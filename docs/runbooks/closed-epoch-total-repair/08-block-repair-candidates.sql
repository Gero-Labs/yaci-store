-- Bounded read-only historical candidate dry run: e,s0,s1 (<=10000-slot window).
-- Run only after 00..07 prove the complete epoch chain and every semantic equation.
-- Emits all affected invalid-containing block candidates, even already-correct controls.
-- No updates, backup creation, temp-table writes, or production mutations.
\set ON_ERROR_STOP on
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
WITH b AS MATERIALIZED (
 SELECT hash, number, slot, epoch, no_of_txs, total_fees, total_output
 FROM yaci_store.block WHERE epoch = :e AND slot BETWEEN :s0 AND :s1
), t AS MATERIALIZED (
 SELECT tx_hash, block_hash, block, slot, epoch, tx_index, invalid, fee, outputs, collateral_return_json
 FROM yaci_store.transaction WHERE epoch = :e AND slot BETWEEN :s0 AND :s1
), u AS MATERIALIZED (
 SELECT tx_hash, output_index, block_hash, block, slot, epoch, is_collateral_return, lovelace_amount
 FROM yaci_store.address_utxo WHERE slot BETWEEN :s0 AND :s1
), scope_transactions AS MATERIALIZED (
 SELECT ts.* FROM yaci_store.transaction ts
 WHERE ts.slot BETWEEN :s0 AND :s1
 UNION ALL
 SELECT ts.* FROM b JOIN yaci_store.transaction ts ON ts.block_hash=b.hash
 WHERE (ts.slot BETWEEN :s0 AND :s1) IS NOT TRUE
), scope_outputs AS MATERIALIZED (
 SELECT us.* FROM yaci_store.address_utxo us WHERE us.slot BETWEEN :s0 AND :s1
 UNION ALL
 SELECT us.* FROM scope_transactions ts JOIN yaci_store.address_utxo us ON us.tx_hash=ts.tx_hash
 WHERE (us.slot BETWEEN :s0 AND :s1) IS NOT TRUE
), scope_logs AS MATERIALIZED (
 SELECT ins.* FROM yaci_store.invalid_transaction ins WHERE ins.slot BETWEEN :s0 AND :s1
 UNION ALL
 SELECT ins.* FROM scope_transactions ts JOIN yaci_store.invalid_transaction ins ON ins.tx_hash=ts.tx_hash
 WHERE (ins.slot BETWEEN :s0 AND :s1) IS NOT TRUE
), scope_proof AS MATERIALIZED (
 SELECT md5(jsonb_build_object(
   'blocks', coalesce((SELECT jsonb_agg(to_jsonb(bs) ORDER BY bs.number,bs.hash)
      FROM yaci_store.block bs WHERE bs.slot BETWEEN :s0 AND :s1),'[]'::jsonb),
   'transactions', coalesce((SELECT jsonb_agg(to_jsonb(ts) ORDER BY ts.tx_hash)
      FROM scope_transactions ts),'[]'::jsonb),
   'outputs', coalesce((SELECT jsonb_agg(to_jsonb(us) ORDER BY us.tx_hash,us.output_index)
      FROM scope_outputs us),'[]'::jsonb),
   'invalid_logs', coalesce((SELECT jsonb_agg(to_jsonb(ins) ORDER BY ins.tx_hash)
      FROM scope_logs ins),'[]'::jsonb)
 )::text) AS scope_digest
), tx_totals AS (
 SELECT t.block_hash, count(*) AS transaction_rows, count(*) FILTER (WHERE t.invalid IS TRUE) AS invalid_transactions,
        sum(t.fee)::numeric AS effective_fee,
        count(DISTINCT t.tx_index) AS distinct_indexes, min(t.tx_index) AS first_index, max(t.tx_index) AS last_index,
        count(*) FILTER (WHERE t.fee IS NULL OR t.fee < 0 OR t.invalid IS NULL OR t.tx_index IS NULL
                           OR jsonb_typeof(t.outputs) IS DISTINCT FROM 'array'
                           OR t.slot IS DISTINCT FROM b.slot OR t.block IS DISTINCT FROM b.number) AS transaction_errors,
        sum(CASE WHEN t.invalid IS FALSE AND jsonb_typeof(t.outputs) = 'array' THEN jsonb_array_length(t.outputs)
                 WHEN t.invalid IS TRUE AND (t.collateral_return_json IS NULL OR t.collateral_return_json = 'null'::jsonb) THEN 0
                 WHEN t.invalid IS TRUE AND jsonb_typeof(t.collateral_return_json) = 'object' THEN 1 END) AS expected_outputs,
        md5(string_agg(t.tx_hash || '|' || t.tx_index::text || '|' || t.invalid::text || '|' || t.fee::text,
                       E'\n' ORDER BY t.tx_index, t.tx_hash)) AS transaction_digest
 FROM t JOIN b ON b.hash = t.block_hash GROUP BY t.block_hash
), output_totals AS (
 SELECT u.block_hash, count(*) AS output_rows, coalesce(sum(u.lovelace_amount),0)::numeric AS applied_output,
        count(*) FILTER (WHERE u.lovelace_amount IS NULL OR u.lovelace_amount < 0 OR t.tx_hash IS NULL
                           OR u.epoch IS DISTINCT FROM t.epoch OR u.slot IS DISTINCT FROM t.slot OR u.block IS DISTINCT FROM t.block
                           OR (t.invalid IS TRUE AND (u.is_collateral_return IS NOT TRUE
                             OR u.output_index IS DISTINCT FROM jsonb_array_length(t.outputs)))
                           OR (t.invalid IS FALSE AND (u.is_collateral_return IS TRUE OR u.output_index < 0
                             OR u.output_index >= jsonb_array_length(t.outputs)))) AS output_errors,
        md5(string_agg(u.tx_hash || '|' || u.output_index::text || '|' || u.lovelace_amount::text || '|'
                       || coalesce(u.is_collateral_return::text,'null'), E'\n'
                       ORDER BY u.tx_hash, u.output_index)) AS output_digest
 FROM u LEFT JOIN t ON t.tx_hash = u.tx_hash GROUP BY u.block_hash
)
SELECT b.hash, b.number, b.slot, b.epoch, b.no_of_txs,
       b.total_fees AS original_fee, b.total_output AS original_output,
       tt.effective_fee AS candidate_fee, coalesce(ot.applied_output,0) AS candidate_output,
       tt.invalid_transactions, tt.transaction_digest, coalesce(ot.output_digest,md5('')) AS output_digest,
       proof.source_digest, scope_proof.scope_digest,
       tt.transaction_errors, coalesce(ot.output_errors,0) AS output_errors,
       tt.transaction_rows <> b.no_of_txs OR tt.distinct_indexes <> b.no_of_txs
         OR tt.first_index <> 0 OR tt.last_index <> b.no_of_txs - 1 AS transaction_count_error,
       tt.expected_outputs IS DISTINCT FROM coalesce(ot.output_rows,0) AS output_count_error,
       b.total_fees IS DISTINCT FROM tt.effective_fee
         OR b.total_output IS DISTINCT FROM coalesce(ot.applied_output,0) AS needs_repair
