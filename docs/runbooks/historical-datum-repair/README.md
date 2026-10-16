# Historical retained datum repair

This dormant operator kit preserves historical rows and the exact stored CBOR.
It adds an alias under `blake2b_256(original_cbor_bytes)` when the retained row is
stored under a reconstructed hash. A null chain slot changes only after an exact
occurrence is proved in that row's own `created_at_tx`. No repair has been applied
to a live database by this work.

The 2026-10-15 mainnet sample has 23 confirmed wrong-key rows with original bytes
retained, nine correct identity rows with null slots, and three unexplained missing
payloads. The sample is evidence of defects, not a complete inventory. The writer
fix prevents new reconstructed hashes; it cannot recreate bytes already lost.

## Schema and provenance

Run `00-catalog.sql` read-only and retain its output before discovery or review.
The pinned source uses `datum(hash varchar(64) PRIMARY KEY,datum text,
created_at_tx varchar(64),slot bigint,create_datetime timestamp,
update_datetime timestamp)`. `transaction_cbor.cbor_data` is `BYTEA`, not JSON.
Confirm primary keys and the indexed `address_utxo.tx_hash` source lookup on each
network. Catalog and bounded source queries use 5s statement and 250ms lock limits.

Two provenance proofs are accepted:

- Inline: the original `created_at_tx` exists at the proposed slot/index, and its
  indexed output at that same slot/index has byte-for-byte identical inline CBOR.
- Witness: original full transaction CBOR is present at the original transaction
  slot. The envelope/body/witnesses are checked, the body byte-span hashes to
  `created_at_tx`, and the datum witness byte-span exactly matches retained CBOR.
  Tagged witness sets are supported; no datum is decoded and re-encoded.

The source may be unavailable or historical transaction CBOR may contain only the
body. Those cases are explicit provenance debt. A newer output with the same asset,
datum or address never proves the original occurrence. An existing identical raw
alias is not overwritten or assigned provenance from its legacy neighbor. A later
discovery of the alias itself must prove its own `created_at_tx` before filling a
null slot.

## Bounded discovery

Use an existing least-privilege PostgreSQL environment (`PGHOST`, `PGPORT`,
`PGDATABASE`, `PGUSER`, an approved password source). The kit never places
credentials in command arguments or evidence. Execute one batch at a time and
inspect its output before continuing; default count25, maximum100.

```sh
python3 docs/runbooks/historical-datum-repair/repair.py discover \
  --network preview --directory /secure/evidence/preview-datum --count 25
```

The primary-key cursor is `hash > cursor ORDER BY hash LIMIT count`. Each batch
contains the complete prior datum row, its SHA256 fingerprint, original bytes,
raw hash, target presence/conflict, and any proven original occurrence or debt.
Content-addressed batch files are fsynced before the atomic cursor replacement;
file and parent-directory fsyncs retain the batch before advancing its cursor.
An interrupted batch can be repeated. A batch filename/hash identifies its exact
JSON values, independent of pretty-print whitespace. Do not edit a reviewed batch.

Scanning `datum` cannot find encodings that collided and were discarded. Separate
discovery-only inventories walk original outputs by `(output_index,tx_hash)` and
witness transactions by `tx_hash`. They page every source row before filtering,
so an absent inline datum cannot cause an unbounded sparse scan. Payloads above
1MiB are marked debt before materialization, each batch reads at most100 rows,
and every raw-key lookup is exact. These inventories do not apply reconstruction.

```sh
python3 docs/runbooks/historical-datum-repair/source_inventory.py \
  --source output --network preview --directory /secure/evidence/preview-output --count 25
python3 docs/runbooks/historical-datum-repair/source_inventory.py \
  --source witness --network preview --directory /secure/evidence/preview-witness --count 25
```

## Review, guarded apply and rollback

Review a small batch, including source completeness, exact bytes/hash, original
occurrence, nullable fields and the catalog/reference audit. Generate SQL first:

```sh
python3 docs/runbooks/historical-datum-repair/repair.py apply \
  --network preview --manifest /secure/evidence/preview-datum/batch-000000-HASH.json \
  --sha256 HASH --sql-output /secure/evidence/preview-datum/reviewed-apply.sql
```

