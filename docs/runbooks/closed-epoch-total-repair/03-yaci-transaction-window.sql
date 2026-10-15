-- psql -v e=633 -v s0=<window_first_slot> -v s1=<window_last_slot>
-- Partition the complete [min_slot,max_slot] into disjoint inclusive <=50000-slot windows.
-- Verify EXPLAIN without ANALYZE first if relevant live indexes are absent/invalid.
\set ON_ERROR_STOP on
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL jit = off;
WITH b AS MATERIALIZED (
 SELECT hash, number, slot, epoch, block_time, no_of_txs
 FROM yaci_store.block WHERE epoch = :e AND slot BETWEEN :s0 AND :s1
), t AS MATERIALIZED (
 SELECT tx_hash, block_hash, block, slot, block_time, epoch, tx_index, invalid, fee
 FROM yaci_store.transaction WHERE slot BETWEEN :s0 AND :s1
 UNION ALL
 SELECT ts.tx_hash, ts.block_hash, ts.block, ts.slot, ts.block_time, ts.epoch, ts.tx_index, ts.invalid, ts.fee
 FROM b JOIN yaci_store.transaction ts ON ts.block_hash=b.hash
 WHERE (ts.slot BETWEEN :s0 AND :s1) IS NOT TRUE
), per_block AS (
 SELECT b.hash, b.number, b.no_of_txs,
        count(t.tx_hash) AS stored_tx_count, count(DISTINCT t.tx_index) AS distinct_tx_indexes,
        min(t.tx_index) AS first_tx_index, max(t.tx_index) AS last_tx_index,
        count(t.tx_hash) FILTER (WHERE t.block IS DISTINCT FROM b.number OR t.slot IS DISTINCT FROM b.slot OR t.epoch IS DISTINCT FROM b.epoch
                                  OR t.block_time IS DISTINCT FROM b.block_time) AS bad_metadata,
        count(t.tx_hash) FILTER (WHERE t.fee IS NULL OR t.invalid IS NULL OR t.tx_index IS NULL) AS null_tx_fields,
        count(t.tx_hash) FILTER (WHERE t.fee < 0) AS negative_fees
 FROM b LEFT JOIN t ON t.block_hash = b.hash
 GROUP BY b.hash, b.number, b.no_of_txs
), invalid_log AS (
 SELECT t.tx_hash, t.invalid, i.tx_hash AS invalid_log_hash,
        i.slot AS invalid_log_slot, i.block_hash AS invalid_log_block_hash, t.slot, t.block_hash
 FROM t LEFT JOIN yaci_store.invalid_transaction i ON i.tx_hash = t.tx_hash
)
SELECT :e::integer AS epoch, :s0::bigint AS first_slot, :s1::bigint AS last_slot,
       (SELECT count(*) FROM b) AS source_blocks,
       (SELECT coalesce(sum(no_of_txs),0) FROM b) AS block_tx_count,
       (SELECT count(*) FROM t) AS transaction_rows,
       (SELECT md5(string_agg(block::text || '|' || tx_index::text || '|' || lower(tx_hash) || '|'
                             || slot::text || '|' || invalid::text || '|' || fee::text,
                             E'\n' ORDER BY block, tx_index, tx_hash)) FROM t) AS effective_transaction_digest,
       (SELECT coalesce(sum(fee),0) FROM t) AS effective_fee_sum,
       (SELECT count(*) FROM t WHERE invalid IS TRUE) AS invalid_transactions,
       (SELECT count(*) FROM t LEFT JOIN b ON b.hash = t.block_hash WHERE b.hash IS NULL) AS orphan_transactions,
       count(*) FILTER (WHERE stored_tx_count <> no_of_txs) AS blocks_with_missing_or_extra_transactions,
       count(*) FILTER (WHERE distinct_tx_indexes <> no_of_txs
                         OR (no_of_txs > 0 AND (first_tx_index <> 0 OR last_tx_index <> no_of_txs-1))) AS bad_index_blocks,
       coalesce(sum(bad_metadata),0) AS bad_transaction_metadata,
       coalesce(sum(null_tx_fields),0) AS null_transaction_fields,
       coalesce(sum(negative_fees),0) AS negative_transaction_fees,
       (SELECT count(*) FROM invalid_log WHERE invalid IS TRUE AND invalid_log_hash IS NULL) AS missing_invalid_logs,
       (SELECT count(*) FROM invalid_log WHERE invalid IS NOT TRUE AND invalid_log_hash IS NOT NULL) AS invalid_flag_mismatches,
       (SELECT count(*) FROM yaci_store.invalid_transaction i
         LEFT JOIN t ON t.tx_hash = i.tx_hash
         WHERE i.slot BETWEEN :s0 AND :s1 AND t.tx_hash IS NULL) AS orphan_invalid_logs,
       (SELECT count(*) FROM invalid_log WHERE invalid_log_hash IS NOT NULL
          AND (invalid_log_slot IS DISTINCT FROM slot OR invalid_log_block_hash IS DISTINCT FROM block_hash)) AS bad_invalid_log_metadata
FROM per_block;
ROLLBACK;
