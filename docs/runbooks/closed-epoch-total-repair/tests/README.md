# PostgreSQL16 repair guard tests

Run from any directory with Python3 and Docker available:

```sh
python3 docs/runbooks/closed-epoch-total-repair/tests/validate.py --output-directory /tmp/yaci-epoch-repair-proof
```

Omit `--output-directory` to use a new temporary directory. All results, fixture copies and per-case stdout/stderr go there, outside the repository. The harness resolves the tested SQL relative to its own file, starts a uniquely named disposable `postgres:16` container with network disabled and tmpfs storage, refuses to replace an existing container, and removes its own container in `finally`. It does not use production credentials or contact production.

`fixture.sql` records the faithful relevant schema types and data constructed by the harness: valid-only and mixed/invalid blocks, Babbage total collateral/return, Alonzo collateral inputs without return, already-correct controls, historical inputs, camelCase invalid bodies and independent db-sync transaction fixtures.

The cases execute actual03/04/06/07/08/09/10SQL through `psql`. They cover valid/invalid applied amounts, successful repair/backup, repeated-manifest refusal and fresh controls, source/digest/finality/unresolved value changes, altered output shape/metadata/body, zero-valued orphan/misplaced rows, fractional manifests and fail-fast table lock contention. Results include exact tested SQL hashes.

Clone success proves guard behavior on fixtures. Before live repair, prove complete source membership against the independent reference and use an approved maintenance window. SHARE locks can block ingestion and lock upgrades can conflict;1s is each lock wait and10s each statement, not total transaction duration. These tests do not establish production schema compatibility, query performance or deadlock freedom.
