package com.bloxbean.cardano.yaci.store.blocks.processor;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.store.blocks.BlocksStoreProperties;
import com.bloxbean.cardano.yaci.store.blocks.domain.Block;
import com.bloxbean.cardano.yaci.store.blocks.domain.BlockCbor;
import com.bloxbean.cardano.yaci.store.blocks.domain.Vrf;
import com.bloxbean.cardano.yaci.store.blocks.storage.BlockCborStorage;
import com.bloxbean.cardano.yaci.store.blocks.storage.BlockStorage;
import com.bloxbean.cardano.yaci.store.blocks.util.BlockUtil;
import com.bloxbean.cardano.yaci.store.client.utxo.UtxoClient;
import com.bloxbean.cardano.yaci.store.common.aspect.EnableIf;
import com.bloxbean.cardano.yaci.store.common.util.TransactionFeeUtil;
import com.bloxbean.cardano.yaci.store.events.BlockEvent;
import com.bloxbean.cardano.yaci.store.events.RollbackEvent;
import com.bloxbean.cardano.yaci.store.events.internal.PreCommitEvent;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.bloxbean.cardano.yaci.store.blocks.BlocksStoreConfiguration.STORE_BLOCKS_ENABLED;

@Component
@EnableIf(STORE_BLOCKS_ENABLED)
@Slf4j
public class BlockProcessor {
    private final BlockStorage blockStorage;
    private final BlockCborStorage blockCborStorage;
    private final BlocksStoreProperties blocksStoreProperties;
    private final UtxoClient utxoClient;
    private final List<PendingBlock> pendingBlocks = new ArrayList<>();

    private record PendingBlock(Block block, List<TransactionBody> bodies, Set<Integer> invalidTransactions) {
    }

    public BlockProcessor(BlockStorage blockStorage,
                          BlockCborStorage blockCborStorage,
                          BlocksStoreProperties blocksStoreProperties,
                          UtxoClient utxoClient) {
        this.blockStorage = blockStorage;
        this.blockCborStorage = blockCborStorage;
        this.blocksStoreProperties = blocksStoreProperties;
        this.utxoClient = utxoClient;
    }

    @EventListener
    @Order(1)
    @Transactional
    public void handleBlockHeaderEvent(@NonNull BlockEvent blockEvent) {
        BlockHeader blockHeader = blockEvent.getBlock().getHeader();
        long blockNumber = blockHeader.getHeaderBody().getBlockNumber();
        long slot = blockHeader.getHeaderBody().getSlot();

        Block block = Block.builder()
                .hash(blockHeader.getHeaderBody().getBlockHash())
                .number(blockNumber)
                .slot(slot)
                .totalOutput(BigInteger.ZERO)
                .totalFees(BigInteger.ZERO)
                .epochNumber(blockEvent.getMetadata().getEpochNumber())
                .epochSlot((int) blockEvent.getMetadata().getEpochSlot())
                .blockTime(blockEvent.getMetadata().getBlockTime())
                .era(blockEvent.getMetadata().getEra().getValue())
                .prevHash(blockHeader.getHeaderBody().getPrevHash())
                .issuerVkey(blockHeader.getHeaderBody().getIssuerVkey())
                .vrfVkey(blockHeader.getHeaderBody().getVrfVkey())
                .nonceVrf(Vrf.from(blockHeader.getHeaderBody().getNonceVrf()))
                .leaderVrf(Vrf.from(blockHeader.getHeaderBody().getLeaderVrf()))
                .vrfResult(Vrf.from(blockHeader.getHeaderBody().getVrfResult()))
                .blockBodySize(blockHeader.getHeaderBody().getBlockBodySize())
                .blockBodyHash(blockHeader.getHeaderBody().getBlockBodyHash())
                .protocolVersion(blockHeader.getHeaderBody().getProtocolVersion().get_1()
                        + "." + blockHeader.getHeaderBody().getProtocolVersion().get_2())
                .noOfTxs(blockEvent.getMetadata().getNoOfTxs())
                .slotLeader(blockEvent.getMetadata().getSlotLeader())
                .build();

        if (blockHeader.getHeaderBody().getOperationalCert() != null) {
            block.setOpCertHotVKey(blockHeader.getHeaderBody().getOperationalCert().getHotVKey());
            block.setOpCertSeqNumber(blockHeader.getHeaderBody().getOperationalCert().getSequenceNumber());
            block.setOpcertKesPeriod(blockHeader.getHeaderBody().getOperationalCert().getKesPeriod());
            block.setOpCertSigma(blockHeader.getHeaderBody().getOperationalCert().getSigma());
        }

        var invalidIndexes = blockEvent.getBlock().getInvalidTransactions();
        Set<Integer> invalidTransactions = invalidIndexes == null ? Set.of() : new HashSet<>(invalidIndexes);
        var bodies = blockEvent.getBlock().getTransactionBodies();
        handleTransaction(block, bodies, invalidTransactions);

        var pending = new PendingBlock(block, bodies, invalidTransactions);
        if (resolveFees(pending)) {
            blockStorage.save(block);
        } else {
            // Parallel processing may not have committed an earlier collateral output yet.
            synchronized (pendingBlocks) {
                pendingBlocks.add(pending);
            }
        }

        if (blocksStoreProperties.isSaveCbor()) {
            saveBlockCbor(blockEvent, blockHeader.getHeaderBody().getBlockHash(), slot);
        }
    }

