-- Read-only catalog evidence. Never scan retained payloads or production tables.
BEGIN READ ONLY;
SET LOCAL statement_timeout='5s';
SET LOCAL lock_timeout='250ms';
SET LOCAL jit=off;

SELECT n.nspname,c.relname,c.relkind,a.attname,format_type(a.atttypid,a.atttypmod) AS type,a.attnotnull
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
JOIN pg_attribute a ON a.attrelid=c.oid
WHERE n.nspname='yaci_store' AND a.attnum>0 AND NOT a.attisdropped
AND c.relname IN('datum','transaction','transaction_cbor','address_utxo','address_utxo_unspent','address_utxo_current','transaction_scripts')
ORDER BY c.relname,a.attnum;

SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='yaci_store'
AND tablename IN('datum','transaction','transaction_cbor','address_utxo','address_utxo_unspent','address_utxo_current','transaction_scripts')
ORDER BY tablename,indexname;

-- Semantic references have no FK in the pinned source; audit both name candidates
-- and actual foreign keys. The operator must review any additional/custom path.
SELECT c.relname,a.attname,format_type(a.atttypid,a.atttypmod) AS type
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
JOIN pg_attribute a ON a.attrelid=c.oid
WHERE n.nspname='yaci_store' AND c.relkind IN('r','p','v','m')
AND a.attnum>0 AND NOT a.attisdropped AND a.attname ILIKE '%datum%'
ORDER BY c.relname,a.attname;

SELECT conrelid::regclass AS referencing_table,conname,pg_get_constraintdef(oid)
FROM pg_constraint WHERE contype='f' AND confrelid=to_regclass('yaci_store.datum');

SELECT child.oid::regclass AS child,parent.oid::regclass AS parent
FROM pg_inherits i JOIN pg_class child ON child.oid=i.inhrelid
JOIN pg_class parent ON parent.oid=i.inhparent JOIN pg_namespace n ON n.oid=parent.relnamespace
WHERE n.nspname='yaci_store' AND parent.relname IN('address_utxo','address_utxo_unspent','address_utxo_current','transaction_scripts');
COMMIT;
