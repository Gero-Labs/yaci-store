-- Inspect one scalar example before using 06. No raw CBOR/full transaction content is emitted.
-- psql -v e=633 -v s0=... -v s1=...
\set ON_ERROR_STOP on
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL jit = off;
SELECT t.tx_hash, t.slot, t.fee, t.total_collateral,
       jsonb_typeof(t.outputs) AS output_keys_type,
       jsonb_typeof(t.collateral_inputs) AS collateral_inputs_type,
       (SELECT string_agg(k,',' ORDER BY k) FROM jsonb_object_keys(
          CASE WHEN jsonb_typeof(t.collateral_inputs->0) = 'object' THEN t.collateral_inputs->0 ELSE '{}'::jsonb END) k) AS input_key_names,
       jsonb_typeof(t.collateral_return_json) AS return_type,
       (SELECT string_agg(k,',' ORDER BY k) FROM jsonb_object_keys(
          CASE WHEN jsonb_typeof(i.transaction) = 'object' THEN i.transaction ELSE '{}'::jsonb END) k) AS transaction_key_names,
       (SELECT string_agg(k,',' ORDER BY k) FROM jsonb_object_keys(
          CASE WHEN jsonb_typeof(i.transaction->'body') = 'object' THEN i.transaction->'body' ELSE '{}'::jsonb END) k) AS body_key_names,
       i.transaction #>> '{body,fee}' AS body_declared_fee,
       i.transaction #>> '{body,totalCollateral}' AS body_total_collateral,
       jsonb_typeof(i.transaction #> '{body,outputs}') AS body_outputs_type,
       (SELECT string_agg(k,',' ORDER BY k) FROM jsonb_object_keys(
          CASE WHEN jsonb_typeof(i.transaction #> '{body,outputs,0}') = 'object'
               THEN i.transaction #> '{body,outputs,0}' ELSE '{}'::jsonb END) k) AS body_output_key_names,
       (SELECT string_agg(k,',' ORDER BY k) FROM jsonb_object_keys(
          CASE WHEN jsonb_typeof(i.transaction #> '{body,outputs,0,amounts,0}') = 'object'
               THEN i.transaction #> '{body,outputs,0,amounts,0}' ELSE '{}'::jsonb END) k) AS body_amount_key_names,
       (SELECT string_agg(k,',' ORDER BY k) FROM jsonb_object_keys(
          CASE WHEN jsonb_typeof(t.collateral_return_json) = 'object'
               THEN t.collateral_return_json ELSE '{}'::jsonb END) k) AS return_key_names,
       jsonb_typeof(i.transaction #> '{body,collateralInputs}') AS body_collateral_inputs_type,
       jsonb_typeof(i.transaction #> '{body,collateralReturn}') AS body_return_type,
       jsonb_typeof(i.transaction #> '{body,collateralReturn,amounts}') AS body_return_amounts_type
FROM yaci_store.transaction t LEFT JOIN yaci_store.invalid_transaction i ON i.tx_hash = t.tx_hash
WHERE t.epoch = :e AND t.slot BETWEEN :s0 AND :s1 AND t.invalid IS TRUE
ORDER BY t.slot, t.tx_index LIMIT 1;
ROLLBACK;
