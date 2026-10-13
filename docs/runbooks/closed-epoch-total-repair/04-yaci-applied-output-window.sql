-- psql -v e=633 -v s0=... -v s1=... . Prefer <=10000-slot windows for this heavier read.
-- Applied outputs, including spent history: NEVER anti-join tx_input for an epoch output sum.
-- A timeout is unresolved proof; reduce the window, do not raise the server timeout.
\set ON_ERROR_STOP on
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
WITH t AS MATERIALIZED (
 SELECT tx_hash, block_hash, block, slot, block_time, epoch, invalid, collateral_return_json,
        CASE WHEN jsonb_typeof(outputs) = 'array' THEN jsonb_array_length(outputs) END AS body_output_count
 FROM yaci_store.transaction WHERE epoch = :e AND slot BETWEEN :s0 AND :s1
), u AS MATERIALIZED (
 SELECT tx_hash, output_index, block_hash, block, slot, block_time, epoch, is_collateral_return, lovelace_amount
 FROM yaci_store.address_utxo WHERE slot BETWEEN :s0 AND :s1
), per_tx AS (
 SELECT t.tx_hash, t.invalid,
        CASE WHEN t.invalid IS FALSE THEN t.body_output_count
             WHEN t.invalid IS TRUE AND t.collateral_return_json IS NULL THEN 0
             WHEN t.invalid IS TRUE AND t.collateral_return_json = 'null'::jsonb THEN 0
             WHEN t.invalid IS TRUE AND jsonb_typeof(t.collateral_return_json) = 'object' THEN 1
        END AS expected_applied_outputs,
        count(u.tx_hash) AS actual_applied_outputs,
        count(u.tx_hash) FILTER (WHERE u.lovelace_amount IS NULL OR u.lovelace_amount < 0) AS bad_lovelace,
        count(u.tx_hash) FILTER (WHERE u.block_hash IS DISTINCT FROM t.block_hash OR u.block IS DISTINCT FROM t.block
                                  OR u.slot IS DISTINCT FROM t.slot OR u.block_time IS DISTINCT FROM t.block_time
                                  OR u.epoch IS DISTINCT FROM t.epoch) AS bad_metadata,
        count(u.tx_hash) FILTER (WHERE t.invalid IS TRUE AND
                                  (u.is_collateral_return IS NOT TRUE OR u.output_index IS DISTINCT FROM t.body_output_count)
                                  OR t.invalid IS FALSE AND
                                  (u.is_collateral_return IS TRUE OR u.output_index < 0 OR u.output_index >= t.body_output_count)) AS bad_output_semantics
 FROM t LEFT JOIN u ON u.tx_hash = t.tx_hash
 GROUP BY t.tx_hash, t.invalid, t.body_output_count, t.collateral_return_json
)
SELECT :e::integer AS epoch, :s0::bigint AS first_slot, :s1::bigint AS last_slot,
       (SELECT count(*) FROM t) AS transaction_rows,
       (SELECT count(*) FROM u) AS applied_output_rows,
       (SELECT coalesce(sum(lovelace_amount),0) FROM u) AS applied_output_sum,
       (SELECT count(*) FROM u LEFT JOIN t ON t.tx_hash = u.tx_hash WHERE t.tx_hash IS NULL) AS orphan_outputs,
       (SELECT count(*) FROM u WHERE epoch IS DISTINCT FROM :e::integer) AS wrong_epoch_outputs,
       count(*) FILTER (WHERE expected_applied_outputs IS NULL) AS malformed_transaction_output_shapes,
       count(*) FILTER (WHERE expected_applied_outputs IS DISTINCT FROM actual_applied_outputs) AS output_count_mismatches,
       coalesce(sum(bad_lovelace),0) AS bad_lovelace_rows,
       coalesce(sum(bad_metadata),0) AS bad_output_metadata,
       coalesce(sum(bad_output_semantics),0) AS wrong_applied_output_semantics
FROM per_tx;
ROLLBACK;
