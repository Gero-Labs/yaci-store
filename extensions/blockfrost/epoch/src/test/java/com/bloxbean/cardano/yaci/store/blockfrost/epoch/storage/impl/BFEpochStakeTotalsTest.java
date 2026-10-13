package com.bloxbean.cardano.yaci.store.blockfrost.epoch.storage.impl;

import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BFEpochStakeTotalsTest {
    @Test
    void readsThePublishedSnapshotTotalsWithoutScanningTheStakeRows() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DATABASE_TO_LOWER=TRUE")) {
            DSLContext dsl = DSL.using(connection, SQLDialect.H2);
            dsl.execute("create table epoch_stake (epoch int, address varchar, amount numeric(38))");
            dsl.execute("create table epoch_stake_total (epoch int primary key, total numeric(38), row_count bigint)");
            dsl.execute("insert into epoch_stake values (632, 'a', 999)");
            dsl.execute("insert into epoch_stake_total values "
                    + "(632, 18446744073709551616, 2), (633, 0, 1), (634, null, 0), (635, 15, 1)");
            List<String> statements = new ArrayList<>();
            dsl.configuration().set(new DefaultExecuteListener() {
                @Override
                public void executeStart(ExecuteContext context) {
                    statements.add(context.sql());
                }
            });
            var reader = new BFEpochStorageReaderImpl(dsl);
            // The rollout flag is intentionally off until the bounded backfill is ready.
            // This test opts into the completed-summary read path.
            ReflectionTestUtils.setField(reader, "epochStakeTotalsEnabled", true);
            assertThat(reader.getActiveStakesByEpochs(List.of(634, 635, 636, 634)))
                    .containsOnlyKeys(634, 635)
                    .containsEntry(634, new BigInteger("18446744073709551616"))
                    .containsEntry(635, BigInteger.ZERO);
            assertThat(statements).hasSize(1);
            assertThat(statements.getFirst()).contains("epoch_stake_total").doesNotContain("sum(");
            // Replacements/deletions are visible immediately; no process-local cache.
            dsl.execute("update epoch_stake_total set total = 42 where epoch = 632");
            assertThat(reader.getActiveStakesByEpochs(List.of(634))).containsEntry(634, BigInteger.valueOf(42));
            dsl.execute("delete from epoch_stake_total where epoch = 632");
            assertThat(reader.getActiveStakesByEpochs(List.of(634))).isEmpty();
        }
    }
}
