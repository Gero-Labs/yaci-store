-- Matching independent epoch/window read. Compare digest with 03 and sums with 04/06.
\set ON_ERROR_STOP on
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL jit = off;
SELECT :e::integer AS epoch, :s0::bigint AS first_slot, :s1::bigint AS last_slot,
       count(*) AS transaction_rows, coalesce(sum(t.fee),0) AS effective_fee_sum,
       coalesce(sum(t.out_sum),0) AS applied_output_sum,
       count(*) FILTER (WHERE t.valid_contract IS FALSE) AS invalid_transactions,
       count(*) FILTER (WHERE t.fee IS NULL OR t.out_sum IS NULL OR t.valid_contract IS NULL
                         OR t.block_index IS NULL) AS null_transaction_fields,
       md5(string_agg(b.block_no::text || '|' || t.block_index::text || '|' || encode(t.hash,'hex') || '|'
                      || b.slot_no::text || '|' || (NOT t.valid_contract)::text || '|' || t.fee::text,
                      E'\n' ORDER BY b.block_no, t.block_index, encode(t.hash,'hex'))) AS effective_transaction_digest
FROM public.block b JOIN public.tx t ON t.block_id = b.id
WHERE b.epoch_no = :e AND b.slot_no BETWEEN :s0 AND :s1;
ROLLBACK;
