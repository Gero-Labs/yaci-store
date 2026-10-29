package com.bloxbean.cardano.yaci.store.blockfrost.transaction.storage.impl;

import com.bloxbean.cardano.yaci.store.blockfrost.transaction.mapper.BFTransactionMapper;
import com.bloxbean.cardano.yaci.store.common.config.StoreProperties;
import com.bloxbean.cardano.yaci.store.common.domain.UtxoKey;
import com.bloxbean.cardano.yaci.store.transaction.domain.Txn;
import com.bloxbean.cardano.yaci.store.transaction.storage.TransactionStorageReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BFTransactionReferenceInputOrderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Connection connection;
    private BFTransactionStorageReaderImpl reader;
    private BFTransactionMapper mapper;
    private JsonNode nexus;
    private JsonNode blockfrost;
    private Txn transaction;

    @BeforeEach
    void setUp() throws Exception {
        loadFixture("tx-reference-input-order");
    }

    private void loadFixture(String directory) throws Exception {
        nexus = fixture(directory, "nexus-utxos.json");
        blockfrost = fixture(directory, "blockfrost-utxos.json");
        JsonNode context = fixture(directory, "blockfrost-transaction.json");
        connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
        DSLContext dsl = DSL.using(connection, SQLDialect.H2);
        dsl.execute("create table address_utxo (tx_hash varchar, output_index int, owner_addr varchar, "
                + "lovelace_amount bigint, amounts varchar, data_hash varchar, inline_datum varchar, "
                + "reference_script_hash varchar, is_collateral_return boolean)");
        dsl.execute("create table tx_input (tx_hash varchar, output_index int, spent_at_block bigint, spent_tx_hash varchar)");
        dsl.execute("create table transaction (tx_hash varchar, collateral_return json, collateral_return_json json)");
        List<UtxoKey> regular = new ArrayList<>();
        List<UtxoKey> collateral = new ArrayList<>();
        List<UtxoKey> references = new ArrayList<>();
        HashSet<String> inserted = new HashSet<>();
        for (JsonNode input : nexus.path("inputs")) {
            UtxoKey key = new UtxoKey(input.path("tx_hash").asText(), input.path("output_index").intValue());
            if (inserted.add(key.getTxHash() + ":" + key.getOutputIndex())) {
                insertUtxo(dsl, key.getTxHash(), input, false);
            }
            if (input.path("reference").asBoolean()) {
                references.add(key);
            } else if (input.path("collateral").asBoolean()) {
                collateral.add(key);
            } else {
                regular.add(key);
                dsl.execute("insert into tx_input values (?, ?, ?, ?)", key.getTxHash(), key.getOutputIndex(),
                        context.path("block_height").longValue(), context.path("hash").asText());
            }
        }
        for (JsonNode output : nexus.path("outputs")) {
            insertUtxo(dsl, context.path("hash").asText(), output, output.path("collateral").asBoolean());
            if (!output.path("consumed_by_tx").isNull()) {
                dsl.execute("insert into tx_input (tx_hash, output_index, spent_tx_hash) values (?, ?, ?)",
                        context.path("hash").asText(), output.path("output_index").intValue(),
                        output.path("consumed_by_tx").asText());
            }
        }
        transaction = Txn.builder().txHash(context.path("hash").asText())
                .blockNumber(context.path("block_height").longValue())
                .invalid(!context.path("valid_contract").asBoolean())
                .inputs(regular).collateralInputs(collateral).referenceInputs(references).build();
        TransactionStorageReader transactionReader = mock(TransactionStorageReader.class);
        when(transactionReader.getTransactionByTxHash(transaction.getTxHash())).thenReturn(Optional.of(transaction));
        reader = new BFTransactionStorageReaderImpl(dsl, transactionReader, objectMapper);
        mapper = Mappers.getMapper(BFTransactionMapper.class);
        ReflectionTestUtils.setField(mapper, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(mapper, "storeProperties", new StoreProperties());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void returnsTheCapturedHostedReferenceInputsBeforeRegularAndCollateralInputs() {
        var raw = reader.findTxUtxos(transaction.getTxHash()).orElseThrow();
        JsonNode rendered = objectMapper.valueToTree(mapper.toUtxosDto(transaction.getTxHash(), raw));
        assertThat(rendered.path("inputs")).isEqualTo(blockfrost.path("inputs"));
        assertThat(rendered.path("outputs")).isEqualTo(blockfrost.path("outputs"));
    }

    @Test
    void leavesRegularAndCollateralRowsInTheirExistingOrderWithoutReferenceInputs() {
        transaction.setReferenceInputs(List.of());
        var raw = reader.findTxUtxos(transaction.getTxHash()).orElseThrow();
        JsonNode rendered = objectMapper.valueToTree(mapper.toUtxosDto(transaction.getTxHash(), raw));
        var expected = objectMapper.createArrayNode();
        blockfrost.path("inputs").forEach(input -> {
            if (!input.path("reference").asBoolean()) expected.add(input);
        });
        assertThat(rendered.path("inputs")).isEqualTo(expected);
        assertThat(rendered.path("outputs")).isEqualTo(blockfrost.path("outputs"));
    }

    @Test
    void sortsTheCapturedMainnetRegularInputsWhilePreservingReferencesCollateralAndOutputs() throws Exception {
        connection.close();
        loadFixture("tx-regular-input-order");

        var raw = reader.findTxUtxos(transaction.getTxHash()).orElseThrow();
        JsonNode rendered = objectMapper.valueToTree(mapper.toUtxosDto(transaction.getTxHash(), raw));

        assertThat(rendered.path("inputs")).isEqualTo(blockfrost.path("inputs"));
        assertThat(rendered.path("outputs")).isEqualTo(blockfrost.path("outputs"));
    }

    @Test
    void ordersRegularInputsByOutputIndexWhenTheirPreviousTransactionHashMatches() throws Exception {
        connection.close();
        loadFixture("tx-regular-input-order");
        DSLContext dsl = DSL.using(connection, SQLDialect.H2);
        JsonNode regular = blockfrost.path("inputs").get(3);
        var indexSeven = regular.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) indexSeven).put("tx_hash", "0".repeat(64));
        ((com.fasterxml.jackson.databind.node.ObjectNode) indexSeven).put("output_index", 7);
        var indexZero = indexSeven.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) indexZero).put("output_index", 0);
        for (JsonNode input : List.of(indexSeven, indexZero)) {
            insertUtxo(dsl, input.path("tx_hash").asText(), input, false);
            dsl.execute("insert into tx_input values (?, ?, ?, ?)", input.path("tx_hash").asText(),
                    input.path("output_index").intValue(), transaction.getBlockNumber(), transaction.getTxHash());
        }
        var expected = objectMapper.createArrayNode();
        for (int i = 0; i < 3; i++) expected.add(blockfrost.path("inputs").get(i));
        expected.add(indexZero).add(indexSeven);
        for (int i = 3; i < blockfrost.path("inputs").size(); i++) expected.add(blockfrost.path("inputs").get(i));

        var raw = reader.findTxUtxos(transaction.getTxHash()).orElseThrow();
        JsonNode rendered = objectMapper.valueToTree(mapper.toUtxosDto(transaction.getTxHash(), raw));

        assertThat(rendered.path("inputs")).isEqualTo(expected);
        assertThat(rendered.path("outputs")).isEqualTo(blockfrost.path("outputs"));
    }

    @Test
    void ordersMultipleValidCollateralInputsByHashThenNumericOutputIndex() throws Exception {
        connection.close();
        loadFixture("tx-regular-input-order");
        DSLContext dsl = DSL.using(connection, SQLDialect.H2);
        JsonNode collateral = blockfrost.path("inputs").get(7);
        var indexTwelve = collateral.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) indexTwelve).put("tx_hash", "0".repeat(64));
        ((com.fasterxml.jackson.databind.node.ObjectNode) indexTwelve).put("output_index", 12);
        var indexSeven = indexTwelve.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) indexSeven).put("output_index", 7);
        List<UtxoKey> collateralKeys = new ArrayList<>(transaction.getCollateralInputs());
        for (JsonNode input : List.of(indexTwelve, indexSeven)) {
            insertUtxo(dsl, input.path("tx_hash").asText(), input, false);
            collateralKeys.add(new UtxoKey(input.path("tx_hash").asText(), input.path("output_index").intValue()));
        }
        transaction.setCollateralInputs(collateralKeys);
        var expected = objectMapper.createArrayNode();
        for (int i = 0; i < 7; i++) expected.add(blockfrost.path("inputs").get(i));
        expected.add(indexSeven).add(indexTwelve).add(collateral);

        var raw = reader.findTxUtxos(transaction.getTxHash()).orElseThrow();
        JsonNode rendered = objectMapper.valueToTree(mapper.toUtxosDto(transaction.getTxHash(), raw));

        assertThat(rendered.path("inputs")).isEqualTo(expected);
        assertThat(rendered.path("outputs")).isEqualTo(blockfrost.path("outputs"));
    }

    private JsonNode fixture(String directory, String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/" + directory + "/" + name)) {
            if (input == null) throw new IllegalStateException("Missing saved fixture " + name);
            return objectMapper.readTree(input);
        }
    }

    private void insertUtxo(DSLContext dsl, String txHash, JsonNode row, boolean collateralReturn) {
        long lovelace = 0;
        var nativeAmounts = objectMapper.createArrayNode();
        for (JsonNode amount : row.path("amount")) {
            if (amount.path("unit").asText().equals("lovelace")) {
                lovelace = Long.parseLong(amount.path("quantity").asText());
            } else {
                nativeAmounts.add(amount);
            }
        }
        dsl.execute("insert into address_utxo values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                txHash, row.path("output_index").intValue(), row.path("address").asText(), lovelace,
                nativeAmounts.toString(), nullable(row, "data_hash"), nullable(row, "inline_datum"),
                nullable(row, "reference_script_hash"), collateralReturn);
    }

    private String nullable(JsonNode row, String field) {
        return row.path(field).isNull() ? null : row.path(field).asText(null);
    }
}
