package com.bloxbean.cardano.yaci.store.adapot.service;

import com.bloxbean.cardano.yaci.store.adapot.snapshot.StakeSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochStakeSnapshotSourceTest {
    @Test
    void replacedSourceJobAtTheSameSlotCannotPublishAnOrphanSnapshot() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table adapot_jobs (epoch int, slot bigint, block_hash varchar, type varchar)");
        jdbc.execute("insert into adapot_jobs values (633, 100, 'canonical', 'REWARD_CALC')");
        var named = new NamedParameterJdbcTemplate(dataSource);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        // Exercise the production predicate with real SQL: slot alone would
        // wrongly accept the canonical replacement for this orphaned source.
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        new StakeSnapshotService(named, null, null, null),
                        "requireSourceJob", 632, 100L, "orphan")))
                .isInstanceOf(IllegalStateException.class);
        transaction.executeWithoutResult(status -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                new StakeSnapshotService(named, null, null, null), "requireSourceJob", 632, 100L, "canonical"));
    }
}