Only format1 discovery manifests are accepted. The approved digest and network
must match; discovery-only historical sample captures are rejected. Eligibility
is a retained wrong key, or a correct raw key with a proved missing slot. Invalid
bytes, conflicts, correct rows and already-existing aliases are excluded. Review
excluded entries and debt; an empty eligible batch is not coverage acceptance.
Apply is capped at100 entries and8MiB of retained/source hexadecimal characters.

After explicit operator approval in a maintenance window, the same command without
`--sql-output` executes a single transaction. Source tables needed by the proofs
are SHARE-locked, bounded by250ms lock timeout; unrelated optional witness tables
are not required for inline proofs. Apply and rollback derive the numeric
`hashtext('datum-repair:' || key)` advisory IDs for old/raw keys, deduplicate
collisions, and acquire them in ascending numeric order before any row writes. Full prior-row JSON must
match, raw-key bytes must agree, and provenance must still match the frozen source.
The inserted alias copies exact CBOR, transaction and timestamps. Legacy rows and
references are preserved. Only a proven legacy null slot is filled. No stored
reference is rekeyed.

The separate `bfcompat_datum_repair_20261015.journal` records the manifest digest,
before/after rows, inserted-alias ownership and old/new slots. Rerun is a no-op
only while current rows exactly match the journal; any later change refuses it.
A source/conflict/lock failure aborts the complete batch. The 5s budget is per
statement, not total transaction duration;100 statements can still block ingestion
across a maintenance transaction. These fixture tests do not prove production
lock freedom or duration.

Rollback SQL is prepared independently:

```sh
python3 docs/runbooks/historical-datum-repair/repair.py rollback \
  --sha256 HASH --sql-output /secure/evidence/preview-datum/reviewed-rollback.sql
```

Rollback requires unchanged full before/after rows. It deletes only an alias that
this journal inserted and restores only its unchanged proven slot. It locks and
checks historical `address_utxo`, optional `address_utxo_unspent`/`address_utxo_current`,
and `transaction_scripts` references. It refuses any unknown table with exact
`data_hash`/`datum_hash` columns. Known partition roots include their descendants.
Before approving rollback, audit catalog foreign keys and custom views/columns or
external consumers whose references use other names: automatic semantic-reference
discovery is impossible. Any uncertain reference means preserve the alias. An
append-only rollback journal makes a repeated rollback a no-op; legacy rows and
both journals remain. Omit `--sql-output` only for explicit reviewed execution.

## Reproducible fixture validation

```sh
./gradlew :extensions:blockfrost:blockfrost-pools:test --tests '*BFHistoricalDatumRepairGuardsTest'
```

The JUnit wrapper creates PostgreSQL16 through Testcontainers with three startup
attempts and a random database marker using pooled JDBC. Before any destructive
fixture reset, Python checks the Testcontainers Docker label and exact marker.
The wrapper proves a wrong token refuses setup. Testcontainers owns cleanup;
Python only inspects/executes that owned container. Production credentials and
databases are never used. Evidence lives in a fresh external temporary directory.

The suite runs 17 Python guard cases through one JUnit wrapper and executes actual
generated apply/rollback SQL. Cases cover original byte
hashes, witness identity, malformed CBOR, inline-only optional-table absence,
provenance changes, conflicts, full-row changes, exact alias preservation, journal
rerun, null provenance debt, current/unspent/unknown references, bounded resumable
source cursors, source byte caps, ascending numeric lock ordering with real
`hashtext` collisions in both apply and rollback, approved digest and sample
format substitution. Tracked `examples/synthetic-manifest.json` contains fake rows
only; its companion digest and test fixture make the reviewed shape reproducible.

## Remaining acceptance

The external `retained-23-discovery-only.json` capture preserves the23 confirmed
original bytes and old/raw identities. Its historical capture omitted timestamps
and has no proved original occurrence. It is explicitly ineligible for apply;
refresh full rows and prove `created_at_tx` instead of inventing either field.

After any approved repair, re-read every exact raw hash/CBOR and its proven slot,
verify journal/rerun behavior, then compare datum JSON/CBOR and affected CIP-68
metadata through the deployed Nexus image against Blockfrost on all three
networks. The nine provenance-only samples and three missing-payload samples must
remain debt until exact source bytes and original provenance are recovered.
No full historical coverage or live parity claim follows from local green tests.
