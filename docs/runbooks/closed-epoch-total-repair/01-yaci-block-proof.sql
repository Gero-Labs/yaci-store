-- psql -v e=633 (or mainnet660/preprod318). One epoch per run.
-- Chain tuple digest must match 02-dbsync-block-proof.sql exactly.
\set ON_ERROR_STOP on
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
WITH b AS MATERIALIZED (
  SELECT hash, number, slot, epoch, prev_hash, block_time, no_of_txs, total_output, total_fees
  FROM yaci_store.block WHERE epoch = :e
), ordered AS (
  SELECT *, lag(number) OVER (ORDER BY number, hash) AS pn,
            lag(hash) OVER (ORDER BY number, hash) AS ph,
            lag(slot) OVER (ORDER BY number, hash) AS ps,
            lag(block_time) OVER (ORDER BY number, hash) AS pt
  FROM b
), agg AS (
  SELECT :e::integer AS epoch, count(*) AS block_count,
         count(DISTINCT number) AS distinct_numbers, count(DISTINCT slot) AS distinct_slots,
         min(number) AS min_number, max(number) AS max_number,
         min(slot) AS min_slot, max(slot) AS max_slot,
         min(block_time) AS first_block_time, max(block_time) AS last_block_time,
         coalesce(sum(no_of_txs),0) AS tx_count,
         coalesce(sum(total_output),0) AS declared_output,
         coalesce(sum(total_fees),0) AS declared_fees,
         count(*) FILTER (WHERE number IS NULL OR slot IS NULL OR block_time IS NULL
                           OR no_of_txs IS NULL OR total_output IS NULL OR total_fees IS NULL
                           OR hash IS NULL OR prev_hash IS NULL) AS null_source_rows,
         count(*) FILTER (WHERE no_of_txs < 0 OR total_output < 0 OR total_fees < 0) AS negative_source_rows,
         count(*) FILTER (WHERE pn IS NOT NULL AND number <> pn + 1) AS number_gaps,
         count(*) FILTER (WHERE ph IS NOT NULL AND prev_hash IS DISTINCT FROM ph) AS broken_prev_links,
         count(*) FILTER (WHERE ps IS NOT NULL AND slot <= ps) AS nonincreasing_slots,
         count(*) FILTER (WHERE pt IS NOT NULL AND block_time <= pt) AS nonincreasing_times,
         md5(string_agg(number::text || '|' || lower(hash) || '|' || slot::text || '|'
                        || block_time::text || '|' || no_of_txs::text || '|'
                        || lower(prev_hash), E'\n' ORDER BY number, hash)) AS chain_digest
  FROM ordered
), edge AS (
  SELECT a.*,
         (SELECT hash FROM b ORDER BY number, hash LIMIT 1) AS first_hash,
         (SELECT prev_hash FROM b ORDER BY number, hash LIMIT 1) AS first_prev_hash,
         (SELECT hash FROM b ORDER BY number DESC, hash LIMIT 1) AS last_hash
  FROM agg a
)
SELECT x.*, e.block_count AS stored_block_count, e.transaction_count AS stored_tx_count,
       e.total_output AS stored_output, e.total_fees AS stored_fees,
       e.start_time AS stored_first_block_time, e.end_time AS stored_last_block_time,
       e.max_slot AS stored_max_slot, e.update_datetime AS stored_update_datetime,
       (SELECT count(*) FROM yaci_store.block p
         WHERE p.number = x.min_number - 1 AND p.hash = x.first_prev_hash) AS predecessor_link_matches,
       (SELECT count(*) FROM yaci_store.block n
         WHERE n.number = x.max_number + 1 AND n.prev_hash = x.last_hash) AS successor_link_matches,
       (SELECT epoch FROM yaci_store.block ORDER BY number DESC LIMIT 1) AS tip_epoch,
       (SELECT number FROM yaci_store.block ORDER BY number DESC LIMIT 1) - x.max_number AS blocks_after_epoch
FROM edge x LEFT JOIN yaci_store.epoch e ON e.number = x.epoch;
ROLLBACK;
