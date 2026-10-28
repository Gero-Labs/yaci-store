package com.bloxbean.cardano.yaci.store.blockfrost.transaction.mapper;

import com.bloxbean.cardano.yaci.store.blockfrost.transaction.dto.BFTxInputDto;
import com.bloxbean.cardano.yaci.store.blockfrost.transaction.dto.BFTxOutputDto;
import com.bloxbean.cardano.yaci.store.blockfrost.transaction.storage.impl.model.TxInputRaw;
import com.bloxbean.cardano.yaci.store.blockfrost.transaction.storage.impl.model.TxOutputRaw;
import com.bloxbean.cardano.yaci.store.blockfrost.transaction.storage.impl.model.TxUtxosRaw;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BFTransactionMapperTest {
    private static final String TX_HASH = "a".repeat(64);
    private final BFTransactionMapper mapper = mapper();

    @Test
    void retainedTransactionsPutCollateralAfterAllRegularAndReferenceInputs() throws Exception {
        try (var resource = getClass().getResourceAsStream("/transaction/retained-input-orders.json")) {
            JsonNode fixtures = new ObjectMapper().readTree(resource);
            assertThat(fixtures.size()).isEqualTo(6);
            for (JsonNode fixture : fixtures) {
                List<TxInputRaw> inputs = new ArrayList<>();
                fixture.path("inputs").forEach(input -> inputs.add(input(input.path("tx_hash").asText(),
                        input.path("output_index").asInt(), input.path("collateral").asBoolean(),
                        input.path("reference").asBoolean())));

                List<BFTxInputDto> result = mapper.toUtxosDto(fixture.path("tx_hash").asText(), raw(inputs)).getInputs();

                List<TxInputRaw> expected = java.util.stream.Stream.concat(inputs.stream().filter(i -> !i.isCollateral()),
                        inputs.stream().filter(TxInputRaw::isCollateral)).toList();
                assertThat(result).as(fixture.path("tx_hash").asText())
                        .extracting(i -> i.getTxHash() + ":" + i.getOutputIndex() + ":" + i.getCollateral() + ":" + i.getReference())
                        .containsExactlyElementsOf(expected.stream().map(i -> i.getTxHash() + ":" + i.getOutputIndex()
                                + ":" + i.isCollateral() + ":" + i.isReference()).toList());
            }
        }
    }

    @Test
    void collateralPartitionKeepsInterleavedRegularReferenceOrderAndDuplicateOutpoints() {
        TxInputRaw referenceFirst = input("f".repeat(64), 2, false, true);
        TxInputRaw regularFirst = input("a".repeat(64), 1, false, false);
        TxInputRaw collateralFirst = input("b".repeat(64), 3, true, false);
        TxInputRaw referenceSecond = input("c".repeat(64), 0, false, true);
        TxInputRaw regularDuplicate = input("a".repeat(64), 1, false, false);
        TxInputRaw collateralSecond = input("a".repeat(64), 1, true, false);
        List<TxInputRaw> inputs = List.of(referenceFirst, regularFirst, collateralFirst,
                referenceSecond, regularDuplicate, collateralSecond);

        List<BFTxInputDto> result = mapper.toUtxosDto(TX_HASH, raw(inputs)).getInputs();

        assertThat(result).extracting(BFTxInputDto::getTxHash).containsExactly(referenceFirst.getTxHash(),
                regularFirst.getTxHash(), referenceSecond.getTxHash(), regularDuplicate.getTxHash(),
                collateralFirst.getTxHash(), collateralSecond.getTxHash());
        assertThat(result).extracting(BFTxInputDto::getCollateral).containsExactly(false, false, false, false, true, true);
        assertThat(result).extracting(BFTxInputDto::getReference).containsExactly(true, false, true, false, false, false);
        assertThat(result.get(1)).isEqualTo(result.get(3));
        assertThat(inputs).containsExactly(referenceFirst, regularFirst, collateralFirst,
                referenceSecond, regularDuplicate, collateralSecond);
    }

    @Test
    void outputsFollowOutputIndexThenCollateralRegardlessOfStorageRowOrder() {
        TxOutputRaw collateral = output(1, true);
        TxOutputRaw finalRegular = output(2, false);
        TxOutputRaw sameIndexRegular = output(1, false);
        TxOutputRaw firstRegular = output(0, false);
        List<TxOutputRaw> outputs = List.of(collateral, finalRegular, sameIndexRegular, firstRegular);
        TxUtxosRaw raw = raw(List.of());
        raw.setOutputs(outputs);

        List<BFTxOutputDto> result = mapper.toUtxosDto(TX_HASH, raw).getOutputs();

        assertThat(result).extracting(BFTxOutputDto::getOutputIndex).containsExactly(0, 1, 1, 2);
        assertThat(result).extracting(BFTxOutputDto::getCollateral).containsExactly(false, false, true, false);
        assertThat(result).extracting(BFTxOutputDto::getAddress)
                .containsExactly(firstRegular.getAddress(), sameIndexRegular.getAddress(),
                        collateral.getAddress(), finalRegular.getAddress());
        assertThat(outputs).containsExactly(collateral, finalRegular, sameIndexRegular, firstRegular);
    }

    @Test
    void parsedCollateralReturnJoinsFinalOutputOrdering() {
        TxUtxosRaw raw = raw(List.of());
        raw.setOutputs(List.of(output(2, false), output(0, false)));
        raw.setCollateralReturnRefJson("{\"output_index\":1}");
        raw.setCollateralReturnDataJson("{\"address\":\"collateral-return\",\"amounts\":[{\"unit\":\"lovelace\",\"quantity\":\"9\"}]}");

        List<BFTxOutputDto> result = mapper.toUtxosDto(TX_HASH, raw).getOutputs();

        assertThat(result).extracting(BFTxOutputDto::getOutputIndex).containsExactly(0, 1, 2);
        assertThat(result.get(1).getCollateral()).isTrue();
        assertThat(result.get(1).getAmount().getFirst().getQuantity()).isEqualTo("9");
        assertThat(result.get(1).getAddress()).isEqualTo("collateral-return");
    }

    private static BFTransactionMapper mapper() {
        var mapper = new BFTransactionMapperImpl();
        mapper.objectMapper = new ObjectMapper();
        return mapper;
    }

    private static TxUtxosRaw raw(List<TxInputRaw> inputs) {
        return TxUtxosRaw.builder().inputs(inputs).outputs(List.of()).build();
    }

    private static TxInputRaw input(String txHash, int index, boolean collateral, boolean reference) {
        return TxInputRaw.builder().txHash(txHash).outputIndex(index).collateral(collateral).reference(reference)
                .address("address-" + txHash + "-" + index).lovelaceAmount(42L).dataHash("datum-" + index)
                .inlineDatum("d87980").referenceScriptHash("script-" + index).build();
    }

    private static TxOutputRaw output(int index, boolean collateral) {
        return TxOutputRaw.builder().txHash(TX_HASH).outputIndex(index).isCollateralReturn(collateral)
                .address("output-" + index + "-" + collateral).lovelaceAmount(42L).consumedByTx("b".repeat(64))
                .dataHash("datum-" + index).inlineDatum("d87980").referenceScriptHash("script-" + index).build();
    }
}
