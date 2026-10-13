package com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl;

import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl.model.BFAssetAddress;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.utxo.UtxoStoreProperties;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.sql.DriverManager;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The actual PostgreSQL holders query must avoid historic reads while retaining its complete contract. */
@Testcontainers(disabledWithoutDocker = true)
class BFAssetAddressesCurrentSetTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String UNIT = "aa".repeat(28); // empty asset name
    private static final String LONG_ADDRESS = "byron" + "x".repeat(600);
    private DSLContext dsl;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
        dsl.execute("drop table if exists address_utxo_unspent, address_utxo, tx_input, transaction");
        dsl.execute("create table address_utxo (tx_hash varchar primary key, output_index smallint, slot bigint, "
                + "tx_index int, owner_addr varchar(500), owner_addr_full text, amounts jsonb)");
        dsl.execute("create table address_utxo_unspent (like address_utxo including all)");
        dsl.execute("create table transaction (tx_hash varchar primary key, tx_index int)");
        dsl.execute("create table tx_input (tx_hash varchar, output_index smallint, primary key (output_index, tx_hash))");
        // A owns oldest+newest, so ascending and descending are not reversed lists.
        output("a-old", 0, 10, 0, "a", null, "2", true, true);
        output("a-new", 1, 50, 1, "a", null, "7", true, true);
        output("b", 1, 20, 0, "b", null, "3", true, true);
        output("c", 0, 20, 1, "c", null, "4", true, true);
        output("d", 2, 20, 0, "d", null, "5", true, true);
        output("long", 0, 30, 0, LONG_ADDRESS.substring(0, 500), LONG_ADDRESS,
                "9223372036854775808", true, true);
        output("spent", 0, 1, 0, "spent", null, "100", false, true);
        dsl.execute("insert into tx_input values ('spent', 0)");
        // Current table retains old null output tx_index; transaction supplies the canonical key.
        dsl.execute("update address_utxo_unspent set tx_index = null where tx_hash = 'c'");
        output("orphan", 0, 0, 0, "orphan", null, "500", true, false);
    }

    @AfterEach
    void closeConnection() throws Exception {
        connection.close();
    }

    private void output(String hash, int index, long slot, int txIndex, String address, String full,
                        String quantity, boolean current, boolean transaction) {
        String amounts = "[{\"unit\":\"lovelace\",\"quantity\":\"1000000\"},"
                + "{\"unit\":\"" + UNIT + "\",\"policy_id\":\"" + UNIT + "\",\"quantity\":\"" + quantity + "\"}]";
        dsl.execute("insert into address_utxo values (?, ?, ?, ?, ?, ?, ?::jsonb)",
                hash, index, slot, txIndex, address, full, amounts);
        if (current) dsl.execute("insert into address_utxo_unspent select * from address_utxo where tx_hash = ?", hash);
        if (transaction) dsl.execute("insert into transaction values (?, ?)", hash, txIndex);
    }

    private AnnotationConfigApplicationContext context(boolean enabled) {
        var source = new MapConfigurationPropertySource(Map.of(
                "store.utxo.unspent-table-enabled", enabled,
                "store.utxo.unspent-table-read-enabled", enabled));
        UtxoStoreProperties properties = new UtxoStoreProperties();
        new Binder(source).bind("store.utxo", Bindable.ofInstance(properties));
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(DSLContext.class, () -> dsl);
        context.registerBean(UtxoStoreProperties.class, () -> properties);
        context.register(BFAssetStorageReaderImpl.class);
        context.refresh();
        return context;
    }

    @Test
    void configuredCurrentReadDoesNotTouchHistoricalOutputsOrSpends() {
        // A revoked historical table makes any hidden historic scan fail, rather than returning a false pass.
        dsl.execute("alter table address_utxo rename to archived_address_utxo");
        dsl.execute("alter table tx_input rename to archived_tx_input");
        try (var context = context(true)) {
            assertThat(context.getBean(BFAssetStorageReaderImpl.class).findAssetAddresses(UNIT, 0, 100, Order.asc))
                    .containsExactlyElementsOf(ascending());
        } finally {
            dsl.execute("alter table archived_address_utxo rename to address_utxo");
            dsl.execute("alter table archived_tx_input rename to tx_input");
        }
    }

    @Test
    void everyPageKeepsAsymmetricOrderTiesFullQuantitiesAndLongAddresses() {
        try (var context = context(true)) {
            var reader = context.getBean(BFAssetStorageReaderImpl.class);
            for (Order order : Order.values()) {
                var expected = order == Order.asc ? ascending() : descending();
                for (int count : List.of(1, 2, 3, 100)) {
                    for (int page = 0; page < 7; page++) {
                        int start = Math.min(page * count, expected.size());
                        int end = Math.min(start + count, expected.size());
                        assertThat(reader.findAssetAddresses(UNIT, page, count, order))
                                .as("order=%s count=%s page=%s", order, count, page)
                                .containsExactlyElementsOf(expected.subList(start, end));
                    }
                }
            }
            assertThat(reader.findAssetAddresses("bb".repeat(28), 0, 100, Order.asc)).isEmpty();
        }
    }

    @Test
    void disabledCurrentReadKeepsTheOriginalAntiJoin() {
        dsl.execute("truncate address_utxo_unspent");
        try (var context = context(false)) {
            assertThat(context.getBean(BFAssetStorageReaderImpl.class).findAssetAddresses(UNIT, 0, 100, Order.asc))
                    .containsExactlyElementsOf(ascending());
        }
    }

    private static BFAssetAddress holder(String address, String quantity, long slot) {
        return new BFAssetAddress(address, new BigInteger(quantity), slot);
    }

    private static List<BFAssetAddress> ascending() {
        return List.of(holder("a", "9", 10), holder("b", "3", 20), holder("d", "5", 20),
                holder("c", "4", 20), holder(LONG_ADDRESS, "9223372036854775808", 30));
    }

    private static List<BFAssetAddress> descending() {
        return List.of(holder("a", "9", 50), holder(LONG_ADDRESS, "9223372036854775808", 30),
                holder("c", "4", 20), holder("d", "5", 20), holder("b", "3", 20));
    }
}
