-- Read only. Run on the selected Yaci database before any data probe.
\set ON_ERROR_STOP on
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL max_parallel_workers_per_gather = 0;
SELECT current_database(), current_user, current_setting('transaction_read_only');
SELECT table_name, column_name, data_type, udt_name
FROM information_schema.columns
WHERE table_schema = 'yaci_store'
  AND table_name IN ('block', 'epoch', 'transaction', 'invalid_transaction', 'address_utxo', 'tx_input')
ORDER BY table_name, ordinal_position;
SELECT c.relname AS table_name, ix.relname AS index_name,
       i.indisvalid, i.indisready, i.indislive, pg_get_indexdef(i.indexrelid) AS definition
FROM pg_index i
JOIN pg_class c ON c.oid = i.indrelid
JOIN pg_class ix ON ix.oid = i.indexrelid
WHERE c.relnamespace = 'yaci_store'::regnamespace
  AND c.relname IN ('block', 'epoch', 'transaction', 'invalid_transaction', 'address_utxo', 'tx_input')
ORDER BY c.relname, ix.relname;
-- A recent row makes closure a cheap number-index edge read.
SELECT number, epoch, slot, block_time FROM yaci_store.block ORDER BY number DESC LIMIT 1;
ROLLBACK;
