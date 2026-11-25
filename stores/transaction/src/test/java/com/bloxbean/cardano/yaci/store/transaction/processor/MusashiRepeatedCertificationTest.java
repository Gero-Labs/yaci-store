package com.bloxbean.cardano.yaci.store.transaction.processor;

import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchStateBase;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages.MsgLeiosBlockTxs;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.TransactionListBuilder;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.RollbackEvent;
import com.bloxbean.cardano.yaci.store.events.TransactionEvent;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.bloxbean.cardano.yaci.store.events.internal.PreCommitEvent;
import com.bloxbean.cardano.yaci.store.transaction.TransactionStoreProperties;
import com.bloxbean.cardano.yaci.store.utxo.UtxoStoreConfiguration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.application.name=musashi-repeated-certification-test")
@ActiveProfiles("musashi-postgres-replay")
@Import({MusashiPostgresReplayTest.Database.class, UtxoStoreConfiguration.class})
class MusashiRepeatedCertificationTest {
    private static final String REPEATED_TX = "c3672c610af902e9986b5b17e0217666eff56d433dea232c049ac0d024b424b8";

    @Autowired private ApplicationEventPublisher events;
    @Autowired private JdbcTemplate sql;
    @Autowired private TransactionTemplate dbTransaction;
    @Autowired private TransactionStoreProperties settings;

    @TestFactory
    List<DynamicTest> rollingBackOnlyTheRepeatedInclusionPreservesTheFirstConfirmationAndItsEffects() throws IOException {
        boolean oldReturnCbor = YaciConfig.INSTANCE.isReturnFullTxCbor();
        boolean oldSaveCbor = settings.isSaveFullTxCbor();
        try {
            YaciConfig.INSTANCE.setReturnFullTxCbor(true);
            settings.setSaveFullTxCbor(true);
            return checkRepeatedInclusion();
        } finally {
            YaciConfig.INSTANCE.setReturnFullTxCbor(oldReturnCbor);
            settings.setSaveFullTxCbor(oldSaveCbor);
        }
    }

    private List<DynamicTest> checkRepeatedInclusion() throws IOException {
        var parent = BlockSerializer.INSTANCE.deserialize(fixture("block-10026.hex")).getHeader().getHeaderBody();
        var captured = BlockSerializer.INSTANCE.deserialize(fixture("block-10027.hex"));
        var header = captured.getHeader().getHeaderBody();
        assertThat(header.getPrevHash()).isEqualTo(parent.getBlockHash());
        assertThat(captured.getLeiosCertificate()).isNotNull();
        var announcement = parent.getEbAnnouncement();
        var eb = LeiosEndorserBlockRef.builder().ebHash(announcement.getHash()).ebSlot(parent.getSlot())
                .ebSize(announcement.getSize()).build();
        var wire = (MsgLeiosBlockTxs) LeiosFetchStateBase.deserialize(fixture("msg-leios-block-txs-f951f865.hex"));
        var complete = BlockSerializer.withEndorserBlockTxs(captured, eb, wire.getTransactions());
        var transactions = TransactionListBuilder.build(complete);
        assertThat(transactions).hasSize(237);
        var repeated = transactions.getLast();
        assertThat(repeated.getTxHash()).isEqualTo(REPEATED_TX);
        var control = transactions.getFirst().getTxHash();
        var first = EventMetadata.builder().era(complete.getEra()).protocolMagic(164)
                .block(header.getBlockNumber()).blockHash(header.getBlockHash()).prevBlockHash(parent.getBlockHash())
                .slot(header.getSlot()).noOfTxs(transactions.size()).build();

        deliver(first, transactions);
        var firstRows = rowsAt(first, REPEATED_TX, 236);
        assertThat(firstRows).as("captured block 10027").isEqualTo(Map.of(
                "confirmations", 1L, "signed_bodies", 1L, "spendable_outputs", 1L, "spent_inputs", 1L));
        var firstControl = rowsAt(first, control, 0);
        assertThat(firstControl).containsEntry("confirmations", 1L).containsEntry("signed_bodies", 1L);

        // A controlled second Store delivery, not a claim about the next block on the captured chain.
        var second = first.toBuilder().block(first.getBlock() + 1).slot(first.getSlot() + 1)
                .prevBlockHash(first.getBlockHash()).blockHash("f".repeat(64)).noOfTxs(1).build();
        deliver(second, List.of(repeated));
        var afterRedelivery = rowsAt(first, REPEATED_TX, 236);

        dbTransaction.executeWithoutResult(status -> events.publishEvent(RollbackEvent.builder()
                .rollbackTo(new Point(first.getSlot(), first.getBlockHash()))
                .currentPoint(new Point(second.getSlot(), second.getBlockHash())).currentBlock(second.getBlock()).build()));
        assertThat(rowsAt(first, control, 0)).as("untouched transaction after rollback").isEqualTo(firstControl);
        var afterRollback = rowsAt(first, REPEATED_TX, 236);
        var orphan = rowsAt(second, REPEATED_TX, 0);
        return firstRows.keySet().stream().map(column -> {
            String observation = "%s before=%s repeated=%s rolledBack=%s orphan=%s".formatted(
                    column, firstRows.get(column), afterRedelivery.get(column), afterRollback.get(column), orphan.get(column));
            return DynamicTest.dynamicTest(observation, () -> {
                assertThat(afterRollback.get(column)).as("first block after rollback").isEqualTo(firstRows.get(column));
                assertThat(afterRedelivery.get(column)).as("first block after redelivery").isEqualTo(firstRows.get(column));
                assertThat(orphan.get(column)).as("orphaned block").isEqualTo(0L);
            });
        }).toList();
    }

    private void deliver(EventMetadata block, List<Transaction> transactions) {
        // Exercise the transaction, CBOR and UTxO writers at their Store event boundary.
        dbTransaction.executeWithoutResult(status -> {
            events.publishEvent(new TransactionEvent(block, transactions));
            events.publishEvent(new PreCommitEvent(block));
            events.publishEvent(new CommitEvent<>(block, List.of()));
        });
    }

    private Map<String, Object> rowsAt(EventMetadata block, String txHash, int index) {
        return sql.queryForMap("""
                select (select count(*) from transaction where tx_hash = ? and block_hash = ? and tx_index = ?) as confirmations,
                       (select count(*) from transaction_cbor where tx_hash = ? and slot = ? and cbor_size > 0) as signed_bodies,
                       (select count(*) from address_utxo u where u.tx_hash = ? and u.block_hash = ?
                         and not exists (select 1 from tx_input i where i.tx_hash = u.tx_hash
                                         and i.output_index = u.output_index)) as spendable_outputs,
                       (select count(*) from tx_input where spent_tx_hash = ? and spent_at_block_hash = ?) as spent_inputs
                """, txHash, block.getBlockHash(), index, txHash, block.getSlot(), txHash, block.getBlockHash(),
                txHash, block.getBlockHash());
    }

    private static byte[] fixture(String name) throws IOException {
        try (var stream = Objects.requireNonNull(
                MusashiRepeatedCertificationTest.class.getResourceAsStream("/leios/w42/" + name), name)) {
            return HexUtil.decodeHexString(new String(stream.readAllBytes(), StandardCharsets.US_ASCII).strip());
        }
    }
}
