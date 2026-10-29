package com.bloxbean.cardano.yaci.store.blockfrost.epoch.service;

import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.yaci.store.adapot.domain.EpochStake;
import com.bloxbean.cardano.yaci.store.adapot.storage.impl.PostgresPartitionManager;
import com.bloxbean.cardano.yaci.store.blockfrost.epoch.storage.impl.BFEpochStorageReaderImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamType;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL checks; use a disposable loopback server, never an indexer database. */
@EnabledIfEnvironmentVariable(named = "YACI_TEST_POSTGRES_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-zA-Z0-9_]+")
class PostgresEpochStakeCredentialIndexTest {
    private Connection connection;
    private DSLContext dsl;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(System.getenv("YACI_TEST_POSTGRES_URL"), "postgres", "");
        dsl = DSL.using(connection, SQLDialect.POSTGRES, new Settings().withRenderSchema(false));
        schema = "bf_epoch_index_" + UUID.randomUUID().toString().replace("-", "");
        dsl.execute("create schema " + schema);
        dsl.execute("set search_path to " + schema);
        dsl.execute("create table epoch_stake (epoch integer, address varchar(255), amount numeric(38), "
                + "pool_id varchar(56), delegation_epoch integer, active_epoch integer, create_datetime timestamp, "
                + "primary key (epoch, address)) partition by range (epoch)");
        dsl.execute("create table epoch_stake_default partition of epoch_stake default");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            dsl.execute("drop schema " + schema + " cascade");
            connection.close();
        }
    }

    @Test
    void servesTheRealLedgerOrderedPageWithAnIndexScanWithoutSortingThePartition() throws Exception {
        new PostgresPartitionManager(dsl).ensureEpochStakePartition(631);
        String scriptLow = reward(true, "00");
        String scriptHigh = reward(true, "20");
        String keyLow = reward(false, "00");
        String keyHigh = reward(false, "20");
        insert(scriptHigh, 7);
        insert(keyHigh, 11);
        insert(scriptLow, 2);
        insert(keyLow, 3);
        insert(reward(true, "01"), 0);
        insert(reward(false, "01"), -1);
        // Reverse insertion order and enough keys to make the planner prefer the ordered
        // index naturally. No enable_seqscan/enable_sort override hides a missing index.
        try (var statement = connection.prepareStatement("insert into epoch_stake "
                + "(epoch,address,amount,pool_id,delegation_epoch,active_epoch) values (631,?,7,?,631,633)")) {
            for (int i = 20_000; i >= 1; i--) {
                byte[] payload = new byte[29];
                payload[0] = (byte) 0xe1;
                payload[1] = (byte) 0x80;
                payload[25] = (byte) (i >>> 24);
                payload[26] = (byte) (i >>> 16);
                payload[27] = (byte) (i >>> 8);
                payload[28] = (byte) i;
                statement.setString(1, Bech32.encode(payload, "stake"));
                statement.setString(2, "0f".repeat(28));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        dsl.execute("analyze epoch_stake_p631");
        AtomicReference<String> renderedQuery = new AtomicReference<>();
        dsl.configuration().set(new DefaultExecuteListenerProvider(new DefaultExecuteListener() {
            @Override
            public void executeStart(ExecuteContext context) {
                if (context.query() != null && context.sql().stripLeading().startsWith("select")) {
                    renderedQuery.set(context.query().getSQL(ParamType.INLINED));
                }
            }
        }));
        List<EpochStake> page = new BFEpochStorageReaderImpl(dsl).findEpochStakes(633, 0, 100);
        assertThat(page).hasSize(100).extracting(EpochStake::getAddress)
                .startsWith(scriptLow, scriptHigh, keyLow, keyHigh);
        String query = renderedQuery.get();
        assertThat(query).isNotBlank();
        JsonNode plan = new ObjectMapper().readTree(dsl.fetchOne("explain (format json) " + query)
                .get(0, String.class)).get(0).get("Plan");
        assertThat(plan.get("Node Type").textValue()).isEqualTo("Limit");
        JsonNode scan = plan.get("Plans").get(0);
        assertThat(scan.get("Node Type").textValue()).isEqualTo("Index Scan");
        assertThat(expressionIndexes()).containsExactly(scan.get("Index Name").textValue());
        assertThat(scan.get("Relation Name").textValue()).isEqualTo("epoch_stake_p631");
    }

    @Test
    void buildsTheMigrationPartitionIndexWhileEmptyBeforeMovingDefaultRows() throws Exception {
        insert(reward(false, "20"), 11);
        insert(reward(true, "00"), 3);
        List<Long> rowsAtIndexBuild = new ArrayList<>();
        dsl.configuration().set(new DefaultExecuteListenerProvider(new DefaultExecuteListener() {
            @Override
            public void executeStart(ExecuteContext context) {
                if (context.sql().stripLeading().toLowerCase(Locale.ROOT).startsWith("create index")) {
                    try (var statement = connection.createStatement();
                         var rows = statement.executeQuery("select count(*) from " + schema + ".epoch_stake_p631")) {
                        rows.next();
                        rowsAtIndexBuild.add(rows.getLong(1));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
            }
        }));
        connection.setAutoCommit(false);
        new PostgresPartitionManager(dsl).ensureEpochStakePartition(631);
        assertThat(rowsAtIndexBuild).containsExactly(0L);
        assertThat(expressionIndexes()).hasSize(1);
        assertThat(dsl.fetchCount(DSL.table("epoch_stake_p631"))).isEqualTo(2);
        assertThat(dsl.fetchCount(DSL.table("epoch_stake_default"))).isZero();
        assertThat(new BFEpochStorageReaderImpl(dsl).findEpochStakes(633, 0, 100))
                .extracting(EpochStake::getAddress)
                .containsExactly(reward(true, "00"), reward(false, "20"));
        connection.commit();
        connection.setAutoCommit(true);
    }

    @Test
    void leavesAnExistingPopulatedPartitionUntouchedEvenWhenAnIndexBuildWouldBlock() throws Exception {
        dsl.execute("create table epoch_stake_p631 partition of epoch_stake for values from (631) to (632)");
        insert(reward(false, "20"), 11);
        dsl.execute("set lock_timeout = '250ms'");
        try (Connection writer = DriverManager.getConnection(System.getenv("YACI_TEST_POSTGRES_URL"), "postgres", "")) {
            writer.setAutoCommit(false);
            try (var statement = writer.createStatement()) {
                statement.execute("lock table " + schema + ".epoch_stake_p631 in row exclusive mode");
            }
            new PostgresPartitionManager(dsl).ensureEpochStakePartition(631);
            assertThat(expressionIndexes()).isEmpty();
            assertThat(dsl.fetchCount(DSL.table("epoch_stake_p631"))).isEqualTo(1);
            writer.rollback();
        }
    }

    private List<String> expressionIndexes() {
        return dsl.fetch("select c.relname from pg_index i join pg_class c on c.oid=i.indexrelid "
                        + "where i.indrelid=?::regclass and i.indexprs is not null order by c.relname",
                schema + ".epoch_stake_p631").getValues(0, String.class);
    }

    private void insert(String address, long amount) {
        dsl.execute("insert into epoch_stake (epoch,address,amount,pool_id,delegation_epoch,active_epoch) "
                + "values (631,?,?,?,631,633)", address, amount, "0f".repeat(28));
    }

    private static String reward(boolean script, String hashByte) {
        return Bech32.encode(HexFormat.of().parseHex((script ? "f1" : "e1") + hashByte.repeat(28)), "stake");
    }
}
