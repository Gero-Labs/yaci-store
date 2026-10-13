-- Independent read-only data validation; does not add a Nexus db-sync route.
-- psql -v e=633/660/318 on matching cexplorer network.
\set ON_ERROR_STOP on
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
WITH b AS MATERIALIZED (
 SELECT b.block_no AS number, encode(b.hash,'hex') AS hash, b.slot_no AS slot,
        extract(epoch from b.time)::bigint AS block_time, b.tx_count AS no_of_txs,
        encode(p.hash,'hex') AS prev_hash
 FROM public.block b LEFT JOIN public.block p ON p.id = b.previous_id
 WHERE b.epoch_no = :e
)
SELECT :e::integer AS epoch, count(*) AS block_count,
       count(DISTINCT number) AS distinct_numbers, count(DISTINCT slot) AS distinct_slots,
       min(number) AS min_number, max(number) AS max_number,
       min(slot) AS min_slot, max(slot) AS max_slot,
       min(block_time) AS first_block_time, max(block_time) AS last_block_time,
       coalesce(sum(no_of_txs),0) AS tx_count,
       count(*) FILTER (WHERE number IS NULL OR hash IS NULL OR slot IS NULL OR block_time IS NULL
                         OR no_of_txs IS NULL OR prev_hash IS NULL) AS null_source_rows,
       md5(string_agg(number::text || '|' || hash || '|' || slot::text || '|'
                      || block_time::text || '|' || no_of_txs::text || '|'
                      || prev_hash, E'\n' ORDER BY number, hash)) AS chain_digest
FROM b;
ROLLBACK;
