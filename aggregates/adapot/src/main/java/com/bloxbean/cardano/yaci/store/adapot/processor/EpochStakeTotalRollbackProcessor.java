package com.bloxbean.cardano.yaci.store.adapot.processor;

import com.bloxbean.cardano.yaci.store.adapot.service.EpochStakeTotalService;
import com.bloxbean.cardano.yaci.store.core.annotation.ReadOnly;
import com.bloxbean.cardano.yaci.store.events.RollbackEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
@ReadOnly(false)
public class EpochStakeTotalRollbackProcessor {
    private final EpochStakeTotalService totals;
    private final NamedParameterJdbcTemplate jdbc;

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE + 1) // after job cancellation, before reward/job row deletion
    @Transactional
    public void handleRollback(RollbackEvent event) {
        totals.lock();
        // Job E creates snapshot E-1 at the epoch transition. Preserve all
        // snapshots whose source transition is at or before the rollback slot.
        var snapshots = jdbc.query("""
                SELECT epoch - 1 AS snapshot_epoch FROM adapot_jobs
                WHERE slot > :slot AND type = 'REWARD_CALC' ORDER BY epoch
                """, Map.of("slot", event.getRollbackTo().getSlot()), (rs, row) -> rs.getInt("snapshot_epoch"));
        snapshots.forEach(totals::clearRolledBackSnapshot);
        // Remove source jobs before releasing the mutex. Otherwise a snapshot
        // waiting on this lock could see a not-yet-deleted orphan job and
        // republish it before RewardProcessor's later job deletion.
        jdbc.update("DELETE FROM adapot_jobs WHERE slot > :slot AND type = 'REWARD_CALC'",
                Map.of("slot", event.getRollbackTo().getSlot()));
        // Keep empty markers until replay replaces them, rather than backfilling
        // orphaned stake rows before their new canonical snapshot is ready.
    }
}
