package com.bloxbean.cardano.yaci.store.adapot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochStakeTotalServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private EpochStakeTotalService totals;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void createDatabase() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("create table epoch_stake (epoch int, address varchar, amount numeric(38))");
        jdbc.execute("create table epoch (number bigint primary key)");
        try (var resource = getClass().getResourceAsStream("/db/store/h2/V0_1300_6__epoch_stake_totals.sql")) {
            assertThat(resource).isNotNull();
            for (String sql : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) jdbc.execute(sql);
            }
        }
        totals = new EpochStakeTotalService(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void publishesAndReplacesExactTotalsInTheSnapshotTransaction() {
        transaction.executeWithoutResult(status -> {
            totals.lock();
            jdbc.execute("insert into epoch_stake values (632, 'a', 9223372036854775808), (632, 'b', 9223372036854775808)");
            totals.refresh(632);
        });
        assertThat(total(632)).isEqualTo(new BigInteger("18446744073709551616"));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            totals.lock();
            jdbc.execute("delete from epoch_stake where epoch = 632");
            totals.refresh(632);
            throw new IllegalStateException("snapshot failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(total(632)).isEqualTo(new BigInteger("18446744073709551616"));
        transaction.executeWithoutResult(status -> {
            totals.lock();
            jdbc.execute("delete from epoch_stake where epoch = 632");
            jdbc.execute("insert into epoch_stake values (632, 'a', 42)");
            totals.refresh(632);
        });
        assertThat(total(632)).isEqualTo(BigInteger.valueOf(42));
    }

    @Test
    void pruningInvalidatesBeforeItsFirstPartialDeleteAndPreventsPartialBackfill() {
        jdbc.execute("insert into epoch values (634), (635)");
        transaction.executeWithoutResult(status -> {
            totals.lock();
            jdbc.execute("insert into epoch_stake values (632, 'a', 10), (632, 'b', 20), (633, 'c', 0)");
            totals.refresh(632);
        });
        transaction.executeWithoutResult(status -> {
            totals.lock();
            totals.beginPruning(633);
            jdbc.execute("delete from epoch_stake where epoch = 632 and address = 'a'");
        });
        assertThat(jdbc.queryForList("select epoch from epoch_stake_total", Integer.class)).isEmpty();
        java.util.Optional<Integer> next = transaction.execute(status -> {
            totals.lock();
            return totals.findNextMissingSnapshot();
        });
        assertThat(next).contains(633);
        transaction.executeWithoutResult(status -> {
            totals.lock();
            totals.refresh(633);
        });
        assertThat(total(633)).isEqualTo(BigInteger.ZERO);
        java.util.Optional<Integer> remaining = transaction.execute(status -> {
            totals.lock();
            return totals.findNextMissingSnapshot();
        });
        assertThat(remaining).isEmpty();
    }

    @Test
    void recordsAnEmptySnapshotWithoutInventingZeroActiveStake() {
        transaction.executeWithoutResult(status -> {
            totals.lock();
            totals.refresh(632);
        });
        assertThat(total(632)).isNull();
        assertThat(jdbc.queryForObject("select row_count from epoch_stake_total where epoch = 632", Long.class)).isZero();
    }

    @Test
    void backgroundBackfillPublishesOnlyOneSnapshotPerCallAndDoesNotRepeatEmptyEpochs() {
        jdbc.execute("insert into epoch values (634), (635)");
        jdbc.execute("insert into epoch_stake values (632, 'a', 10)");
        var backfill = new EpochStakeTotalBackfill(totals, new NamedParameterJdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), org.jooq.impl.DSL.using(org.jooq.SQLDialect.H2), 3);
        backfill.backfillNext();
        assertThat(jdbc.queryForList("select epoch from epoch_stake_total order by epoch", Integer.class))
                .containsExactly(632);
        backfill.backfillNext();
        assertThat(jdbc.queryForList("select epoch from epoch_stake_total order by epoch", Integer.class))
                .containsExactly(632, 633);
        backfill.backfillNext();
        assertThat(jdbc.queryForObject("select count(*) from epoch_stake_total", Long.class)).isEqualTo(2);
        assertThat(total(633)).isNull();
    }

    @Test
    void canonicalFullReplacementCanRecreateAPreviouslyPrunedEpochAfterRollback() {
        transaction.executeWithoutResult(status -> {
            totals.lock();
            totals.beginPruning(633);
            jdbc.execute("insert into epoch_stake values (632, 'replayed', 42)");
            totals.refreshCompletedSnapshot(632);
        });
        assertThat(total(632)).isEqualTo(BigInteger.valueOf(42));
    }

    private BigInteger total(int epoch) {
        return jdbc.queryForObject("select total from epoch_stake_total where epoch = ?",
                (rs, row) -> rs.getBigDecimal(1) == null ? null : rs.getBigDecimal(1).toBigIntegerExact(), epoch);
    }
}