    /**
     * Save block CBOR data to separate table when CBOR storage is enabled.
     * CBOR data is retrieved from the Block object in BlockEvent.
     * The Block.cbor field contains hex-encoded CBOR string which is decoded to bytes.
     */
    private void saveBlockCbor(BlockEvent blockEvent, String blockHash, long slot) {
        com.bloxbean.cardano.yaci.core.model.Block yaciBlock = blockEvent.getBlock();

        String cborHex = yaciBlock.getCbor();
        if (cborHex == null || cborHex.isEmpty()) {
            log.debug("No CBOR data available for block {} (YaciConfig.INSTANCE.setReturnBlockCbor(true) may not be set)", blockHash);
            return;
        }

        byte[] cborData = HexUtil.decodeHexString(cborHex);

        BlockCbor blockCbor = BlockCbor.builder()
                .blockHash(blockHash)
                .cborData(cborData)
                .cborSize(cborData.length)
                .slot(slot)
                .build();

        blockCborStorage.save(blockCbor);
    }

    public void handleTransaction(Block block, List<TransactionBody> transactionBodies) {
        handleTransaction(block, transactionBodies, Set.of());
    }

    private void handleTransaction(Block block, List<TransactionBody> transactionBodies, Set<Integer> invalidTransactions) {
        if (transactionBodies == null || transactionBodies.size() == 0)
            return;

        BigInteger transactionOutputInLovelace = IntStream.range(0, transactionBodies.size())
                .mapToObj(i -> appliedOutputs(transactionBodies.get(i), invalidTransactions.contains(i)))
                .flatMap(List::stream)
                .flatMap(transactionOutput -> transactionOutput.getAmounts().stream())
                .filter(amount -> BlockUtil.amountIsInADA(amount))
                .map(amount -> amount.getQuantity())
                .reduce((a, b) -> a.add(b)).orElse(BigInteger.ZERO);

        BigInteger totalFees = transactionBodies
                .stream()
                .map(transactionBody -> transactionBody.getFee())
                .reduce((a, b) -> a.add(b)).orElse(BigInteger.ZERO);

        block.setTotalOutput(transactionOutputInLovelace == null ? BigInteger.ZERO : transactionOutputInLovelace);
        block.setTotalFees(totalFees == null ? BigInteger.ZERO : totalFees);
    }

    private List<TransactionOutput> appliedOutputs(TransactionBody body, boolean invalid) {
        if (!invalid)
            return body.getOutputs();
        return body.getCollateralReturn() == null ? List.of() : List.of(body.getCollateralReturn());
    }

    private boolean resolveFees(PendingBlock pending) {
        if (pending.invalidTransactions().isEmpty())
            return true;

        BigInteger totalFees = BigInteger.ZERO;
        for (int i = 0; i < pending.bodies().size(); i++) {
            BigInteger fee;
            try {
                fee = TransactionFeeUtil.resolveFee(pending.bodies().get(i), pending.invalidTransactions().contains(i),
                        utxoClient::getUtxosByIds);
            } catch (RestClientException e) {
                log.debug("Collateral UTxO lookup unavailable for block {}", pending.block().getHash(), e);
                return false;
            }
            if (fee == null)
                return false;
            totalFees = totalFees.add(fee);
        }
        pending.block().setTotalFees(totalFees);
        return true;
    }

    @EventListener
    @Order(0)
    @Transactional
    public void handleCollateralFees(PreCommitEvent event) {
        synchronized (pendingBlocks) {
            // Resolve the entire pending batch first. A missing fee must not publish partial
            // blocks or advance the cursor.
            for (var pending : pendingBlocks) {
                if (!resolveFees(pending))
                    throw new IllegalStateException("Collateral fee unresolved for block " + pending.block().getHash());
            }
            for (var pending : pendingBlocks)
                blockStorage.save(pending.block());
            pendingBlocks.clear();
        }
    }

    @EventListener
    @Order(1)
    @Transactional
    public void handleRollbackEvent(@NotNull RollbackEvent rollbackEvent) {
        synchronized (pendingBlocks) {
            pendingBlocks.clear();
        }
        int count = blockStorage.deleteBySlotGreaterThan(rollbackEvent.getRollbackTo().getSlot());
        log.info("Rollback -- {} block records", count);

        if (blocksStoreProperties.isSaveCbor()) {
            int cborDeleted = blockCborStorage.deleteBySlotGreaterThan(rollbackEvent.getRollbackTo().getSlot());
            log.info("Rollback -- {} block_cbor records", cborDeleted);
        }
    }
}
