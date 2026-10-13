-- NOT executed. Reviewed-candidate-only mutation, not source discovery.
-- Inputs: e,s0,s1 (<=10000 slots), k=<actual network security parameter>, reviewed_json='[...]'. Candidate JSON comes from 08,
-- after complete epoch 00..07 validation. Required keys are those selected below.
-- Root must run with an authorized repair role; no credentials belong in reviewed_json.
-- All original/candidate rows retained in a per-network repair backup.
\set ON_ERROR_STOP on
\timing on
BEGIN;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
CREATE TEMP TABLE reviewed_epoch_block_patch ON COMMIT DROP AS
SELECT * FROM jsonb_to_recordset(:'reviewed_json'::jsonb) AS x(
  hash text, number bigint, slot bigint, epoch integer, no_of_txs integer,
  original_fee numeric, original_output numeric, candidate_fee numeric, candidate_output numeric,
  invalid_transactions bigint, transaction_digest text, output_digest text,
  transaction_errors bigint, output_errors bigint, transaction_count_error boolean,
  output_count_error boolean, needs_repair boolean);
ALTER TABLE reviewed_epoch_block_patch ADD PRIMARY KEY(hash);
CREATE TEMP TABLE epoch_repair_window ON COMMIT DROP AS
SELECT :e::integer AS epoch, :s0::bigint AS s0, :s1::bigint AS s1, :k::bigint AS security_depth;

DO $guard$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM reviewed_epoch_block_patch) THEN
   RAISE EXCEPTION 'Empty reviewed block candidate';
 END IF;
 IF EXISTS (SELECT 1 FROM epoch_repair_window WHERE s1 < s0 OR s1-s0 >= 10000) THEN
   RAISE EXCEPTION 'Repair window exceeds reviewed bound';
 END IF;
 IF EXISTS (SELECT 1 FROM reviewed_epoch_block_patch p CROSS JOIN epoch_repair_window w
            WHERE p.epoch IS DISTINCT FROM w.epoch OR p.slot NOT BETWEEN w.s0 AND w.s1
              OR p.invalid_transactions IS NULL OR p.invalid_transactions <= 0
              OR p.transaction_errors IS DISTINCT FROM 0 OR p.output_errors IS DISTINCT FROM 0
              OR p.transaction_count_error IS DISTINCT FROM false OR p.output_count_error IS DISTINCT FROM false
              OR p.hash IS NULL OR p.number IS NULL OR p.slot IS NULL OR p.no_of_txs IS NULL
              OR p.original_fee IS NULL OR p.original_output IS NULL OR p.candidate_fee IS NULL OR p.candidate_output IS NULL
              OR p.candidate_fee < 0 OR p.candidate_output < 0
              OR p.candidate_fee >= 9223372036854775808 OR p.candidate_fee <> trunc(p.candidate_fee)
              OR p.candidate_output <> trunc(p.candidate_output)
              OR p.transaction_digest IS NULL OR p.output_digest IS NULL
              OR p.needs_repair IS DISTINCT FROM
                  (p.original_fee IS DISTINCT FROM p.candidate_fee OR p.original_output IS DISTINCT FROM p.candidate_output)) THEN
   RAISE EXCEPTION 'Unproved or malformed reviewed block candidate';
 END IF;
 IF EXISTS (SELECT 1 FROM epoch_repair_window w
             WHERE (SELECT epoch FROM yaci_store.block ORDER BY number DESC LIMIT 1) <= w.epoch) THEN
   RAISE EXCEPTION 'Epoch not closed';
 END IF;
 IF EXISTS (SELECT 1 FROM epoch_repair_window w
             WHERE w.security_depth <= 0 OR w.security_depth IS NULL
               OR (SELECT number FROM yaci_store.block ORDER BY number DESC LIMIT 1)
                  - (SELECT max(number) FROM yaci_store.block WHERE epoch=w.epoch) <= w.security_depth) THEN
   RAISE EXCEPTION 'Epoch remains within network security depth';
 END IF;
END
$guard$;