FROM b JOIN tx_totals tt ON tt.block_hash = b.hash
LEFT JOIN output_totals ot ON ot.block_hash = b.hash
CROSS JOIN scope_proof
CROSS JOIN LATERAL (
 SELECT md5(jsonb_build_object(
   'block', (SELECT to_jsonb(bs) FROM yaci_store.block bs WHERE bs.hash=b.hash),
   'transactions', coalesce((SELECT jsonb_agg(to_jsonb(ts) ORDER BY ts.tx_index,ts.tx_hash)
      FROM yaci_store.transaction ts WHERE ts.block_hash=b.hash),'[]'::jsonb),
   'outputs', coalesce((SELECT jsonb_agg(to_jsonb(us) ORDER BY us.tx_hash,us.output_index)
      FROM (
        SELECT us.* FROM yaci_store.address_utxo us WHERE us.slot=b.slot AND us.block_hash=b.hash
        UNION ALL
        SELECT us.* FROM yaci_store.transaction ts JOIN yaci_store.address_utxo us ON us.tx_hash=ts.tx_hash
        WHERE ts.block_hash=b.hash AND (us.slot=b.slot AND us.block_hash=b.hash) IS NOT TRUE
      ) us),'[]'::jsonb),
   'invalid_logs', coalesce((SELECT jsonb_agg(to_jsonb(ins) ORDER BY ins.tx_hash)
      FROM (
        SELECT ins.* FROM yaci_store.invalid_transaction ins WHERE ins.slot=b.slot AND ins.block_hash=b.hash
        UNION ALL
        SELECT ins.* FROM yaci_store.transaction ts JOIN yaci_store.invalid_transaction ins ON ins.tx_hash=ts.tx_hash
        WHERE ts.block_hash=b.hash AND (ins.slot=b.slot AND ins.block_hash=b.hash) IS NOT TRUE
      ) ins),'[]'::jsonb),
   'collateral_sources', coalesce((SELECT jsonb_agg(to_jsonb(cs) ORDER BY cs.tx_hash,cs.output_index)
      FROM yaci_store.address_utxo cs JOIN (
        SELECT DISTINCT key->>'tx_hash' AS tx_hash,
          CASE WHEN key->>'output_index' ~ '^[0-9]+$' THEN (key->>'output_index')::integer END AS output_index
        FROM yaci_store.transaction ts CROSS JOIN LATERAL jsonb_array_elements(
          CASE WHEN jsonb_typeof(ts.collateral_inputs)='array' THEN ts.collateral_inputs ELSE '[]'::jsonb END) key
        WHERE ts.block_hash=b.hash AND ts.invalid IS TRUE
      ) requested ON cs.tx_hash=requested.tx_hash AND cs.output_index=requested.output_index),'[]'::jsonb)
 )::text) AS source_digest
) proof
WHERE tt.invalid_transactions > 0
ORDER BY b.number, b.hash;
ROLLBACK;
