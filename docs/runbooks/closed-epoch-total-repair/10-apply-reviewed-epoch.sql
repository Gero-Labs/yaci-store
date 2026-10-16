-- NOT executed. One proven immutable closed epoch after 09's reviewed block windows.
-- Inputs: e,k, reviewed_epoch_json='[{...}]' with exact before and proven candidate fields.
-- candidate chain_digest is from 01/02; fee/output candidates must be proven by full 03..07.
\set ON_ERROR_STOP on
\timing on
BEGIN;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL jit = off;
CREATE TEMP TABLE reviewed_epoch_summary_patch ON COMMIT DROP AS
SELECT * FROM jsonb_to_recordset(:'reviewed_epoch_json'::jsonb) AS x(
 number bigint, original_block_count integer, original_tx_count bigint, original_fee numeric,
 original_output numeric, original_start bigint, original_end bigint, original_max_slot bigint,
 candidate_block_count integer, candidate_tx_count bigint, candidate_fee numeric, candidate_output numeric,
 candidate_start bigint, candidate_end bigint, candidate_max_slot bigint, chain_digest text);
CREATE TEMP TABLE epoch_summary_repair_scope ON COMMIT DROP AS SELECT :e::integer AS epoch, :k::bigint AS security_depth;
-- Same source-before-row lock order as09. Requires an approved maintenance window.
-- SHARE prevents source updates/deletes/phantom inserts during chain+total checks.
-- 1s bounds each lock wait and10s each statement, not complete transaction wall time.
LOCK TABLE yaci_store.block IN SHARE MODE;
DO $guard$
BEGIN
 IF (SELECT count(*) FROM reviewed_epoch_summary_patch) <> 1 THEN RAISE EXCEPTION 'Exactly one reviewed epoch required'; END IF;
 IF EXISTS (SELECT 1 FROM reviewed_epoch_summary_patch p CROSS JOIN epoch_summary_repair_scope s
              WHERE p.number IS DISTINCT FROM s.epoch OR s.security_depth IS NULL OR s.security_depth <= 0
                OR p.candidate_fee IS NULL OR p.candidate_fee < 0 OR p.candidate_fee >= 9223372036854775808
                OR p.candidate_fee <> trunc(p.candidate_fee)
                OR p.candidate_output IS NULL OR p.candidate_output < 0 OR p.candidate_output <> trunc(p.candidate_output)
                OR p.candidate_block_count IS NULL OR p.candidate_block_count <= 0
                OR p.candidate_tx_count IS NULL OR p.candidate_tx_count < 0
                OR p.candidate_start IS NULL OR p.candidate_end IS NULL OR p.candidate_start > p.candidate_end
                OR p.candidate_max_slot IS NULL OR p.chain_digest IS NULL
                OR (SELECT number FROM yaci_store.block ORDER BY number DESC LIMIT 1)
                    -(SELECT max(number) FROM yaci_store.block WHERE epoch=s.epoch) <= s.security_depth
                OR (SELECT epoch FROM yaci_store.block ORDER BY number DESC LIMIT 1) <= s.epoch) THEN
   RAISE EXCEPTION 'Unproved or mutable epoch candidate';
 END IF;
END
$guard$;
SELECT e.number FROM yaci_store.epoch e JOIN reviewed_epoch_summary_patch p ON p.number=e.number FOR UPDATE OF e;
CREATE TEMP TABLE reviewed_epoch_actual ON COMMIT DROP AS
SELECT b.epoch, count(*)::integer AS block_count, sum(b.no_of_txs)::bigint AS tx_count,
       coalesce(sum(b.total_fees),0)::numeric AS fee, coalesce(sum(b.total_output),0)::numeric AS output,
       min(b.block_time) AS start_time, max(b.block_time) AS end_time, max(b.slot) AS max_slot,
       count(DISTINCT b.number) AS distinct_numbers, count(DISTINCT b.slot) AS distinct_slots,
       min(b.number) AS first_number, max(b.number) AS last_number,
       count(*) FILTER (WHERE b.number IS NULL OR b.slot IS NULL OR b.block_time IS NULL OR b.prev_hash IS NULL
          OR b.no_of_txs IS NULL OR b.no_of_txs < 0 OR b.total_fees IS NULL OR b.total_fees < 0
          OR b.total_output IS NULL OR b.total_output < 0) AS source_errors,
       md5(string_agg(b.number::text || '|' || lower(b.hash) || '|' || b.slot::text || '|'
                      || b.block_time::text || '|' || b.no_of_txs::text || '|'
                      || lower(b.prev_hash), E'\n' ORDER BY b.number,b.hash)) AS chain_digest
