# Gero production Blockfrost backports

This branch preserves the production Yaci Store baseline: `cf1b0d41febe8b4591f4e3f3ab3bd4478abda94a`
(upstream `3.0.0-beta4` plus the balance rollback fix). It carries the reviewed
upstream changes from PRs [#1253](https://github.com/bloxbean/yaci-store/pull/1253),
[#1255](https://github.com/bloxbean/yaci-store/pull/1255), and
[#1254](https://github.com/bloxbean/yaci-store/pull/1254): unordered epoch bounds,
slot-bounded block-address outputs, and the optional `block(slot_leader, number)` index.
The PR targets `fix/balance-rollback-latest-row`, whose newer `2eb995ad1` commit
adds rollback regression tests. Those tests are retained; account runtime code is
unchanged. The build workflow also covers PRs into this production baseline branch.

The current production image, `registry.gerowallet.io/gero/yaci-store:3.0.0-beta4-cf1b0d4-y1`,
also contains a separately patched `yaci-core-0.4.6.jar`. Its
`TransactionBodyExtractor.getLength` ONE_BYTE branch returns `symbols[0] & 0xFF`.
Using the unmodified Maven dependency would lose all transactions in rare blocks
whose definite-length transaction array has 128–255 entries. Building this branch
alone does **not** retain that patch.

## Build without losing the production parser fix

Use JDK 21 and run the module tests before building:

```sh
./gradlew :aggregates:epoch-aggr:test \
  :extensions:blockfrost:blockfrost-block:test \
  :components:dbutils:test -PskipSigning=true --max-workers=4
./gradlew :applications:all:bootJar -PskipSigning=true --max-workers=4
```

Obtain `/app/yaci-store.jar` from the exact current production image into a local
file. Set `GERO_DEPLOYED_YACI_JAR` to that file's absolute path, then create a
separate image-context JAR:

```sh
python3 scripts/production/preserve-yaci-core.py \
  --build-jar applications/all/build/libs/yaci-store-3.0.0-beta4.jar \
  --deployed-jar "$GERO_DEPLOYED_YACI_JAR" \
  --output build/production/yaci-store.jar
```

The helper verifies the production nested dependency against SHA256
`e48f263bfc09df714de1945a0c35496570161b82ec3ccac20204b745bc9f4669`, copies it
with ZIP method STORED as required by Spring Boot, and confirms every other
archive entry is unchanged. It fails on an unexpected or duplicated dependency
and does not overwrite either input. Record its output JAR hash with the image
tag and this branch's Git commit.

The replacement image recipe retains the current base image and replaces only
the prepared application JAR:

```dockerfile
FROM registry.gerowallet.io/gero/yaci-store:3.0.0-beta4-cf1b0d4-y1
COPY yaci-store.jar /app/yaci-store.jar
```

This document prepares the build; it does not build, push, or roll out an image.
The Git-properties plugin can report the main checkout's commit in a worktree;
record the actual source commit separately.

## Database and rollout requirements

The pool-block index is optional and an upgraded JAR does not create it.
Plan its creation separately on each target Yaci database. With a running writer,
use reviewed `CREATE INDEX CONCURRENTLY` maintenance: the optional-index tooling
uses plain `CREATE INDEX`, which blocks writes, so use it only with the writer
stopped. Verify the index's
validity and an ordered index scan for both page directions before declaring
the pool-block timeout fixed. This branch performs no live database changes.
Concrete concurrent maintenance SQL and suspended per-network Jobs are prepared
in the Nexus repository under `k8s/yaci/yaci-pool-block-index-bootstrap*`; follow
its `k8s/koios/README.md` maintenance coordination and acceptance steps.

Epoch-bound recomputation corrects first/last time and maximum slot when the
aggregate is next saved. It does not repair fees/output already stored incorrectly
on historical blocks. Current-epoch freshness also depends on the deployed
epoch-calculation interval and any downstream cache; this backport does not
change either setting.

Coordinate any indexer restart with the owner of the mainnet CBOR backfill and
watchdog. Startup rollbacks can delete newly backfilled CBOR. After an authorized
rollout, check fresh block and epoch tips, recent CBOR coverage, and the original
Blockfrost comparison fixtures before considering the change live.
