package com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl;

import com.bloxbean.cardano.yaci.store.blockfrost.asset.controller.BFAssetController;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.service.BFAssetService;
import com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.BFAssetQueryDeadline;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.utxo.UtxoStoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.ConnectionProvider;
import org.jooq.ExecuteListener;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
class BFAssetUtxoQueryTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String UNIT = "aa".repeat(28);
    private static final String OTHER = "bb".repeat(28) + "01";
    private static final String PATH = "/api/v1/blockfrost/assets/" + UNIT + "/utxos";
    private Connection connection;
    private DSLContext dsl;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
        dsl.execute("drop table if exists transaction, assets, address_utxo, address_utxo_unspent, tx_input");
        dsl.execute("create table transaction(tx_hash text primary key,tx_index int,slot bigint)");
        dsl.execute("create table assets(unit text,policy text)");
        dsl.execute("insert into assets values (?, ?)", UNIT, UNIT);
        dsl.execute("create table address_utxo(tx_hash text,output_index smallint,slot bigint,tx_index int,"
                + "block_hash text,block bigint,block_time bigint,owner_addr text,owner_addr_full text,"
                + "lovelace_amount bigint,amounts jsonb,data_hash text,inline_datum text,reference_script_hash text)");
        dsl.execute("create table address_utxo_unspent(like address_utxo)");
        dsl.execute("create table tx_input(tx_hash text,output_index smallint)");
        String amounts = "[{\"unit\":\"" + OTHER + "\",\"quantity\":\"9223372036854775808\"},"
                + "{\"unit\":\"" + UNIT + "\",\"quantity\":\"4\"}]";
        dsl.execute("insert into transaction values ('z',0,200),('a',1,200),('m',2,200),('spent',0,200),('later',0,210)");
        for (String hash : new String[]{"z", "a", "m", "spent", "later"}) {
            long slot = hash.equals("later") ? 210 : 200;
            long height = hash.equals("later") ? 101 : 100;
            // Output tx_index is deliberately wrong/null; transaction is the chain-position source.
            dsl.execute("insert into address_utxo values (?,0,?,99,'block',?,1000,'short','full-address',"
                    + "3000000,?::jsonb,'wrong-stored-hash','9f00ff','script')", hash, slot, height, amounts);
        }
        dsl.execute("insert into tx_input values ('spent',0)");
        mvc = MockMvcBuilders.standaloneSetup(new BFAssetController(
                new BFAssetService(new BFAssetStorageReaderImpl(dsl))))
                .addPlaceholderValue("blockfrost.apiPrefix", "/api/v1/blockfrost").build();
    }

    @AfterEach
    void closeConnection() throws Exception {
        connection.close();
    }

    @Test
    void realRouteUsesTransactionPositionFullValueAndInclusiveHeightBeforePagination() throws Exception {
        JsonNode first = response(mvc, "?count=2");
        assertThat(first.findValuesAsText("tx_hash")).containsExactly("z", "a");
        assertThat(response(mvc, "?count=2&page=2").findValuesAsText("tx_hash")).containsExactly("m", "later");
        assertThat(response(mvc, "?count=2&order=desc").findValuesAsText("tx_hash")).containsExactly("later", "m");
        assertThat(response(mvc, "?count=1&page=2&from=100&to=100").findValuesAsText("tx_hash")).containsExactly("a");
        assertThat(response(mvc, "?from=101&to=101").findValuesAsText("tx_hash")).containsExactly("later");
        assertThat(response(mvc, "?page=999")).isEmpty();
        JsonNode row = first.get(0);
        assertThat(row.path("address").asText()).isEqualTo("full-address");
        assertThat(row.path("block").asText()).isEqualTo("block");
        assertThat(row.path("block_height").asLong()).isEqualTo(100);
        assertThat(row.path("block_time").asLong()).isEqualTo(1000);
        assertThat(row.path("amount").findValuesAsText("unit")).containsExactly("lovelace", UNIT, OTHER);
        assertThat(row.path("amount").findValuesAsText("quantity")).containsExactly("3000000", "4", "9223372036854775808");
        assertThat(row.path("inline_datum").asText()).isEqualTo("9f00ff");
        assertThat(row.path("inline_datum_json").toString()).isEqualTo("{\"list\":[{\"int\":0}]}");
        assertThat(row.path("data_hash").asText())
                .isEqualTo("2d4f2d2515100e24db31e726ea1222cc83060c0b36445582ac7c2e5d8be35805");
        assertThat(row.path("reference_script_hash").asText()).isEqualTo("script");
        assertThat(row.properties().stream().map(java.util.Map.Entry::getKey).toList()).containsExactlyInAnyOrderElementsOf(Set.of(
                "address", "tx_hash", "output_index", "amount", "block", "block_height", "block_time",
                "data_hash", "inline_datum", "inline_datum_json", "reference_script_hash"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"bad\"", "null", "1.5", "\"1.5\"", "\"\"", "true", "[]", "{}"})
    void malformedRequestedAssetQuantityInvalidatesMatchedOutputInsteadOfReturningPartialValue(String quantity) throws Exception {
        // Containment still matches the requested unit: the failure is in the real value projection.
        dsl.execute("update address_utxo set amounts=?::jsonb where tx_hash='z'",
                "[{\"unit\":\"" + UNIT + "\",\"quantity\":" + quantity + "},"
                        + "{\"unit\":\"" + OTHER + "\",\"quantity\":\"9\"}]");
        mvc.perform(get(PATH + "?count=1")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void missingRequestedAssetQuantityInvalidatesMatchedOutput() throws Exception {
        dsl.execute("update address_utxo set amounts=?::jsonb where tx_hash='z'",
                "[{\"unit\":\"" + UNIT + "\"}]");
        mvc.perform(get(PATH + "?count=1")).andExpect(status().isServiceUnavailable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "\"4\"", "9223372036854775808", "\"9223372036854775808\""})
    void validNumericAndStringIntegersPreserveFullValueAndAuthoritativeLovelaceOrdering(String quantity) throws Exception {
        dsl.execute("update address_utxo set amounts=?::jsonb where tx_hash='z'",
                "[{\"unit\":\"" + OTHER + "\",\"quantity\":\"9\"},"
                        + "{\"unit\":\"lovelace\",\"quantity\":\"1\"},"
                        + "{\"unit\":\"" + UNIT + "\",\"quantity\":" + quantity + "}]");
        JsonNode amount = response(mvc, "?count=1").get(0).path("amount");
        assertThat(amount.findValuesAsText("unit")).containsExactly("lovelace", UNIT, OTHER);
        assertThat(amount.findValuesAsText("quantity"))
                .containsExactly("3000000", quantity.replace("\"", ""), "9");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\" \"", "null", "42", "\"invalid\"", "\"aa\"",
            "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaag\"",
            "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0\""})
    void invalidAdditionalUnitInvalidatesWholeMatchedOutput(String unit) throws Exception {
        dsl.execute("update address_utxo set amounts=?::jsonb where tx_hash='z'",
                "[{\"unit\":\"" + UNIT + "\",\"quantity\":\"4\"},"
                        + "{\"unit\":" + unit + ",\"quantity\":\"9\"}]");
        mvc.perform(get(PATH + "?count=1")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void malformedAdditionalQuantityAndMissingUnitInvalidateWholeMatchedOutput() throws Exception {
        for (String additional : new String[]{"{\"unit\":\"" + OTHER + "\",\"quantity\":\"bad\"}",
                "{\"quantity\":\"9\"}", "null"}) {
            dsl.execute("update address_utxo set amounts=?::jsonb where tx_hash='z'",
                    "[{\"unit\":\"" + UNIT + "\",\"quantity\":\"4\"}," + additional + "]");
            mvc.perform(get(PATH + "?count=1")).andExpect(status().isServiceUnavailable());
        }
    }

    @Test
    void outputIndexResolvesSameTransactionAndNullableDatumFieldsArePresent() throws Exception {
        dsl.execute("insert into address_utxo select tx_hash,2,slot,tx_index,block_hash,block,block_time,"
                + "owner_addr,null,lovelace_amount,amounts,null,null,null from address_utxo where tx_hash='z'");
        dsl.execute("insert into address_utxo select tx_hash,1,slot,tx_index,block_hash,block,block_time,"
                + "owner_addr,owner_addr_full,lovelace_amount,amounts,null,null,null from address_utxo where tx_hash='z' and output_index=0");
        JsonNode rows = response(mvc, "?count=3");
        assertThat(rows.findValuesAsText("tx_hash")).containsExactly("z", "z", "z");
        assertThat(rows.findValuesAsText("output_index")).containsExactly("0", "1", "2");
        assertThat(response(mvc, "?order=desc&count=3&page=2").findValuesAsText("output_index"))
                .containsExactly("2", "1", "0");
        JsonNode nullable = rows.get(2);
        assertThat(nullable.path("address").asText()).isEqualTo("short");
        for (String field : new String[]{"data_hash", "inline_datum", "inline_datum_json", "reference_script_hash"}) {
            assertThat(nullable.has(field)).isTrue();
            assertThat(nullable.get(field).isNull()).isTrue();
        }
    }

    @Test
    void burnedAssetAndExcludedRangeAreEmptyButNeverMintedAssetIsNotFound() throws Exception {
        String burned = "cc".repeat(28);
        dsl.execute("insert into assets values (?, ?)", burned, burned);
        mvc.perform(get("/api/v1/blockfrost/assets/" + burned + "/utxos"))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
        assertThat(response(mvc, "?from=102")).isEmpty();
        mvc.perform(get("/api/v1/blockfrost/assets/" + "dd".repeat(28) + "/utxos"))
                .andExpect(status().isNotFound());
    }

    @Test
    void configuredCurrentTableWorksWithoutHistoricalOrSpendTables() {
        dsl.execute("insert into address_utxo_unspent select * from address_utxo where tx_hash in ('z','a','m','later')");
        dsl.execute("drop table address_utxo, tx_input");
        UtxoStoreProperties properties = UtxoStoreProperties.builder()
                .unspentTableEnabled(true).unspentTableReadEnabled(true).build();
        var factory = new StaticListableBeanFactory();
        factory.addBean("utxoStoreProperties", properties);
        var reader = new BFAssetStorageReaderImpl(dsl, factory.getBeanProvider(UtxoStoreProperties.class));
        assertThat(reader.findAssetUtxos(UNIT, 0, 100, Order.asc, null, null, new BFAssetQueryDeadline()))
                .extracting(com.bloxbean.cardano.yaci.store.blockfrost.asset.storage.impl.model.BFAssetUtxo::txHash)
                .containsExactly("z", "a", "m", "later");
    }

    @Test
    void missingCanonicalTransactionIndexInvalidatesNonemptyLaterPages() {
        dsl.execute("update transaction set tx_index=null where tx_hash='z'");
        var reader = new BFAssetStorageReaderImpl(dsl);
        for (Order order : Order.values()) {
            assertThatThrownBy(() -> reader.findAssetUtxos(UNIT, 1, 1, order, null, null, new BFAssetQueryDeadline()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }
    }

    @Test
    void mismatchedTransactionSlotDoesNotSilentlyDiscardAnOutput() {
        dsl.execute("update transaction set slot=201 where tx_hash='z'");
        assertThatThrownBy(() -> new BFAssetStorageReaderImpl(dsl)
                .findAssetUtxos(UNIT, 0, 100, Order.asc, null, null, new BFAssetQueryDeadline()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void postgresCancelsSlowPageWithinRemainingDeadlineAndConnectionStillWorks() {
        dsl.execute("alter table address_utxo rename to actual_address_utxo");
        dsl.execute("create function slow_asset_read() returns boolean language plpgsql volatile as $$"
                + "begin perform pg_sleep(10); return true; end $$");
        dsl.execute("create view address_utxo as select * from actual_address_utxo where slow_asset_read()");
        long started = System.nanoTime();
        assertThatThrownBy(() -> new BFAssetStorageReaderImpl(dsl)
                .findAssetUtxos(UNIT, 0, 100, Order.asc, null, null, new BFAssetQueryDeadline(Duration.ofSeconds(2))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(dsl.fetchOne("select 1").get(0, Integer.class)).isEqualTo(1);
        dsl.execute("drop view address_utxo");
        dsl.execute("alter table actual_address_utxo rename to address_utxo");
        dsl.execute("drop function slow_asset_read()");
    }

    @Test
    void insufficientRemainingTimeRefusesBothStatementsBeforeAccessingMissingTables() {
        dsl.execute("drop table address_utxo, assets");
        var reader = new BFAssetStorageReaderImpl(dsl);
        var deadline = new BFAssetQueryDeadline(Duration.ofMillis(1));
        assertThatThrownBy(() -> reader.findAssetUtxos(UNIT, 0, 100, Order.asc, null, null, deadline))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> reader.assetExists(UNIT, deadline))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void delayedConnectionAcquisitionCannotStartSqlWithAStaleTimeout() {
        var acquired = new AtomicInteger();
        var executed = new AtomicInteger();
        var provider = new ConnectionProvider() {
            @Override
            public Connection acquire() {
                acquired.incrementAndGet();
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return connection;
            }

            @Override
            public void release(Connection ignored) {
            }
        };
        var config = dsl.configuration().derive(provider)
                .deriveAppending(ExecuteListener.onExecuteEnd(context -> executed.incrementAndGet()));
        assertThatThrownBy(() -> new BFAssetStorageReaderImpl(DSL.using(config))
                .findAssetUtxos(UNIT, 0, 100, Order.asc, null, null,
                        new BFAssetQueryDeadline(Duration.ofMillis(1100))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(acquired.get()).isEqualTo(1);
        assertThat(executed.get()).isZero();
        assertThat(dsl.fetchOne("select 1").get(0, Integer.class)).isEqualTo(1);
    }

    @Test
    void boundedContextRetainsConfiguredExecuteListeners() {
        var executed = new AtomicInteger();
        var source = DSL.using(dsl.configuration()
                .deriveAppending(ExecuteListener.onExecuteEnd(context -> executed.incrementAndGet())));
        assertThat(new BFAssetStorageReaderImpl(source)
                .findAssetUtxos(UNIT, 0, 100, Order.asc, null, null, new BFAssetQueryDeadline())).hasSize(4);
        assertThat(executed.get()).isEqualTo(1);
    }

    private JsonNode response(MockMvc mvc, String query) throws Exception {
        String body = mvc.perform(get(PATH + query)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }
}