FROM yaci_store.block b JOIN reviewed_epoch_summary_patch p ON p.number=b.epoch GROUP BY b.epoch;
DO $source$
BEGIN
 IF (SELECT count(*) FROM yaci_store.epoch e JOIN reviewed_epoch_summary_patch p ON p.number=e.number) <> 1 THEN
   RAISE EXCEPTION 'Reviewed epoch row disappeared';
 END IF;
 IF EXISTS (SELECT 1 FROM reviewed_epoch_summary_patch p JOIN yaci_store.epoch e ON p.number=e.number
             WHERE e.block_count IS DISTINCT FROM p.original_block_count OR e.transaction_count IS DISTINCT FROM p.original_tx_count
               OR e.total_fees IS DISTINCT FROM p.original_fee OR e.total_output IS DISTINCT FROM p.original_output
               OR e.start_time IS DISTINCT FROM p.original_start OR e.end_time IS DISTINCT FROM p.original_end
               OR e.max_slot IS DISTINCT FROM p.original_max_slot) THEN RAISE EXCEPTION 'Reviewed epoch row changed'; END IF;
 IF (SELECT count(*) FROM reviewed_epoch_actual) <> 1 THEN RAISE EXCEPTION 'Reviewed source epoch missing'; END IF;
 IF EXISTS (SELECT 1 FROM reviewed_epoch_summary_patch p JOIN reviewed_epoch_actual a ON a.epoch=p.number
             WHERE a.block_count IS DISTINCT FROM p.candidate_block_count OR a.tx_count IS DISTINCT FROM p.candidate_tx_count
               OR a.source_errors <> 0 OR a.distinct_numbers <> a.block_count OR a.distinct_slots <> a.block_count
               OR a.last_number-a.first_number+1 <> a.block_count
               OR a.fee IS DISTINCT FROM p.candidate_fee OR a.output IS DISTINCT FROM p.candidate_output
               OR a.start_time IS DISTINCT FROM p.candidate_start OR a.end_time IS DISTINCT FROM p.candidate_end
               OR a.max_slot IS DISTINCT FROM p.candidate_max_slot OR a.chain_digest IS DISTINCT FROM p.chain_digest) THEN
   RAISE EXCEPTION 'Reviewed epoch source changed or block repairs incomplete';
 END IF;
END
$source$;
CREATE SCHEMA IF NOT EXISTS bfcompat_epoch_repair_20261013;
CREATE TABLE IF NOT EXISTS bfcompat_epoch_repair_20261013.epoch_before
  (LIKE yaci_store.epoch INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE UNIQUE INDEX IF NOT EXISTS bfcompat_epoch_before_number ON bfcompat_epoch_repair_20261013.epoch_before(number);
INSERT INTO bfcompat_epoch_repair_20261013.epoch_before
SELECT e.* FROM yaci_store.epoch e JOIN reviewed_epoch_summary_patch p ON p.number=e.number
ON CONFLICT (number) DO NOTHING;
DO $patch$
DECLARE changed bigint;
BEGIN
 UPDATE yaci_store.epoch e SET block_count=p.candidate_block_count, transaction_count=p.candidate_tx_count,
     total_fees=p.candidate_fee::bigint, total_output=p.candidate_output,
     start_time=p.candidate_start, end_time=p.candidate_end, max_slot=p.candidate_max_slot, update_datetime=now()
 FROM reviewed_epoch_summary_patch p WHERE e.number=p.number;
 GET DIAGNOSTICS changed = ROW_COUNT;
 IF changed <> 1 THEN RAISE EXCEPTION 'Changed epoch row count mismatch: %', changed; END IF;
END
$patch$;
SELECT e.number,e.block_count,e.transaction_count,e.total_fees,e.total_output,e.start_time,e.end_time,e.max_slot
FROM yaci_store.epoch e JOIN reviewed_epoch_summary_patch p ON p.number=e.number;
COMMIT;
