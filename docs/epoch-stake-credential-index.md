# Epoch stake credential-order index

Blockfrost stake pages filter snapshot epoch `active_epoch - 2` and positive
amounts, then order scripts before keys and credentials by their raw bytes.
The address primary key cannot supply that order. `PostgresPartitionManager`
creates a partial expression index on each new empty epoch-stake partition,
before moving default-partition rows or allowing snapshot inserts. It leaves
existing partitions untouched. No parent index or boot-time historical scan
is added, and reader timeouts and ordering stay the same.

The index's four expressions must remain identical to the PostgreSQL branch
of `BFEpochStorageReaderImpl.credentialOrder()`. The real PostgreSQL test
executes that reader and requires a natural `Limit -> Index Scan` without a
sort or planner overrides. The predicate is `amount > 0`; the index has no
payload `INCLUDE` columns.

## Existing populated partitions

Existing leaves require a separately controlled `CREATE INDEX CONCURRENTLY`
operation. This document is a template, not an application migration or a
bulk-build script. Pick one network and one leaf at a time, with its verified
schema, epoch bounds, approved build timeout, and current maintenance owner.
Do not run the operation through the application transaction or on the parent.

Before submitting a build, retain a bounded read-only catalog report showing:

- The selected relation is a leaf directly attached to that network's
  `epoch_stake`, with the expected one-epoch bound.
- Its existing indexes, expressions, partial predicates, validity/readiness,
  relation sizes and available disk space.
- The deployed reader uses the same four expressions and all expression
  functions are immutable on that PostgreSQL version.
- No previous build or maintenance owner is still active; writer and long
  transaction activity are understood before choosing the build window.

With psql variables `schema`, `leaf`, and `index` set to the verified names,
these catalog queries do not scan stake rows:

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '3s';
SELECT c.oid::regclass AS leaf, c.relkind, c.relispartition,
       pg_get_expr(c.relpartbound, c.oid) AS bound,
       p.inhparent::regclass AS parent,
       pg_relation_size(c.oid) AS heap_bytes
FROM pg_class c JOIN pg_inherits p ON p.inhrelid = c.oid
WHERE c.oid = format('%I.%I', :'schema', :'leaf')::regclass;
SELECT x.indexrelid::regclass AS name, x.indisvalid, x.indisready,
       pg_get_indexdef(x.indexrelid) AS definition,
       pg_get_expr(x.indpred, x.indrelid) AS predicate,
       pg_relation_size(x.indexrelid) AS index_bytes
FROM pg_index x
WHERE x.indrelid = format('%I.%I', :'schema', :'leaf')::regclass;
COMMIT;
```

Execute the following only in a dedicated autocommit maintenance connection,
using the approved `build_timeout` variable. `CONCURRENTLY` permits writers,
but still consumes CPU, I/O and disk and can wait for old transactions. Keep
the existing API query budget unchanged.

```sql
SET lock_timeout = '3s';
SET statement_timeout = :'build_timeout';
CREATE INDEX CONCURRENTLY :"index" ON :"schema".:"leaf" (
  (CASE WHEN substring(substring(lower(address), position('1' in lower(address)) + 1, 47), 1, 1) = '7'
        THEN 0 ELSE 1 END),
  (mod(position(substring(substring(lower(address), position('1' in lower(address)) + 1, 47), 2, 1)
        in 'qpzry9x8gf2tvdw0s3jn54khce6mua7l') - 1, 4)),
  (translate(substring(substring(lower(address), position('1' in lower(address)) + 1, 47), 3, 45),
        'qpzry9x8gf2tvdw0s3jn54khce6mua7l', '0123456789abcdefghijklmnopqrstuv')::bytea),
  (address::bytea)
) WHERE amount > 0;
```

After completion, retain the catalog report again and require `indisvalid`
and `indisready`. Use the actual deployed reader's SQL for bounded
`EXPLAIN (ANALYZE, BUFFERS)` and live page comparison. Check exact row order
and page boundaries as well as timing; an index alone does not prove parity.

A timed-out or cancelled concurrent build can leave an invalid index. Inspect
the catalog and active backend first. Do not conceal it with `IF NOT EXISTS`,
start another build while its owner is active, or kill the database process.
An explicitly owned cleanup uses `DROP INDEX CONCURRENTLY` on that confirmed
leaf index in autocommit mode before a separately scheduled retry. Removing
the new index restores the previous plan without changing stake data.

## Local verification

Use an owned disposable PostgreSQL server listening on `127.0.0.1` and JDK 21:

```sh
YACI_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5433/postgres \
  ./gradlew :extensions:blockfrost:blockfrost-epoch:test \
  --tests '*BFEpochStakeOrderTest' \
  --tests '*PostgresEpochStakeCredentialIndexTest' \
  --offline --max-workers=2 --no-daemon
```

The PostgreSQL test is annotation-gated when the variable is absent and only
accepts a loopback URL. It creates and drops a unique schema. Use a disposable
server with the test's local `postgres` user, never an indexer database.
