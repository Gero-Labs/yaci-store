package com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl;

import com.bloxbean.cardano.yaci.store.blockfrost.asset.service.BFAssetService;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.BFAssetQueryDeadline;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl.model.BFAssetHistory;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Query regressions use synthetic slots; original CBOR for the retained zero hashes is unverified. */
@Testcontainers
class BFAssetNonzeroMintEventsTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String POLICY = "919d4c2c9455016289341b1a14dedf697687af31751170d56a31466e";
    private static final String UNIT = POLICY + "74444a4544";
    private static final String ZERO_ONE = "c7cba666400b1deec21a674f01846b8733661dabbdcb998a9411ab449f7c73d3";
    private static final String ZERO_TWO = "652dc5c31506ec8a21b9bd0bf035385cbad1c20f79fb26054d29ba82b76b804b";
    private static final BigInteger LARGE = new BigInteger("9007199254740993");
    private Connection connection;
    private DSLContext dsl;
    private BFAssetStorageReaderImpl reader;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
        schema(dsl);
        reader = new BFAssetStorageReaderImpl(dsl);
    }

    @AfterEach
    void closeConnection() throws Exception {
        connection.close();
    }

    private static void schema(DSLContext db) {
        db.execute("drop table if exists assets, transaction, address_utxo, tx_input");
        db.execute("create table assets(tx_hash varchar(64),unit varchar(120),policy varchar(56),"
                + "asset_name varchar(255),fingerprint varchar(100),mint_type varchar(10),quantity bigint,slot bigint)");
        db.execute("create table transaction(tx_hash varchar(64) primary key,tx_index int,slot bigint)");
        db.execute("create table address_utxo(tx_hash varchar(64),output_index smallint,slot bigint,tx_index int,"
                + "block_hash varchar(64),block bigint,block_time bigint,owner_addr varchar(500),owner_addr_full text,"
                + "lovelace_amount bigint,amounts " + (db.dialect() == SQLDialect.POSTGRES ? "jsonb" : "json")
                + ",data_hash varchar(64),inline_datum text,reference_script_hash varchar(56))");
        db.execute("create table tx_input(tx_hash varchar(64),output_index smallint)");
    }

    private static void event(DSLContext db, String unit, String hash, long slot, int index, BigInteger amount) {
        db.execute("insert into transaction values (?,?,?)", hash, index, slot);
        db.execute("insert into assets values (?,?,?,'DJED','asset-fingerprint',?,?,?)",
                hash, unit, unit.substring(0, 56), amount.signum() > 0 ? "MINT" : "BURN", amount, slot);
    }

    @Test
    void detailCountsOnlyNonzeroEventsAndSkipsEarlierZeroForInitialMint() {
        event(dsl, UNIT, ZERO_ONE, 1, 0, BigInteger.ZERO);
        event(dsl, UNIT, "first-mint", 2, 0, LARGE);
        event(dsl, UNIT, ZERO_TWO, 3, 0, BigInteger.ZERO);
        event(dsl, UNIT, "real-burn", 4, 0, BigInteger.valueOf(-7));

        var info = reader.findAssetInfo(UNIT).orElseThrow();
        assertThat(info.initialMintTxHash()).isEqualTo("first-mint");
        assertThat(info.mintOrBurnCount()).isEqualTo(2);
        assertThat(info.quantity()).isEqualTo(new BigInteger("9007199254740986"));
        // The raw writer projection remains intact, including both zero rows.
        assertThat(dsl.fetchOne("select count(*) from assets").get(0, Integer.class)).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 100})
    void postgresFiltersBeforeEveryPageBoundaryAndUsesActualSameSlotTransactionIndex(int count) {
        List<BFAssetHistory> expected = pageFixture(dsl);
        assertEveryPage(reader, expected, count);
        var info = reader.findAssetInfo(UNIT).orElseThrow();
        assertThat(info.mintOrBurnCount()).isEqualTo(253);
        assertThat(dsl.fetchOne("select count(*) from assets").get(0, Integer.class)).isEqualTo(255);
    }

    @Test
    void fullyBurnedAssetRetainsBothRealEventsAndZeroLifetimeQuantity() {
        event(dsl, UNIT, "mint", 1, 0, LARGE);
        event(dsl, UNIT, ZERO_ONE, 2, 0, BigInteger.ZERO);
        event(dsl, UNIT, "burn", 3, 0, LARGE.negate());
        event(dsl, UNIT, ZERO_TWO, 4, 0, BigInteger.ZERO);
        var info = reader.findAssetInfo(UNIT).orElseThrow();
        assertThat(info.quantity()).isZero();
        assertThat(info.mintOrBurnCount()).isEqualTo(2);
        assertThat(reader.findAssetHistory(UNIT, 0, 100, Order.asc)).containsExactly(
                new BFAssetHistory("mint", "minted", LARGE),
                new BFAssetHistory("burn", "burned", LARGE.negate()));
        assertThat(reader.findAssets(0, 100, Order.asc)).singleElement()
                .satisfies(asset -> assertThat(asset.quantity()).isZero());
        assertThat(reader.findAssetsByPolicy(POLICY, 0, 100, Order.asc)).singleElement()
                .satisfies(asset -> assertThat(asset.quantity()).isZero());
        assertThat(new BFAssetService(reader).getAssetUtxos(UNIT, 0, 100, Order.asc, null, null)).isEmpty();
    }

    @Test
    void zeroOnlyDetailAndHistoryAreAbsentWhileIndependentRawIdentityLookupIsPreserved() {
        event(dsl, UNIT, ZERO_ONE, 1, 0, BigInteger.ZERO);
        assertThat(reader.findAssetInfo(UNIT)).isEmpty();
        assertThat(reader.findAssetHistory(UNIT, 0, 100, Order.asc)).isEmpty();
        assertThat(reader.assetExists(UNIT, new BFAssetQueryDeadline())).isTrue();
        var service = new BFAssetService(reader);
        assertThatThrownBy(() -> service.getAsset(UNIT)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getAssetHistory(UNIT, 0, 100, Order.asc))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.getAssetUtxos(UNIT, 0, 100, Order.asc, null, null)).isEmpty();
        String unknown = "ab".repeat(28);
        assertThat(reader.findAssetInfo(unknown)).isEmpty();
        assertThat(reader.assetExists(unknown, new BFAssetQueryDeadline())).isFalse();
        assertThatThrownBy(() -> service.getAssetUtxos(unknown, 0, 100, Order.asc, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void h2FallbackRefillsPagesAndPreservesNegativeAmountsWithoutChangingRawEvents() throws Exception {
        try (Connection h2 = DriverManager.getConnection("jdbc:h2:mem:nonzero-events;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
            DSLContext db = DSL.using(h2, SQLDialect.H2);
            schema(db);
            List<BFAssetHistory> expected = pageFixture(db);
            var fallback = new BFAssetStorageReaderImpl(db);
            assertEveryPage(fallback, expected, 100);
            assertThat(fallback.findAssetInfo(UNIT).orElseThrow().mintOrBurnCount()).isEqualTo(253);
            assertThat(db.fetchOne("select count(*) from assets").get(0, Integer.class)).isEqualTo(255);
        }
    }

    @Test
    void outputPresentUnitDoesNotGainLogicalMintInfoOrLoseItsPopulatedUtxoPage() {
        String outputOnly = "ab".repeat(28);
        dsl.execute("insert into transaction values ('output',0,100)");
        dsl.execute("insert into address_utxo values ('output',0,100,0,'block',10,1000,'address',null,"
                + "1000000,?::jsonb,null,null,null)",
                "[{\"unit\":\"" + outputOnly + "\",\"quantity\":\"1\"}]");
        var service = new BFAssetService(reader);
        assertThat(reader.findAssetInfo(outputOnly)).isEmpty();
        assertThat(reader.assetExists(outputOnly, new BFAssetQueryDeadline())).isFalse();
        assertThat(service.getAssetUtxos(outputOnly, 0, 100, Order.asc, null, null)).hasSize(1);
        assertThatThrownBy(() -> service.getAsset(outputOnly)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        // Existing empty-page point lookup stays independent; this patch does not broaden asset identity.
        assertThatThrownBy(() -> service.getAssetUtxos(outputOnly, 1, 100, Order.asc, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static List<BFAssetHistory> pageFixture(DSLContext db) {
        List<BFAssetHistory> expected = new ArrayList<>();
        for (int i = 0; i < 253; i++) {
            String hash = switch (i) {
                case 98 -> "162ed49b28ab9a87bd59f7a95cec4cfae5f932cec3be777b54477fd75fcdface";
                case 99 -> "77574471f96406ea612087a73bcc6529deef49c005b8961e50f8a4e3f29785ee";
                default -> "%064x".formatted(5000 - i); // Reverse hash order contradicts tx_index order.
            };
            long slot = 100 + i / 5;
            int txIndex = i % 5 + (i >= 95 && i < 100 ? 2 : 0);
            BigInteger amount = i % 7 == 0 ? BigInteger.valueOf(-5) : BigInteger.TEN;
            event(db, UNIT, hash, slot, txIndex, amount);
            expected.add(new BFAssetHistory(hash, amount.signum() < 0 ? "burned" : "minted", amount));
        }
        // Their synthetic positions mirror the retained first100 discrepancy (95/96).
        event(db, UNIT, ZERO_ONE, 119, 0, BigInteger.ZERO);
        event(db, UNIT, ZERO_TWO, 119, 1, BigInteger.ZERO);
        return expected;
    }

    private static void assertEveryPage(BFAssetStorageReaderImpl source, List<BFAssetHistory> ascending, int count) {
        for (Order direction : Order.values()) {
            List<BFAssetHistory> expected = new ArrayList<>(ascending);
            if (direction == Order.desc) Collections.reverse(expected);
            List<BFAssetHistory> actual = new ArrayList<>();
            int pages = (expected.size() + count - 1) / count;
            for (int page = 0; page < pages; page++) {
                List<BFAssetHistory> rows = source.findAssetHistory(UNIT, page, count, direction);
                assertThat(rows).containsExactlyElementsOf(expected.subList(page * count,
                        Math.min((page + 1) * count, expected.size())));
                actual.addAll(rows);
            }
            assertThat(actual).containsExactlyElementsOf(expected);
            assertThat(actual).extracting(BFAssetHistory::txHash).doesNotHaveDuplicates();
            assertThat(source.findAssetHistory(UNIT, pages, count, direction)).isEmpty();
        }
    }
}
