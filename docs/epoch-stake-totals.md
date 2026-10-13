# Epoch stake totals rollout

The Blockfrost epoch list previously summed every address in every requested
snapshot. A mainnet page after epoch 633 selected 28 snapshots (about 37.65M
rows); both the deployed query and a per-partition UNION exceeded a bounded
seven-second probe. Individual snapshots 632 and 659 took 279ms and 281ms.

`epoch_stake_total` stores the exact `SUM(epoch_stake.amount)` and row count for
each snapshot epoch. Snapshot epoch N is active epoch N+2: the normal writer
sets `activeEpoch = epoch + 2`; the custom genesis writer stores `epoch =
genesisEpoch - 1` and `activeEpoch = genesisEpoch + 1`.

All application writers lock singleton `epoch_stake_total_state` before
changing snapshot rows. Snapshot replacement and genesis writes refresh the
total in the same transaction. Pruning advances a persistent floor and removes
totals before each partial-delete batch; backfill cannot publish partial pruned
snapshots. Rollback clears only snapshots created by jobs after its rollback
slot, preserving earlier totals. Empty markers block orphan backfill until
canonical replay replaces them. CLI rollback locks the same state row and
deletes totals with the same `epoch >= rollbackEpoch - 1` bound as stake rows.

The bounded background backfill computes one snapshot per transaction and
never runs in an API request. Empty snapshots have `total = NULL, row_count =
0`, preserving the existing absent active-stake result. Default settings:

```properties
store.adapot.epoch-stake-total-backfill-enabled=true
store.adapot.epoch-stake-total-backfill-timeout-seconds=3
store.adapot.epoch-stake-total-backfill-interval-ms=1000
store.adapot.epoch-stake-totals-enabled=false
```

Deploy schema/writers with the totals read flag off. The scheduler starts after
30 seconds and logs each published snapshot, then `Epoch stake total backfill
ready: no missing snapshots`. Its per-transaction limit also bounds PostgreSQL
statement work; a failed or locked snapshot is retried in a later transaction.
For the production rollout, verify readiness independently before setting
`store.adapot.epoch-stake-totals-enabled=true` in the persistent manifest:

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '3s';
SELECT e.number - 2 AS missing_snapshot
FROM epoch e CROSS JOIN epoch_stake_total_state s
WHERE s.id = 1 AND e.number - 2 >= s.prune_before_epoch
  AND NOT EXISTS (SELECT 1 FROM epoch_stake_total t WHERE t.epoch = e.number - 2)
ORDER BY e.number;
COMMIT;
```

Then validate exact totals against one snapshot at a time under a bounded
transaction, retaining the resulting epoch/total/row-count evidence. Verify
the compiled reader's SQL and live `/epochs/633/next` pages. Once the flag is
on, the list reader fetches only the small total table; it never falls back to
a page-wide stake sum. Missing and pruned snapshots remain absent, zero sums
remain zero, and totals are not cached in application memory.

## Raw maintenance

Raw SQL bypasses the application protocol. Any maintenance that changes
`epoch_stake` must take `SELECT id FROM epoch_stake_total_state WHERE id=1 FOR
UPDATE` first in the same transaction, then delete affected totals. Full
snapshot replacements should refresh each affected snapshot with:

```sql
DELETE FROM epoch_stake_total WHERE epoch = :snapshot_epoch;
INSERT INTO epoch_stake_total (epoch, total, row_count)
SELECT :snapshot_epoch, SUM(amount), COUNT(*) FROM epoch_stake
WHERE epoch = :snapshot_epoch;
```

Partial pruning must advance `prune_before_epoch` before the first batch and
delete total rows below the floor on every batch. Do not republish totals for
partially pruned snapshots. Keep the read flag off during a raw maintenance
operation that cannot follow this protocol. Database migrations create only
small empty tables; they never scan historical snapshots at application boot.
