# PostgreSQL16 repair guard tests

Run the Testcontainers-owned fixture from the repository root:

```sh
./gradlew :extensions:blockfrost:blockfrost-pools:test --tests '*BFClosedEpochRepairGuardsTest'
```

The JUnit test starts PostgreSQL 16 through Testcontainers with three startup
attempts and uses pooled JDBC to create a random fixture marker. It invokes
`validate.py` with the container ID and generated marker in the environment.
The script refuses destructive fixture setup unless Docker confirms
Testcontainers ownership and the exact database marker matches. It uses only
`docker inspect` and `docker exec`; Testcontainers owns container cleanup.
No production credentials or production database are used. Each run writes
SQL hashes, fixture copies, results and per-case stdout/stderr to a new temporary
evidence directory outside the repository, printed by the test.

`fixture.sql` records the faithful relevant schema types and data constructed by the harness: valid-only and mixed/invalid blocks, Babbage total collateral/return, Alonzo collateral inputs without return, already-correct controls, historical inputs, camelCase invalid bodies and independent db-sync transaction fixtures.

The cases execute actual03/04/06/07/08/09/10SQL through `psql`. They cover valid/invalid applied amounts, successful repair/backup, repeated-manifest refusal and fresh controls, source/digest/finality/unresolved value changes, altered output shape/metadata/body, zero-valued orphan/misplaced rows, fractional manifests and fail-fast table lock contention. Pre-review fixtures seed malformed linked outputs outside the canonical bounds,NULL slots, wrong transaction metadata and orphan wrong-epoch rows before08; these must produce nonzero expanded-scope errors and refuse09. A historical collateral-input mutation outside the window exercises `source_digest` independently of `scope_digest`. The06 baseline asserts all semantic counters and expected deltas, rather than only SQL execution. Results include exact tested SQL hashes.

Clone success proves guard behavior on fixtures. Before live repair, prove complete source membership against the independent reference and use an approved maintenance window. SHARE locks can block ingestion and lock upgrades can conflict;1s is each lock wait and10s each statement, not total transaction duration. These tests do not establish production schema compatibility, query performance or deadlock freedom.
