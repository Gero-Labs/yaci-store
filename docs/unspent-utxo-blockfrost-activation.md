# Activate current UTxO reads for Blockfrost asset holders

The asset-holder query reads `address_utxo_unspent` only when both properties are
enabled. Both default false. The table retains every canonical output column and
is maintained at each committed batch, including genesis. Replaying a batch
refreshes every canonical output column from the persisted source, including
pointer stake credentials resolved on retry. The holder query keeps
its original earliest/latest current-UTxO order and sums all current outputs of
each selected holder. Native UTxO readers are unchanged.

Online and admin CLI source rollback restore current rows before deleting source
spends, in the same transaction. The CLI requires both source tables to delete
strictly after the rollback slot when the current table exists; a partial source
configuration or different boundary is rejected before mutation. Ledger-only
rollback does not touch current rows. PostgreSQL commit, rollback and backfill
use the same advisory transaction lock. Source SQL failure aborts the current-table
rollback, including cursor changes.

Initial activation starts with the newly migrated empty table. Existing content
from a period with maintenance disabled or a manual source repair must not be
treated as a fresh table: backfill inserts missing rows, and final reconciliation
only checks spends after its captured tip. It cannot repair every stale payload
or older spent row already present. Keep reads off, stop all writers, rebuild the
current table from an empty state through the separately reviewed maintenance
procedure, then restart maintenance before bounded backfill and the full readiness
checks below. An independently reviewed reconciliation covering every copied
range, every spend and full column parity is another valid prerequisite. Do not
reenable reads from a partial fill or a recent-tip-only check.

## Stage code and maintenance

1. Confirm the target database has not applied another `V0_200_3` migration. Use the
   normal deployment workflow to deploy the release with
   `store.utxo.unspent-table-enabled=true` and
   `store.utxo.unspent-table-read-enabled=false` in its durable configuration.
2. Verify the actual running image and these properties. Confirm the new migration
   created `address_utxo_unspent`, and commits are adding/removing rows while the
   indexer continues advancing. The migration alone does not populate history.
3. Apply the existing optional extra indexes to the new empty/current table before
   backfill. PostgreSQL requires
   `idx_address_utxo_unspent_amounts USING gin (amounts jsonb_path_ops)`.
   Verify `indisvalid`, `indisready` and the exact operator class. Do not run an
   unbounded online index build on the historical output table as part of this step.

## Backfill one network at a time

The admin CLI provides `backfill-unspent-utxos` with `--from-slot`, `--to-slot`,
`--chunk-slots`, `--statement-timeout-ms` and `--lock-timeout-ms`. Its default first
slot is `-1` to include genesis; its default last slot is the maximum historical
output slot captured before copying. The CLI must use the same database/schema and
maintenance property as the live store. Set an explicit fixed `--to-slot` for
resumable batches and record it with the release evidence.

Example bounded interval:

```text
backfill-unspent-utxos --from-slot -1 --to-slot 4318 --chunk-slots 4320 --statement-timeout-ms 5000 --lock-timeout-ms 250
```

Use the reported successful chunk end to resume; repeat a completed interval safely
if the last log was lost. A timeout or error rolls back that chunk and aborts the
command. It must not be treated as completed. Reduce the interval/chunk size after
examining server-side timings rather than extending the query budget. The live
writer and backfill share one PostgreSQL advisory transaction lock; a busy writer
can cancel a CLI chunk at its configured lock budget.

Every complete requested interval is reconciled against spends after its captured
tip. For a full initial fill, copy every interval from `-1` through the captured tip,
then run the final reconciliation. Keep current reads disabled throughout.

## Verify and enable reads

Use read-only, server-timed probes with explicit query budgets. Record image,
database tip, statement duration, row count and operator class. Validate current
table membership against `address_utxo` minus `tx_input` in bounded slot ranges,
including genesis, early history, spend-heavy slots and the backfill/commit boundary.
Confirm no current row has a matching spend, and no range has an unspent source
outpoint missing from the current table. Check full column parity in sampled ranges.
Validate all copied intervals, not merely the most recent tip.

For the three previously failed fixtures, compare both holder page directions and
pages 1 and 2 with Blockfrost, retaining complete quantities and full addresses:

- Mainnet SNEK: `279c909f348e533da5808898f87f9a14bb2c3dfbbacccd631d927a3f534e454b`
- Preprod WingRiders: `659ab0b5658687c2e74cd10dba8244015b713bf503b90557769d77a757696e67526964657273`
- Preview empty-name asset: `177c1580591e6fb9d7a790627cf0b787b1d792c46087afb0db8a9446`

Run the current-table holder SQL directly first. A stable successful query inside
the existing endpoint budget is required; smaller storage or local tests alone do
not establish availability. Only after full backfill, reconciliation, membership
and body parity pass, set `store.utxo.unspent-table-read-enabled=true` in the durable
manifest and deploy normally. Recheck the exact Nexus endpoints after the running
image/config changes. Preserve unavailable Blockfrost references as such.

If acceptance fails, set the read flag false through the normal configuration
workflow while leaving maintenance on. This keeps the current table warm for
diagnosis. The historical source remains available throughout.
