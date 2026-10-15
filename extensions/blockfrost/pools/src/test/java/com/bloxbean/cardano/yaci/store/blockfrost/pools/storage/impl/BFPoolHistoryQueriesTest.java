package com.bloxbean.cardano.yaci.store.blockfrost.pools.storage.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.conf.StatementType;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.bloxbean.cardano.yaci.store.blockfrost.pools.storage.BFPoolsStorageReader;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual PostgreSQL SQL, including LATERAL discovery and partition bounds. */
@Testcontainers
class BFPoolHistoryQueriesTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private final List<String> statements = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = connection()) {
            DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
            dsl.execute("drop schema public cascade");
            dsl.execute("create schema public");
            dsl.execute("create table epoch (number bigint primary key)");
            dsl.execute("insert into epoch select generate_series(0,12)");
            dsl.execute("create table epoch_stake (epoch int, address text, pool_id text, amount numeric(38), primary key(epoch,address)) partition by range(epoch)");
            for (int epoch = 0; epoch <= 12; epoch++) {
                dsl.execute("create table epoch_stake_p" + epoch + " partition of epoch_stake for values from (" + epoch + ") to (" + (epoch + 1) + ")");
            }
            dsl.execute("create index on epoch_stake(epoch,pool_id)");
            dsl.execute("create table epoch_stake_total(epoch int primary key,total numeric(38),row_count bigint)");
            dsl.execute("create table block(epoch int,slot_leader text)");
            dsl.execute("create table reward(earned_epoch int,pool_id text,type text,amount numeric(38))");
            dsl.execute("create table pool(pool_id text,active_epoch int,status text,slot bigint,tx_hash text,tx_index int,cert_index int)");
            dsl.execute("create table pool_registration(pool_id text,tx_hash text,tx_index int,cert_index int,cost numeric(38),margin_numerator numeric(38),margin_denominator numeric(38))");
            // Snapshot N is active at N+2. Gaps are real; unrelated pools must not fill them.
            dsl.execute("insert into epoch_stake values (3,'a','p',40),(3,'b','p',60),(3,'other','q',100),"
                    + "(5,'a','p',80),(5,'b','q',320),(6,'a','q',777),(7,'a','p',0),(7,'b','q',0),(9,'a','p',1),(10,'a','p',1)");
            dsl.execute("insert into epoch_stake_total values (3,200,3),(5,400,2),(7,0,2),(9,1,1),(10,1,1)");
            dsl.execute("insert into block values (5,'p'),(5,'p'),(7,'p'),(12,'q')");
            dsl.execute("insert into reward values (5,'p','leader',50),(5,'p','member',250),(5,'p','refund',9999),(7,'p','member',30)");
            // One fee parameter change becomes effective between the two sampled epochs.
            dsl.execute("insert into pool values ('p',1,'REGISTRATION',1,'first',0,0),('p',6,'UPDATE',2,'second',0,0)");
            dsl.execute("insert into pool_registration values ('p','first',0,0,40,1,3),('p','second',0,0,100,0,1)");
        }
    }

    @Test
    void discoversOnePageThenBoundsEveryAggregateToItsLiteralEpochs() throws Exception {
        try (Connection connection = connection()) {
            var reader = reader(connection, false);
            var page = reader.getPoolHistoryFull("p", 0, 2, "asc");
            assertThat(page).extracting(row -> row.getEpoch()).containsExactly(5, 7);
            assertThat(page).extracting(row -> row.getActiveStake()).containsExactly("100", "80");
            assertThat(page).extracting(row -> row.getActiveSize()).containsExactly(0.5, 0.2);
            assertThat(page).extracting(row -> row.getDelegatorsCount()).containsExactly(2, 1);
            assertThat(page).extracting(row -> row.getBlocks()).containsExactly(2, 1);
            assertThat(page).extracting(row -> row.getRewards()).containsExactly("300", "30");
            assertThat(page).extracting(row -> row.getFees()).containsExactly("126", "30");
            assertThat(statements).anyMatch(sql -> sql.toLowerCase().contains("lateral"));
            assertThat(statements).filteredOn(sql -> sql.contains("sum(\"epoch_stake\".\"amount\")"))
                    .allMatch(sql -> sql.contains("in (3, 5)"));
            assertThat(statements).noneMatch(sql -> sql.contains("in (select \"page_epochs\""));
        }
    }

    @Test
    void publishedTotalsAvoidWholeStakeSumsAndMissingTotalsFallBackOnlyForTheMissingEpoch() throws Exception {
        try (Connection connection = connection()) {
            var reader = reader(connection, true);
            assertThat(reader.getPoolHistoryFull("p", 0, 2, "asc"))
                    .extracting(row -> row.getActiveSize()).containsExactly(0.5, 0.2);
            assertThat(statements).anyMatch(sql -> sql.contains("epoch_stake_total"));
            assertThat(statements).noneMatch(sql -> sql.contains("total_stake_sub"));
            DSL.using(connection).execute("delete from epoch_stake_total where epoch=5");
            statements.clear();
            assertThat(reader.getPoolHistoryFull("p", 0, 2, "asc"))
                    .extracting(row -> row.getActiveSize()).containsExactly(0.5, 0.2);
            assertThat(statements).filteredOn(sql -> sql.contains("total_stake_sub"))
                    .allMatch(sql -> sql.contains("in (5)") && !sql.contains("in (3, 5)"));
        }
    }

    @Test
    void paginationExcludesZeroOnlySnapshotsAndTheOpenEpoch() throws Exception {
        try (Connection connection = connection()) {
            var reader = reader(connection, true);
            assertThat(reader.getPoolHistoryFull("p", 0, 2, "desc"))
                    .extracting(row -> row.getEpoch()).containsExactly(11, 7);
            var next = reader.getPoolHistoryFull("p", 1, 2, "asc");
            assertThat(next).extracting(row -> row.getEpoch()).containsExactly(11);
            assertThat(next.getFirst().getActiveStake()).isEqualTo("1");
            assertThat(next.getFirst().getActiveSize()).isEqualTo(1.0);
            assertThat(reader.getPoolHistoryFull("p", 5, 2, "asc")).isEmpty();
        }
    }

    @Test
    void zeroAmountDelegationsAreAbsentFromBlockfrostMembershipAndCounts() throws Exception {
        try (Connection connection = connection()) {
            // Observed mainnet epoch 211: the 71 positive rows agree with Blockfrost;
            // Yaci adds two zero rows because its snapshot starts from delegations.
            DSL.using(connection).execute("insert into epoch_stake values (3,'zero-1','p',0),(3,'zero-2','p',0)");
            var page = reader(connection, true).getPoolHistoryFull("p", 0, 100, "asc");
            assertThat(page).extracting(row -> row.getEpoch()).containsExactly(5, 7, 11);
            assertThat(page.getFirst().getDelegatorsCount()).isEqualTo(2);
            assertThat(page.getFirst().getActiveStake()).isEqualTo("100");
            assertThat(page.getFirst().getActiveSize()).isEqualTo(0.5);
        }
    }

    @Test
    void springReadTransactionKeepsPageAndAmountsOnOneSnapshotDuringAReplacement() throws Exception {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES,
                new Settings().withStatementType(StatementType.STATIC_STATEMENT));
        AtomicBoolean replaced = new AtomicBoolean();
        dsl.configuration().set(new DefaultExecuteListener() {
            @Override
            public void executeEnd(ExecuteContext context) {
                if (context.sql().contains("lateral") && replaced.compareAndSet(false, true)) {
                    // Independent committed writer between discovery and aggregate reads.
                    // The repeatable-read response must remain internally consistent.
                    try (Connection writer = connection()) {
                        assertThat(context.connection().isReadOnly()).isTrue();
                        assertThat(context.connection().getTransactionIsolation())
                                .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                        DSL.using(writer).execute("update epoch_stake set amount=400 where epoch=3 and address='a'");
                        DSL.using(writer).execute("update epoch_stake_total set total=560 where epoch=3");
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
            }
        });
        var target = new BFPoolsStorageReaderImpl(dsl, new ObjectMapper());
        ReflectionTestUtils.setField(target, "epochStakeTotalsEnabled", true);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        BFPoolsStorageReader reader = (BFPoolsStorageReader) proxy.getProxy();
        assertThat(reader.getPoolHistoryFull("p", 0, 1, "asc").getFirst().getActiveStake()).isEqualTo("100");
        assertThat(replaced).isTrue();
        assertThat(reader.getPoolHistoryFull("p", 0, 1, "asc").getFirst().getActiveStake()).isEqualTo("460");
    }

    private BFPoolsStorageReaderImpl reader(Connection connection, boolean totalsEnabled) {
        DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES,
                new Settings().withStatementType(StatementType.STATIC_STATEMENT));
        dsl.configuration().set(new DefaultExecuteListener() {
            @Override
            public void executeStart(ExecuteContext context) {
                statements.add(context.sql());
            }
        });
        statements.clear();
        var reader = new BFPoolsStorageReaderImpl(dsl, new ObjectMapper());
        if (totalsEnabled) {
            ReflectionTestUtils.setField(reader, "epochStakeTotalsEnabled", true);
        }
        return reader;
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
