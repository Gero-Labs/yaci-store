package com.bloxbean.cardano.yaci.store.blockfrost.account.storage.impl;

import com.bloxbean.cardano.yaci.store.blockfrost.account.storage.impl.model.AccountTransaction;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.utxo.UtxoStoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.conf.StatementType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BFAccountTransactionOrderingTest {
    private static final String STAKE = "stake_test_ordering";
    private static final String TOKEN = "11".repeat(28) + "01";
    private Connection connection;
    private DSLContext dsl;
    private BFAccountStorageReaderImpl reader;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:account_order;DATABASE_TO_LOWER=TRUE", "sa", "");
        // H2 2.2.224 returns no rows for the repeated parameterized CTE used here. Its identical
        // inlined SQL returns the populated result. Keep this workaround local to H2 tests;
        // prepared PostgreSQL execution and query cost require separate acceptance.
        dsl = DSL.using(connection, SQLDialect.H2, new Settings().withRenderSchema(false)
                .withStatementType(StatementType.STATIC_STATEMENT));
        // Relevant columns and primary keys from the production H2 store migrations.
        dsl.execute("""
                create table address_utxo (
                    tx_hash varchar(64) not null, output_index int not null, slot bigint,
                    block bigint, tx_index int, block_time bigint, owner_addr varchar(500),
                    owner_stake_addr varchar(255), lovelace_amount bigint, amounts json,
                    primary key (output_index, tx_hash))
                """);
        dsl.execute("""
                create table tx_input (
                    tx_hash varchar(64) not null, output_index int not null, spent_tx_hash varchar(64),
                    spent_at_slot bigint, spent_at_block bigint, spent_block_time bigint,
                    primary key (output_index, tx_hash))
                """);
        dsl.execute("""
                create table transaction (
                    tx_hash varchar(64) primary key, slot bigint, block bigint,
                    tx_index int, block_time bigint)
                """);
        reader = new BFAccountStorageReaderImpl(dsl, new UtxoStoreProperties());
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @ParameterizedTest
    @EnumSource(Order.class)
    void pagesFollowTransactionIndexWhenOneBlockCrossesPageBoundaries(Order order) {
        receive("post", 101, 0, "addr", 0);
        receive("a100", 100, 3, "addr", 0);
        receive("pre", 99, 0, "addr", 0);
        receive("m100", 100, 8, "addr", 0);
        receive("z100", 100, 1, "addr", 0);

        List<AccountTransaction> asc = List.of(row("addr", "pre", 99, 0),
                row("addr", "z100", 100, 1), row("addr", "a100", 100, 3),
                row("addr", "m100", 100, 8), row("addr", "post", 101, 0));
        assertPopulatedPages(order, 2, ordered(asc, order), null, null);
    }

    @ParameterizedTest
    @EnumSource(Order.class)
    void pagesFollowEarliestOutputForMultipleAddressesOfOneTransaction(Order order) {
        receive("multi", 100, 3, "addr_m", 2);
        receive("multi", 100, 3, "addr_z", 5); // Later duplicate must not move addr_z.
        receive("multi", 100, 3, "addr_y", 4);
        receive("multi", 100, 3, "addr_a", 1);
        receive("multi", 100, 3, "addr_b", 3);
        receive("multi", 100, 3, "addr_z", 0);

        List<AccountTransaction> asc = List.of(row("addr_z", "multi", 100, 3),
                row("addr_a", "multi", 100, 3), row("addr_m", "multi", 100, 3),
                row("addr_b", "multi", 100, 3), row("addr_y", "multi", 100, 3));
        assertPopulatedPages(order, 2, ordered(asc, order), null, null);
    }

    @ParameterizedTest
    @EnumSource(Order.class)
    void receivesAndSpendsYieldOneRowPerTransactionAndAddressWithoutChangingAmounts(Order order) {
        receiveAmount("left", 10, 2, "addr_z", 0, 100, 7, STAKE);
        receiveAmount("left", 10, 2, "addr_z", 1, 50, 3, STAKE);
        receiveAmount("right", 10, 5, "addr_a", 0, 200, 11, STAKE);
        receiveAmount("mixed", 20, 4, "addr_z", 0, 70, 5, STAKE);
        receiveAmount("mixed", 20, 4, "addr_m", 1, 80, 6, STAKE);
        receiveAmount("foreign", 15, 1, "addr_foreign", 0, 999, 999, "another_stake");
        spend("left", 0, "mixed", 20, 4);
        spend("left", 1, "mixed", 20, 4);
        spend("right", 0, "mixed", 20, 4);

        List<AccountTransaction> asc = List.of(row("addr_z", "left", 10, 2),
                row("addr_a", "right", 10, 5), row("addr_z", "mixed", 20, 4),
                row("addr_a", "mixed", 20, 4), row("addr_m", "mixed", 20, 4));
        assertPopulatedPages(order, 2, ordered(asc, order), null, null);
        var totals = reader.getAddressesTotal(STAKE).orElseThrow();
        assertThat(totals.stakeAddress()).isEqualTo(STAKE);
        assertThat(totals.txCount()).isEqualTo(3);
        assertThat(totals.receivedSum()).isEqualTo(Map.of("lovelace", BigInteger.valueOf(500), TOKEN, BigInteger.valueOf(32)));
        assertThat(totals.sentSum()).isEqualTo(Map.of("lovelace", BigInteger.valueOf(350), TOKEN, BigInteger.valueOf(21)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void terminalReceivingAddressIsRetainedAlongsideEverySpendingAddress(int inputAddresses) {
        receive("left", 10, 2, "addr_z", 0);
        spend("left", 0, "terminal", 20, 4);
        if (inputAddresses == 2) {
            receive("right", 10, 5, "addr_a", 0);
            spend("right", 0, "terminal", 20, 4);
        }
        receive("terminal", 20, 4, "addr_m", 0);

        List<AccountTransaction> expected = inputAddresses == 1
                ? List.of(row("addr_z", "terminal", 20, 4), row("addr_m", "terminal", 20, 4))
                : List.of(row("addr_z", "terminal", 20, 4), row("addr_a", "terminal", 20, 4), row("addr_m", "terminal", 20, 4));
        assertPopulatedPages(Order.asc, 1, expected, "20", "20");
        assertPopulatedPages(Order.desc, 1, expected.reversed(), "20", "20");
    }

    @ParameterizedTest
    @EnumSource(Order.class)
    void inclusiveBlockIndexBoundsApplyToReceiveAndSpendOnlyTransactionsBeforePaging(Order order) {
        receive("old", 90, 0, "addr", 0);
        receive("old", 90, 0, "addr", 1);
        spend("old", 0, "z_spend", 100, 1);
        spend("old", 1, "a_spend", 100, 7);
        receive("m_receive", 100, 4, "addr", 0);
        receive("after", 101, 0, "addr", 0);

        assertPopulatedPages(order, 1, ordered(List.of(row("addr", "m_receive", 100, 4),
                row("addr", "a_spend", 100, 7)), order), "100:4", "100:7");
        assertPopulatedPages(order, 1, ordered(List.of(row("addr", "z_spend", 100, 1),
                row("addr", "m_receive", 100, 4)), order), "100:1", "100:4");
        assertPopulatedPages(order, 2, ordered(List.of(row("addr", "z_spend", 100, 1),
                row("addr", "m_receive", 100, 4), row("addr", "a_spend", 100, 7)), order), "100", "100");
        assertThat(reader.findTransactions(STAKE, 0, 2, order, "100:8", "100:9")).isEmpty();
    }

    @Test
    void savedPreviewFullBodiesMatchBothDirectionsAndEveryPopulatedPage() throws Exception {
        JsonNode fixture;
        try (var stream = getClass().getResourceAsStream("/fixtures/transactions/preview.json")) {
            fixture = new ObjectMapper().readTree(stream);
        }
        String stake = fixture.get("stake_address").asText();
        // Saved HTTP rows expose no spent output metadata. This control reconstructs them as
        // receive-only outputs; synthetic tests above prove actual mixed receive/spend semantics.
        for (JsonNode row : fixture.get("asc")) {
            String tx = row.get("tx_hash").asText();
            long block = row.get("block_height").asLong();
            int index = row.get("tx_index").asInt();
            long time = row.get("block_time").asLong();
            transaction(tx, block, index, time);
            output(tx, 0, block, index, time, row.get("address").asText(), stake, 1, 0);
        }
        for (Order order : Order.values()) {
            List<AccountTransaction> expected = new ArrayList<>();
            for (JsonNode row : fixture.get(order.name())) {
                expected.add(new AccountTransaction(row.get("address").asText(), row.get("tx_hash").asText(),
                        row.get("tx_index").asLong(), row.get("block_height").asLong(), row.get("block_time").asLong()));
            }
            assertThat(expected).hasSize(108);
            assertThat(reader.findTransactions(stake, 0, 100, order, null, null)).containsExactlyElementsOf(expected.subList(0, 100));
            assertThat(reader.findTransactions(stake, 1, 100, order, null, null)).containsExactlyElementsOf(expected.subList(100, 108));
            assertThat(reader.findTransactions(stake, 2, 100, order, null, null)).isEmpty();
        }
    }

    private void assertPopulatedPages(Order order, int count, List<AccountTransaction> expected, String from, String to) {
        List<AccountTransaction> actual = new ArrayList<>();
        int pages = (expected.size() + count - 1) / count;
        for (int page = 0; page < pages; page++) {
            List<AccountTransaction> rows = reader.findTransactions(STAKE, page, count, order, from, to);
            assertThat(rows).as("populated %s page %s", order, page)
                    .containsExactlyElementsOf(expected.subList(page * count, Math.min((page + 1) * count, expected.size())));
            actual.addAll(rows);
        }
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(reader.findTransactions(STAKE, pages, count, order, from, to)).isEmpty();
    }

    private List<AccountTransaction> ordered(List<AccountTransaction> asc, Order order) {
        return order == Order.desc ? asc.reversed() : asc;
    }

    private AccountTransaction row(String address, String tx, long block, int index) {
        return new AccountTransaction(address, tx, index, block, block * 10);
    }

    private void receive(String tx, long block, int index, String address, int outputIndex) {
        receiveAmount(tx, block, index, address, outputIndex, 1, 0, STAKE);
    }

    private void receiveAmount(String tx, long block, int index, String address, int outputIndex,
                               long lovelace, int token, String stake) {
        transaction(tx, block, index, block * 10);
        output(tx, outputIndex, block, index, block * 10, address, stake, lovelace, token);
    }

    private void transaction(String tx, long block, int index, long time) {
        dsl.execute("merge into transaction (tx_hash, slot, block, tx_index, block_time) key(tx_hash) values (?, ?, ?, ?, ?)",
                tx, block * 100, block, index, time);
    }

    private void output(String tx, int outputIndex, long block, int index, long time,
                        String address, String stake, long lovelace, int token) {
        String amounts = "[{\"unit\":\"" + TOKEN + "\",\"quantity\":\"" + token + "\"}]";
        dsl.execute("""
                insert into address_utxo (tx_hash, output_index, slot, block, tx_index, block_time,
                    owner_addr, owner_stake_addr, lovelace_amount, amounts)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ? format json)
                """, tx, outputIndex, block * 100, block, index, time, address, stake, lovelace, amounts);
    }

    private void spend(String inputTx, int outputIndex, String spendingTx, long block, int index) {
        transaction(spendingTx, block, index, block * 10);
        dsl.execute("""
                insert into tx_input (tx_hash, output_index, spent_tx_hash, spent_at_slot, spent_at_block, spent_block_time)
                values (?, ?, ?, ?, ?, ?)
                """, inputTx, outputIndex, spendingTx, block * 100, block, block * 10);
    }
}
