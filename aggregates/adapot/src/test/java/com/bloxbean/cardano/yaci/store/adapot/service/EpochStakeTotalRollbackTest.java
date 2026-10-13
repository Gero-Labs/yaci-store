package com.bloxbean.cardano.yaci.store.adapot.service;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.store.adapot.processor.EpochStakeTotalRollbackProcessor;
import com.bloxbean.cardano.yaci.store.events.RollbackEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EpochStakeTotalRollbackTest {
    @Test
    void rollbackPreservesEarlierTotalsAndStopsBackfillOfOrphanedSnapshots() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table epoch_stake (epoch int, address varchar, amount numeric(38))");
        jdbc.execute("create table epoch_stake_total (epoch int primary key, total numeric(38), row_count bigint)");
        jdbc.execute("create table epoch_stake_total_state (id int primary key, prune_before_epoch int)");
        jdbc.execute("insert into epoch_stake_total_state values (1, -2147483648)");
        jdbc.execute("create table adapot_jobs (epoch int, slot bigint, type varchar)");
        jdbc.execute("insert into adapot_jobs values (633, 100, 'REWARD_CALC'), (634, 200, 'REWARD_CALC')");
        jdbc.execute("insert into epoch_stake values (632, 'old', 10), (633, 'orphan', 20)");
        var named = new NamedParameterJdbcTemplate(dataSource);
        var totals = new EpochStakeTotalService(named);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            totals.lock();
            totals.refresh(632);
            totals.refresh(633);
        });
        transaction.executeWithoutResult(status -> new EpochStakeTotalRollbackProcessor(totals, named)
                .handleRollback(RollbackEvent.builder().rollbackTo(new Point(150, "canonical")).build()));
        assertThat(jdbc.queryForObject("select total from epoch_stake_total where epoch = 632", BigDecimal.class))
                .isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select row_count from epoch_stake_total where epoch = 633", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from epoch_stake where epoch = 633", Long.class)).isZero();
        assertThat(jdbc.queryForList("select epoch from adapot_jobs", Integer.class)).containsExactly(633);
        transaction.executeWithoutResult(status -> {
            totals.lock();
            jdbc.execute("insert into epoch_stake values (633, 'canonical', 42)");
            totals.refresh(633);
        });
        assertThat(jdbc.queryForObject("select total from epoch_stake_total where epoch = 633", BigDecimal.class))
                .isEqualByComparingTo("42");
    }
}
