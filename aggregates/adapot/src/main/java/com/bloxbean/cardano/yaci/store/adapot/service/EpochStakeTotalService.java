package com.bloxbean.cardano.yaci.store.adapot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Publishes exact totals with their stake snapshots. Every snapshot writer,
 * backfill and pruner locks the state row before touching epoch_stake, so a
 * backfill cannot publish rows concurrently replaced or partially pruned.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class EpochStakeTotalService {
    private final NamedParameterJdbcTemplate jdbc;

    public void lock() {
        jdbc.queryForObject("SELECT id FROM epoch_stake_total_state WHERE id = 1 FOR UPDATE", Map.of(), Integer.class);
    }

    public void refresh(int snapshotEpoch) {
        jdbc.update("DELETE FROM epoch_stake_total WHERE epoch = :epoch", Map.of("epoch", snapshotEpoch));
        Integer floor = jdbc.queryForObject("SELECT prune_before_epoch FROM epoch_stake_total_state WHERE id = 1",
                Map.of(), Integer.class);
        if (snapshotEpoch < floor) return;
        insertTotal(snapshotEpoch);
    }

    public void refreshCompletedSnapshot(int snapshotEpoch) {
        // A full canonical replacement can legitimately recreate an epoch
        // below a former pruning floor after a deep rollback/replay.
        jdbc.update("DELETE FROM epoch_stake_total WHERE epoch = :epoch", Map.of("epoch", snapshotEpoch));
        insertTotal(snapshotEpoch);
    }

    private void insertTotal(int snapshotEpoch) {
        // SUM of an empty snapshot stays NULL, matching the existing stake API.
        // A row_count=0 marker prevents the backfill retrying Byron/absent epochs.
        jdbc.update("""
                INSERT INTO epoch_stake_total (epoch, total, row_count)
                SELECT :epoch, SUM(amount), COUNT(*) FROM epoch_stake WHERE epoch = :epoch
                """, Map.of("epoch", snapshotEpoch));
    }

    public void clearRolledBackSnapshot(int snapshotEpoch) {
        jdbc.update("DELETE FROM epoch_stake WHERE epoch = :epoch", Map.of("epoch", snapshotEpoch));
        refreshCompletedSnapshot(snapshotEpoch);
    }

    public void beginPruning(int pruneBeforeEpoch) {
        jdbc.update("""
                UPDATE epoch_stake_total_state
                SET prune_before_epoch = CASE WHEN prune_before_epoch < :epoch THEN :epoch ELSE prune_before_epoch END
                WHERE id = 1
                """, Map.of("epoch", pruneBeforeEpoch));
        // Repeat in each batch, so a replacement between batches cannot leave
        // a published total while the following batch removes its rows.
        jdbc.update("DELETE FROM epoch_stake_total WHERE epoch < :epoch", Map.of("epoch", pruneBeforeEpoch));
    }

    public Optional<Integer> findNextMissingSnapshot() {
        // Snapshot epoch N is active at N+2 (StakeSnapshotService). The epoch
        // table is small; never discover missing snapshots by scanning stakes.
        return jdbc.query("""
                SELECT e.number - 2 AS snapshot_epoch
                FROM epoch e
                CROSS JOIN epoch_stake_total_state s
                WHERE s.id = 1 AND e.number - 2 >= s.prune_before_epoch
                  AND NOT EXISTS (SELECT 1 FROM epoch_stake_total t WHERE t.epoch = e.number - 2)
                ORDER BY e.number
                LIMIT 1
                """, Map.of(), (rs, row) -> rs.getInt("snapshot_epoch")).stream().findFirst();
    }
}