-- Lock only reviewed canonical block rows. Close-source proof must have established
-- immutable depth and no rebuild/rollback. Optimistic comparisons prevent stale candidates.
SELECT b.hash FROM yaci_store.block b JOIN reviewed_epoch_block_patch p ON p.hash = b.hash
ORDER BY b.number FOR UPDATE OF b;
DO $source$
BEGIN
 IF (SELECT count(*) FROM yaci_store.block b JOIN reviewed_epoch_block_patch p ON p.hash=b.hash)
       <> (SELECT count(*) FROM reviewed_epoch_block_patch) THEN
   RAISE EXCEPTION 'Reviewed block disappeared';
 END IF;
 IF EXISTS (SELECT 1 FROM reviewed_epoch_block_patch p JOIN yaci_store.block b ON b.hash=p.hash
             WHERE b.number IS DISTINCT FROM p.number OR b.slot IS DISTINCT FROM p.slot OR b.epoch IS DISTINCT FROM p.epoch
                OR b.no_of_txs IS DISTINCT FROM p.no_of_txs OR b.total_fees IS DISTINCT FROM p.original_fee
                OR b.total_output IS DISTINCT FROM p.original_output) THEN
   RAISE EXCEPTION 'Reviewed block source changed';
 END IF;
 IF EXISTS (
   SELECT 1 FROM reviewed_epoch_block_patch p
   CROSS JOIN LATERAL (
     SELECT count(*) AS cnt, count(*) FILTER (WHERE invalid IS TRUE) AS invalids,
            coalesce(sum(fee),0) AS fee,
            md5(string_agg(tx_hash || '|' || tx_index::text || '|' || invalid::text || '|' || fee::text,
                            E'\n' ORDER BY tx_index, tx_hash)) AS digest
     FROM yaci_store.transaction t WHERE t.slot=p.slot AND t.block_hash=p.hash
   ) t
   CROSS JOIN LATERAL (
     SELECT coalesce(sum(lovelace_amount),0) AS output,
            coalesce(md5(string_agg(tx_hash || '|' || output_index::text || '|' || lovelace_amount::text || '|'
                      || coalesce(is_collateral_return::text,'null'), E'\n' ORDER BY tx_hash,output_index)),md5('')) AS digest
     FROM yaci_store.address_utxo u WHERE u.slot=p.slot AND u.block_hash=p.hash
   ) u
   WHERE t.cnt IS DISTINCT FROM p.no_of_txs OR t.invalids IS DISTINCT FROM p.invalid_transactions
     OR t.fee IS DISTINCT FROM p.candidate_fee OR u.output IS DISTINCT FROM p.candidate_output
     OR t.digest IS DISTINCT FROM p.transaction_digest OR u.digest IS DISTINCT FROM p.output_digest
 ) THEN RAISE EXCEPTION 'Reviewed transaction or output source changed'; END IF;
END
$source$;

CREATE SCHEMA IF NOT EXISTS bfcompat_epoch_repair_20261013;
CREATE TABLE IF NOT EXISTS bfcompat_epoch_repair_20261013.block_before
  (LIKE yaci_store.block INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE UNIQUE INDEX IF NOT EXISTS bfcompat_epoch_block_before_hash
  ON bfcompat_epoch_repair_20261013.block_before(hash);
INSERT INTO bfcompat_epoch_repair_20261013.block_before
SELECT b.* FROM yaci_store.block b JOIN reviewed_epoch_block_patch p ON p.hash=b.hash
WHERE p.needs_repair IS TRUE
ON CONFLICT (hash) DO NOTHING;

DO $patch$
DECLARE changed bigint;
BEGIN
 UPDATE yaci_store.block b SET total_fees=p.candidate_fee::bigint,
       total_output=p.candidate_output, update_datetime=now()
 FROM reviewed_epoch_block_patch p
 WHERE p.needs_repair IS TRUE AND b.hash=p.hash;
 GET DIAGNOSTICS changed = ROW_COUNT;
 IF changed <> (SELECT count(*) FROM reviewed_epoch_block_patch WHERE needs_repair IS TRUE) THEN
   RAISE EXCEPTION 'Changed block row count mismatch: %', changed;
 END IF;
 RAISE NOTICE 'Changed block rows: %', changed;
END
$patch$;
DO $verified$
BEGIN
 IF EXISTS (SELECT 1 FROM reviewed_epoch_block_patch p JOIN yaci_store.block b ON b.hash=p.hash
             WHERE b.total_fees IS DISTINCT FROM p.candidate_fee OR b.total_output IS DISTINCT FROM p.candidate_output) THEN
   RAISE EXCEPTION 'Block patch verification failed';
 END IF;
END
$verified$;
COMMIT;
