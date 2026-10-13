package com.bloxbean.cardano.yaci.store.adapot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class EpochStakeTotalConcurrencyTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private EpochStakeTotalService totals;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("drop table if exists epoch_stake, epoch_stake_total, epoch_stake_total_state");
        jdbc.execute("create table epoch_stake (epoch int, address text, amount numeric(38))");
        jdbc.execute("create table epoch_stake_total (epoch int primary key, total numeric(38), row_count bigint)");
        jdbc.execute("create table epoch_stake_total_state (id int primary key, prune_before_epoch int)");
        jdbc.execute("insert into epoch_stake_total_state values (1, -2147483648)");
        jdbc.execute("insert into epoch_stake values (632, 'a', 10), (632, 'b', 20)");
        totals = new EpochStakeTotalService(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void replacementWaitsForBackfillAndThenPublishesItsOwnSnapshot() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var replacing = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var backfill = executor.submit(() -> transaction.executeWithoutResult(status -> {
                totals.lock();
                locked.countDown();
                await(release);
                totals.refresh(632);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var replacement = executor.submit(() -> transaction.executeWithoutResult(status -> {
                replacing.countDown();
                totals.lock();
                jdbc.execute("delete from epoch_stake where epoch = 632");
                jdbc.execute("insert into epoch_stake values (632, 'new', 42)");
                totals.refresh(632);
            }));
            assertThat(replacing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(replacement.isDone()).isFalse();
            release.countDown();
            backfill.get(5, TimeUnit.SECONDS);
            replacement.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select total from epoch_stake_total where epoch = 632", BigDecimal.class))
                .isEqualByComparingTo("42");
    }

    @Test
    void pruningWaitsForBackfillAndRemovesItsTotalBeforePartialRowsCommit() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var backfill = executor.submit(() -> transaction.executeWithoutResult(status -> {
                totals.lock();
                locked.countDown();
                await(release);
                totals.refresh(632);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var pruning = executor.submit(() -> transaction.executeWithoutResult(status -> {
                totals.lock();
                totals.beginPruning(633);
                jdbc.execute("delete from epoch_stake where epoch = 632 and address = 'a'");
            }));
            release.countDown();
            backfill.get(5, TimeUnit.SECONDS);
            pruning.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from epoch_stake_total where epoch = 632", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from epoch_stake where epoch = 632", Long.class)).isOne();
        transaction.executeWithoutResult(status -> {
            totals.lock();
            totals.refresh(632);
        });
        assertThat(jdbc.queryForObject("select count(*) from epoch_stake_total where epoch = 632", Long.class)).isZero();
    }

    @Test
    void aBlockedBackgroundBackfillIsCanceledAndRetriedAfterTheWriterReleasesItsLock() throws Exception {
        jdbc.execute("create table if not exists epoch (number bigint primary key)");
        jdbc.execute("truncate epoch");
        jdbc.execute("insert into epoch values (634)");
        // Spring binds the connection and SET LOCAL limits by DataSource identity.
        // Use the same bean as totals, matching the application's injected wiring.
        var backfill = new EpochStakeTotalBackfill(totals, new NamedParameterJdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES), 3);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> transaction.executeWithoutResult(status -> {
                totals.lock();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var attempt = executor.submit(backfill::backfillNext);
            try {
                attempt.get(4, TimeUnit.SECONDS); // PG lock_timeout is 1s.
                assertThat(jdbc.queryForObject("select count(*) from epoch_stake_total", Long.class)).isZero();
            } finally {
                release.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
        }
        backfill.backfillNext();
        assertThat(jdbc.queryForObject("select total from epoch_stake_total where epoch = 632", BigDecimal.class))
                .isEqualByComparingTo("30");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test transaction did not release");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
