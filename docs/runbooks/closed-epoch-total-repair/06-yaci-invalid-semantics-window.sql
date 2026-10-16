-- Run only after 05 confirms actual JSON names and types. Source predicts camelCase body fields
-- (Yaci model) and snake_case collateral input keys (UtxoKey). Adapt only if 05 proves otherwise.
-- psql -v e=633 -v s0=... -v s1=... . <=50000 slots, shrink on server timeout.
-- Each invalid tx is sparse and collateral lookups use address_utxo's composite PK.
\set ON_ERROR_STOP on
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL jit = off;
WITH x AS MATERIALIZED (
 SELECT t.tx_hash, t.block, t.slot, t.fee, t.total_collateral, t.collateral_inputs,
        t.collateral_return_json, i.tx_hash AS invalid_log_hash, i.transaction->'body' AS body,
        i.transaction->>'invalid' AS log_invalid,
        i.transaction #>> '{body,txHash}' AS body_tx_hash,
        i.transaction #>> '{body,totalCollateral}' AS body_total_collateral_text,
        i.transaction #>> '{body,fee}' AS declared_fee_text
 FROM yaci_store.transaction t LEFT JOIN yaci_store.invalid_transaction i ON i.tx_hash = t.tx_hash
 WHERE t.epoch = :e AND t.slot BETWEEN :s0 AND :s1 AND t.invalid IS TRUE
), parsed AS (
 SELECT x.*, c.requested_inputs, c.found_inputs, c.malformed_keys, c.bad_input_values,
        c.duplicate_inputs, c.input_sum, r.return_amount_rows, r.bad_return_values, r.return_sum,
        d.body_output_sum, d.malformed_outputs,
        br.body_return_sum, br.body_return_lovelace_rows, br.bad_body_return_values,
        CASE WHEN declared_fee_text ~ '^[0-9]+$' THEN declared_fee_text::numeric END AS declared_fee,
        CASE WHEN total_collateral > 0 THEN total_collateral::numeric
             WHEN jsonb_typeof(collateral_inputs) = 'array' AND c.requested_inputs > 0
                  AND c.requested_inputs = c.found_inputs AND c.malformed_keys = 0
                  AND c.bad_input_values = 0 AND c.duplicate_inputs = 0 AND r.bad_return_values = 0
                  AND (collateral_return_json IS NULL OR collateral_return_json = 'null'::jsonb
                       OR (jsonb_typeof(collateral_return_json->'amounts') = 'array' AND r.return_amount_rows = 1))
             THEN c.input_sum - r.return_sum END AS expected_effective_fee
 FROM x
 CROSS JOIN LATERAL (
   SELECT count(*) AS requested_inputs, count(u.tx_hash) AS found_inputs,
          count(*) FILTER (WHERE key->>'tx_hash' IS NULL OR key->>'output_index' IS NULL) AS malformed_keys,
          count(u.tx_hash) FILTER (WHERE u.lovelace_amount IS NULL OR u.lovelace_amount < 0) AS bad_input_values,
          count(*) - count(DISTINCT (key->>'tx_hash',key->>'output_index')) AS duplicate_inputs,
          coalesce(sum(u.lovelace_amount),0)::numeric AS input_sum
   FROM jsonb_array_elements(CASE WHEN jsonb_typeof(x.collateral_inputs) = 'array'
                                 THEN x.collateral_inputs ELSE '[]'::jsonb END) key
   LEFT JOIN yaci_store.address_utxo u ON u.tx_hash = key->>'tx_hash'
        AND u.output_index = CASE WHEN key->>'output_index' ~ '^[0-9]+$' THEN (key->>'output_index')::integer END
 ) c
 CROSS JOIN LATERAL (
   SELECT count(*) FILTER (WHERE amount->>'unit' = 'lovelace') AS return_amount_rows,
          count(*) FILTER (WHERE amount->>'unit' = 'lovelace'
                               AND (amount->>'quantity' IS NULL OR amount->>'quantity' !~ '^[0-9]+$')) AS bad_return_values,
          coalesce(sum(CASE WHEN amount->>'unit' = 'lovelace' AND amount->>'quantity' ~ '^[0-9]+$'
                             THEN (amount->>'quantity')::numeric END),0) AS return_sum
   FROM jsonb_array_elements(CASE WHEN jsonb_typeof(x.collateral_return_json->'amounts') = 'array'
                                 THEN x.collateral_return_json->'amounts' ELSE '[]'::jsonb END) amount
 ) r
 CROSS JOIN LATERAL (
   SELECT coalesce(sum(oa.output_lovelace),0) AS body_output_sum,
          count(*) FILTER (WHERE jsonb_typeof(output) IS DISTINCT FROM 'object'
                             OR jsonb_typeof(output->'amounts') IS DISTINCT FROM 'array'
                             OR oa.lovelace_rows <> 1 OR oa.bad_quantities <> 0) AS malformed_outputs
   FROM jsonb_array_elements(CASE WHEN jsonb_typeof(x.body->'outputs') = 'array'
                                 THEN x.body->'outputs' ELSE '[]'::jsonb END) output
   CROSS JOIN LATERAL (
      SELECT count(*) FILTER (WHERE amount->>'unit' = 'lovelace') AS lovelace_rows,
             count(*) FILTER (WHERE amount->>'unit' = 'lovelace'
                                  AND (amount->>'quantity' IS NULL OR amount->>'quantity' !~ '^[0-9]+$')) AS bad_quantities,
             coalesce(sum(CASE WHEN amount->>'unit' = 'lovelace' AND amount->>'quantity' ~ '^[0-9]+$'
                                THEN (amount->>'quantity')::numeric END),0) AS output_lovelace
      FROM jsonb_array_elements(CASE WHEN jsonb_typeof(output->'amounts') = 'array'
                                    THEN output->'amounts' ELSE '[]'::jsonb END) amount
   ) oa
 ) d
 CROSS JOIN LATERAL (
   SELECT coalesce(sum(CASE WHEN amount->>'unit' = 'lovelace' AND amount->>'quantity' ~ '^[0-9]+$'
                             THEN (amount->>'quantity')::numeric END),0) AS body_return_sum,
          count(*) FILTER (WHERE amount->>'unit' = 'lovelace') AS body_return_lovelace_rows,
          count(*) FILTER (WHERE amount->>'unit' = 'lovelace'
                               AND (amount->>'quantity' IS NULL OR amount->>'quantity' !~ '^[0-9]+$')) AS bad_body_return_values
   FROM jsonb_array_elements(CASE WHEN jsonb_typeof(x.body #> '{collateralReturn,amounts}') = 'array'
                                 THEN x.body #> '{collateralReturn,amounts}' ELSE '[]'::jsonb END) amount
 ) br
)
SELECT :e::integer AS epoch, :s0::bigint AS first_slot, :s1::bigint AS last_slot,
       count(*) AS invalid_transactions,
       count(*) FILTER (WHERE invalid_log_hash IS NULL) AS missing_invalid_logs,
       count(*) FILTER (WHERE declared_fee IS NULL OR jsonb_typeof(body->'outputs') IS DISTINCT FROM 'array'
                         OR malformed_outputs > 0 OR log_invalid IS DISTINCT FROM 'true'
                         OR body_tx_hash IS DISTINCT FROM tx_hash) AS unproved_declared_body,
       count(*) FILTER (WHERE
         (body_total_collateral_text IS NOT NULL AND body_total_collateral_text !~ '^[0-9]+$')
         OR ((total_collateral > 0 OR CASE WHEN body_total_collateral_text ~ '^[0-9]+$'
                     THEN body_total_collateral_text::numeric > 0 ELSE false END)
             AND CASE WHEN body_total_collateral_text ~ '^[0-9]+$'
                     THEN body_total_collateral_text::numeric END IS DISTINCT FROM total_collateral::numeric)
       ) AS total_collateral_body_mismatches,
       count(*) FILTER (WHERE jsonb_typeof(collateral_inputs) IS DISTINCT FROM 'array' OR requested_inputs = 0
                         OR requested_inputs <> found_inputs OR malformed_keys > 0 OR bad_input_values > 0
                         OR duplicate_inputs > 0) AS incomplete_collateral_inputs,
       count(*) FILTER (WHERE total_collateral > 0 AND requested_inputs = found_inputs
                         AND requested_inputs > 0 AND malformed_keys = 0 AND bad_input_values = 0
                         AND duplicate_inputs = 0 AND input_sum - return_sum <> total_collateral) AS collateral_conservation_mismatches,
       count(*) FILTER (WHERE expected_effective_fee IS NULL OR expected_effective_fee < 0) AS unresolved_effective_fees,
       count(*) FILTER (WHERE expected_effective_fee IS DISTINCT FROM fee::numeric) AS stored_effective_fee_mismatches,
       count(*) FILTER (WHERE collateral_return_json IS NOT NULL AND collateral_return_json <> 'null'::jsonb
                         AND (jsonb_typeof(collateral_return_json->'amounts') IS DISTINCT FROM 'array'
                              OR return_amount_rows <> 1 OR bad_return_values <> 0)) AS malformed_collateral_returns,
       count(*) FILTER (WHERE body->'collateralReturn' IS NOT NULL AND body->'collateralReturn' <> 'null'::jsonb
                         AND (jsonb_typeof(body #> '{collateralReturn,amounts}') IS DISTINCT FROM 'array'
                              OR body_return_lovelace_rows <> 1 OR bad_body_return_values <> 0)) AS malformed_body_returns,
       count(*) FILTER (WHERE body_return_sum IS DISTINCT FROM return_sum) AS return_body_value_mismatches,
       count(*) FILTER (WHERE
         (body->'collateralReturn' IS NOT NULL AND body->'collateralReturn' <> 'null'::jsonb)
         IS DISTINCT FROM (collateral_return_json IS NOT NULL AND collateral_return_json <> 'null'::jsonb)) AS return_body_presence_mismatches,
       coalesce(sum(expected_effective_fee - declared_fee),0) AS fee_correction_delta,
       coalesce(sum(body_output_sum - return_sum),0) AS output_reduction_delta,
       coalesce(sum(fee),0) AS stored_invalid_effective_fees,
       coalesce(sum(declared_fee),0) AS invalid_declared_fees,
       coalesce(sum(expected_effective_fee),0) AS independently_resolved_invalid_fees,
       coalesce(sum(body_output_sum),0) AS invalid_declared_output_sum,
       coalesce(sum(return_sum),0) AS invalid_applied_return_sum
FROM parsed;
ROLLBACK;
