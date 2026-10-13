# Repairing verified closed-epoch block totals

The block writer now uses the same effective collateral fee calculation as the transaction writer. Valid transactions contribute ordinary outputs; phase-2-invalid transactions contribute only their collateral return. Missing collateral can resolve after parallel block processing, so affected blocks wait until precommit. Both writers resolve their complete pending batch before saving it; if collateral remains unresolved, processing fails before publishing inaccurate totals or advancing the cursor. There is no declared-fee fallback.

The complete application uses the primary local `UtxoClient`, which reads cached and historical created outputs, including those later spent. A standalone blocks store must enable that local client or configure a remote UTxO client. The starter's dummy client cannot resolve invalid transactions without positive `totalCollateral`; those blocks now stop at precommit instead of silently recording declared fees. Both writers retry only at precommit, after parallel event processing has completed. Block fee precommit runs at order0, followed by transaction fees at order1, before default plugin listeners; a failed fee guard prevents those listeners, commit and the cursor update. Pending queues clear on rollback, including the startup rollback before replay; failed saves retain the queue until success or rollback.

These future writer changes do not repair previous `block` or `epoch` rows. The min/max epoch-bound fix also only affects epochs actually aggregated. A broad summary-only rebuild preserves any wrong declared block fees or invalid ordinary outputs.

## Source proof before repair

The SQL kit in [`closed-epoch-total-repair/`](closed-epoch-total-repair/) targets one epoch and bounded windows. Read-only files use 5/10-second PostgreSQL statement timeouts, a one-second lock timeout, and no parallel workers. A timeout means unresolved proof: shrink the window rather than increasing the timeout. Let PostgreSQL cancel server work. Do not kill an exec/psql stream to cancel a production query; use sequential in-cluster Jobs.

1. `00-catalog.sql` confirms the selected database, schema, column types, and valid indexes. Confirm the network matches the independent reference.
2. `01-yaci-block-proof.sql` validates canonical block continuity, hashes, predecessor/successor links, duplicate/null data, count, transaction count, source sums, and first/last time. The epoch must be closed and farther behind the tip than the actual network security parameter.
3. `02-dbsync-block-proof.sql` independently derives ordered canonical chain tuples from source blocks. Its digest and all counts/edges/times must equal Yaci's. The db-sync `epoch` table is not a substitute for source-data validation.
4. `03-yaci-transaction-window.sql` covers the entire epoch with disjoint inclusive windows of at most 50,000 slots. Per-block transaction counts, index ranges, metadata, invalid flags/logs, and effective fees must be complete. `07-dbsync-transaction-window.sql` must have the same effective transaction digest, invalid count, and fee sum.
5. `04-yaci-applied-output-window.sql`, preferably at most 10,000 slots per window, proves output membership/counts/indices/metadata and ledger-applied semantics. `address_utxo` contains created history, including outputs later spent: never subtract spent outputs from an epoch output total. Sum complete windows and compare against the independent source `tx.out_sum` and the hosted API's closed-epoch output.
6. `05-invalid-json-shape.sql` confirms actual JSON field names before `06-yaci-invalid-semantics-window.sql` reads them. Yaci body fields are expected to be camelCase; stored collateral outpoint keys are snake_case. Missing/malformed bodies or collateral return values must fail proof. Effective invalid fees derive from positive body `totalCollateral`, otherwise complete exact collateral inputs minus return. Stored fees must match, and every input required for derivation must be present exactly once.
7. Aggregate the invalid corrections over every invalid-containing window. Require `declared_block_fees + fee_correction_delta = effective_transaction_fees = independent_tx_fees = hosted_epoch_fees` and `declared_block_output - output_reduction_delta = applied_output_sum = independent_tx_output = hosted_epoch_output`. Exact count equality alone is insufficient.
8. Re-read canonical source proof after the windows. A concurrent rebuild, rollback, or changed digest invalidates the candidate.

The root executor should save proof output and a reviewable manifest before writes. The kit has no credentials; supply credentials only using the normal secret-backed Job environment. Actual schema/type errors or insufficient role grants must be resolved explicitly, never hidden by removing a guard.

## Bounded reviewed repair

`08-block-repair-candidates.sql` is a read-only dry run for at most 10,000 slots. It emits original and proposed fee/output totals, exact transaction/output digests, invalid transaction counts, and error fields for invalid-containing blocks. Every error field must be zero/false, and the complete epoch proof above must already have passed. Keep already-correct candidates as controls and review the changed set.

`09-apply-reviewed-block-window.sql` is mutation SQL. It accepts only the reviewed JSON candidate, the same exact window, and the actual positive network security parameter. It rechecks original block values and current transaction/output digests, backs up original changed block rows in `bfcompat_epoch_repair_20261013`, and updates only candidates marked `needs_repair`. The changed-row count must match. It does not derive a new unreviewed candidate or repair ordinary valid-only blocks.

After all reviewed block windows are repaired, `10-apply-reviewed-epoch.sql` accepts one reviewed epoch summary manifest containing original fields, independently proved candidate totals and timestamps, max slot, and canonical chain digest. It rechecks source block totals and the unchanged chain digest, backs up the original summary, and updates exactly one immutable closed row. API scheduled epoch boundaries are separate from stored first/last block times; do not alter protocol boundaries, stake/reward/pot tables, or the open epoch.

The mutation files are a reviewable operator kit, not automatic migrations. They must not be run before source proof and explicit rollout approval. Parser checks do not prove PostgreSQL execution, PL/pgSQL guards, or production data acceptance. Test them on a representative clone before live use.

After repair, repeat source checks and fetch the exact closed-epoch detail plus asc/desc list pages covering it through the running deployment. All historical fields must equal the independent reference. A repaired sampled epoch does not prove every historical epoch repaired.

## Initial reproduced scope

The October 13, 2026 comparison identified mainnet 633 (retained stale summary fee and last time), mainnet 660 (reversed stored first/last timestamps plus invalid fee/output differences), and preprod 318 (correct counts/times, invalid fee/output differences). The mainnet rebuild had begun at 637 and did not revisit 633. These observations establish targets, not complete source-data proof.

The future writer change reuses the semantics in upstream PRs 1183/1186, with one intentional difference: unresolved collateral after precommit fails instead of falling back to a declared fee. Before this patch, the transaction precommit handler logged an unresolved fee and permitted storing null. The transaction guard now prevents that row being saved or published, while the block guard prevents inaccurate block totals. Historical transaction rows still require independent effective-fee proof.
