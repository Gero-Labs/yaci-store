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
  invalid_transactions bigint, transaction_digest text, output_digest text, source_digest text, scope_digest text,
  transaction_errors bigint, output_errors bigint, transaction_count_error boolean,
  output_count_error boolean, needs_repair boolean);
ALTER TABLE reviewed_epoch_block_patch ADD PRIMARY KEY(hash);
CREATE TEMP TABLE epoch_repair_window ON COMMIT DROP AS
SELECT :e::integer AS epoch, :s0::bigint AS s0, :s1::bigint AS s1, :k::bigint AS security_depth;

-- Strict source stability includes phantom inserts. Row locks alone cannot prove this.
-- Requires an approved maintenance window: SHARE conflicts with ingestion writes.
-- Every repair uses this table lock order before row locks; fail after 1s of lock wait.
-- statement_timeout bounds each statement, not the complete transaction wall time.
LOCK TABLE yaci_store.block, yaci_store.transaction, yaci_store.address_utxo,
  yaci_store.invalid_transaction IN SHARE MODE;
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
              OR p.transaction_digest IS NULL OR p.output_digest IS NULL OR p.source_digest IS NULL OR p.scope_digest IS NULL
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

-- Compare the complete bounded membership scope, including entirely orphan rows.
CREATE TEMP TABLE reviewed_window_actual ON COMMIT DROP AS
WITH b AS MATERIALIZED (
 SELECT bs.hash FROM yaci_store.block bs CROSS JOIN epoch_repair_window w
 WHERE bs.epoch=w.epoch AND bs.slot BETWEEN w.s0 AND w.s1
), scope_transactions AS MATERIALIZED (
 SELECT ts.* FROM yaci_store.transaction ts CROSS JOIN epoch_repair_window w
 WHERE ts.slot BETWEEN w.s0 AND w.s1
 UNION ALL
 SELECT ts.* FROM b JOIN yaci_store.transaction ts ON ts.block_hash=b.hash CROSS JOIN epoch_repair_window w
 WHERE (ts.slot BETWEEN w.s0 AND w.s1) IS NOT TRUE
), scope_outputs AS MATERIALIZED (
 SELECT us.* FROM yaci_store.address_utxo us CROSS JOIN epoch_repair_window w WHERE us.slot BETWEEN w.s0 AND w.s1
 UNION ALL
 SELECT us.* FROM scope_transactions ts JOIN yaci_store.address_utxo us ON us.tx_hash=ts.tx_hash
 CROSS JOIN epoch_repair_window w WHERE (us.slot BETWEEN w.s0 AND w.s1) IS NOT TRUE
), scope_logs AS MATERIALIZED (
 SELECT ins.* FROM yaci_store.invalid_transaction ins CROSS JOIN epoch_repair_window w WHERE ins.slot BETWEEN w.s0 AND w.s1
 UNION ALL
 SELECT ins.* FROM scope_transactions ts JOIN yaci_store.invalid_transaction ins ON ins.tx_hash=ts.tx_hash
 CROSS JOIN epoch_repair_window w WHERE (ins.slot BETWEEN w.s0 AND w.s1) IS NOT TRUE
)
SELECT md5(jsonb_build_object(
 'blocks', coalesce((SELECT jsonb_agg(to_jsonb(bs) ORDER BY bs.number,bs.hash)
    FROM yaci_store.block bs CROSS JOIN epoch_repair_window w WHERE bs.slot BETWEEN w.s0 AND w.s1),'[]'::jsonb),
 'transactions', coalesce((SELECT jsonb_agg(to_jsonb(ts) ORDER BY ts.tx_hash)
    FROM scope_transactions ts),'[]'::jsonb),
 'outputs', coalesce((SELECT jsonb_agg(to_jsonb(us) ORDER BY us.tx_hash,us.output_index)
    FROM scope_outputs us),'[]'::jsonb),
 'invalid_logs', coalesce((SELECT jsonb_agg(to_jsonb(ins) ORDER BY ins.tx_hash)
    FROM scope_logs ins),'[]'::jsonb)
)::text) AS scope_digest;
DO $scope$
BEGIN
 IF EXISTS (SELECT 1 FROM reviewed_epoch_block_patch p CROSS JOIN reviewed_window_actual a
             WHERE p.scope_digest IS DISTINCT FROM a.scope_digest) THEN
   RAISE EXCEPTION 'Reviewed window membership source changed';
 END IF;
END
$scope$;

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
   CROSS JOIN LATERAL (
     SELECT md5(jsonb_build_object(
       'block', (SELECT to_jsonb(bs) FROM yaci_store.block bs WHERE bs.hash=p.hash),
       'transactions', coalesce((SELECT jsonb_agg(to_jsonb(ts) ORDER BY ts.tx_index,ts.tx_hash)
          FROM yaci_store.transaction ts WHERE ts.block_hash=p.hash),'[]'::jsonb),
       'outputs', coalesce((SELECT jsonb_agg(to_jsonb(us) ORDER BY us.tx_hash,us.output_index)
          FROM (
            SELECT us.* FROM yaci_store.address_utxo us WHERE us.slot=p.slot AND us.block_hash=p.hash
            UNION ALL
            SELECT us.* FROM yaci_store.transaction ts JOIN yaci_store.address_utxo us ON us.tx_hash=ts.tx_hash
            WHERE ts.block_hash=p.hash AND (us.slot=p.slot AND us.block_hash=p.hash) IS NOT TRUE
          ) us),'[]'::jsonb),
       'invalid_logs', coalesce((SELECT jsonb_agg(to_jsonb(ins) ORDER BY ins.tx_hash)
          FROM (
            SELECT ins.* FROM yaci_store.invalid_transaction ins WHERE ins.slot=p.slot AND ins.block_hash=p.hash
            UNION ALL
            SELECT ins.* FROM yaci_store.transaction ts JOIN yaci_store.invalid_transaction ins ON ins.tx_hash=ts.tx_hash
            WHERE ts.block_hash=p.hash AND (ins.slot=p.slot AND ins.block_hash=p.hash) IS NOT TRUE
          ) ins),'[]'::jsonb),
       'collateral_sources', coalesce((SELECT jsonb_agg(to_jsonb(cs) ORDER BY cs.tx_hash,cs.output_index)
          FROM yaci_store.address_utxo cs JOIN (
            SELECT DISTINCT key->>'tx_hash' AS tx_hash,
              CASE WHEN key->>'output_index' ~ '^[0-9]+$' THEN (key->>'output_index')::integer END AS output_index
            FROM yaci_store.transaction ts CROSS JOIN LATERAL jsonb_array_elements(
              CASE WHEN jsonb_typeof(ts.collateral_inputs)='array' THEN ts.collateral_inputs ELSE '[]'::jsonb END) key
            WHERE ts.block_hash=p.hash AND ts.invalid IS TRUE
          ) requested ON cs.tx_hash=requested.tx_hash AND cs.output_index=requested.output_index),'[]'::jsonb)
     )::text) AS source_digest
   ) proof
   WHERE t.cnt IS DISTINCT FROM p.no_of_txs OR t.invalids IS DISTINCT FROM p.invalid_transactions
     OR t.fee IS DISTINCT FROM p.candidate_fee OR u.output IS DISTINCT FROM p.candidate_output
     OR t.digest IS DISTINCT FROM p.transaction_digest OR u.digest IS DISTINCT FROM p.output_digest
     OR proof.source_digest IS DISTINCT FROM p.source_digest
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
