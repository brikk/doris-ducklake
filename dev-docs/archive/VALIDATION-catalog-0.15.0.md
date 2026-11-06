# Catalog 0.15.0 adoption — 2026-11-04

**Historical, failed adoption attempt.** Targeted checks found a release defect in
the new exception's progress count. The owner subsequently requested
[0.15.1 adoption](VALIDATION-catalog-0.15.1.md), which fixes that defect. The 0.15.0
results below are preserved, not relabeled as a passing run.

Owner-directed adoption of formal **0.15.0**, release commit
`948bf0744480d74595703a8c9b42dbc5abe8e937`, tag `v0.15.0`.
Catalog, fixtures, replay and SLT now use released 0.15.0 normally. JDBC 2.0.0.1,
Doris API27 SDK/FE/BE pins, stored-format 1.0 admission, corpus pin and feature
opt-ins are unchanged. No 1.1 write admission or historical-reader promotion.

**Validation scope is deliberately limited by the owner:** only the three changes
since the [completed snapshot trial](TRIAL-catalog-0.15.0-20261104.182540-2.md),
maintenance/procedure tests, compilation and quick smoke/package checks. No full
suite or corpus re-verification, duplicate CI matrix or FE/BE deployment. The
snapshot's 319/276 normal-suite passes remain snapshot evidence, not release counts.

## Published artifacts

Independently downloaded from `https://repo.maven.apache.org/maven2/` and matched
to all supplied SHA256 values. The normal Gradle resolution/package is also checked
against those bytes, without the removed timestamp-snapshot trial init script.

```text
17841c0cbbbcbc28a0d3d32405cfca77ece9f91a1696ea0702acd0899d6e1274 ducklake-catalog-0.15.0.jar
ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f ducklake-catalog-0.15.0-test-fixtures.jar
3b3ddd334e9eb9cd971062cc2b19b1b2d7ee535538c46f005b5db01277300c11 ducklake-test-corpus-replay-0.15.0.jar
ee37d13d863437eaaf36fe4c34f8d66cfa1cb6d1349ef661ae4f50d2e91120be slt-format-0.15.0.jar
```

Only the catalog JAR changed from the trial; the other three retain their hashes.
Downloads and pre-adoption default ZIP/receipt copies are retained under
`build/validation/catalog-0.15.0/`.

## The three post-trial changes

1. **Committed expiry / failed cleanup is typed.** The released procedure adapter
   catches `DucklakeExpiryCleanupIncompleteException`, preserves it as the cause
   with its expired/scheduled counts, and explicitly says **do not retry expiry**.
   Recovery is `DucklakeCatalog.cleanUpExpiredObjects`, not physical
   `cleanup_old_files`. No automatic re-expiry or new SQL procedure is introduced.
   The historical candidate keeps a trivial API-compatible adapter and its old
   dependency lane; it is not re-qualified in this adoption.
2. **Cleanup budgets are hard.** The folded release tests replace the trial's soft
   overshoot expectation. They check table isolation and a one-file budget against
   a data file with five delete files: every call schedules at most one file and
   bounded repetition must complete without scheduling latest/history-live files.
3. **Old result-copy ABI links again.** The unchanged Java17 client bytecode from
   the trial, compiled against published 0.14.0, now passes its constructor and
   `copy(int,int)` calls with the release JAR. Release tests also invoke the old
   Kotlin default bridge with each mask and preserve `cleanupComplete=false`.

The useful native PostgreSQL/MinIO trial fixtures are now ordinary released-lane
tests: `DuckLakeExpiryMaintenanceTest`, `DuckLakeExpiryStorageTest`, and their
owned `DuckLakeExpiryFixture`. There is no remaining `test/catalog-015` source
lane or snapshot dependency override. The historical trial report retains its
original pre-fix findings and is clearly marked superseded by adoption.

## Targeted checks

Selected on each usual JVM lane, without running other tests:

- `DuckLakeProcedureOpsTest` and `DuckLakeCleanupOldFilesTest` — existing contracts.
- `DuckLakeExpiryMaintenanceTest` and `DuckLakeExpiryStorageTest` — native paged
  expiry, real page-two failure/recovery, hard budgets, history/table safety,
  copy/default bridges and real duplicate/missing MinIO deletion.
- `DorisBaselineContractTest` and `DuckLakeConnectorCapabilitiesTest` — quick
  API27/wire/connector startup-capability smoke, no new capabilities.

Compilation, Detekt, assembly, Java17 runtime/package scanning and resolved
dependency recording accompany the selected tests. Three cluster-free release
bundle-guard tests cover the updated compose admission pin. No live controller is
run. Heavy work is serial in one private 6 GiB/no-swap/300% CPU scope with one
Gradle worker and no retained daemon.

### Results and confirmed defect

- **Java25: 41 passed, 1 failed, 0 skipped**; **Java17: 41 passed, 1 failed,
  0 skipped**. These are the 42 selected cases on each JVM, not full suites.
- The failure is the new exception's `scheduledFileCount`: after a PostgreSQL
  page-two DELETE failure it reports **1,002**, but only **1,000** queued rows
  committed. The last two scheduling inserts rolled back with their failed page.
- A repeat of **only that failure/recovery case** on each JVM confirms all state
  and recovery assertions before retaining the failing count assertion:
  `EXPIRY_PROGRESS reported=1002 committed=1000 resumed=2 latest=4`.
  Snapshot deletion stays committed, the next cleanup schedules exactly the two
  remaining files, and latest files remain unscheduled. No data-loss or cleanup
  recovery failure was observed in these controls.
- Published source explains the mismatch: `scheduleFiles` increments `gc.scheduled`
  inside the transaction before its DELETE/commit succeeds. Rollback does not undo
  that in-memory increment; the expiry catch copies it into the new typed error.
  This is a library counter defect, not a reason to weaken the regression to 1,002.
- Hard-budget/progress tests and legacy constructor/copy/default-bridge checks
  pass. The unchanged precompiled 0.14 Java17 client now links with release 0.15.0.
- Compilation, Detekt, assembly and the Java17 runtime/package scan (**24,769
  classes**) pass. All four resolved Central JARs and the bundled catalog match
  the supplied hashes. **3/3** cluster-free bundle guards pass.

Exact logs, JUnit counts, source fingerprints, artifact checks and cleanup receipts
are under `build/validation/catalog-0.15.0/`. `first-targeted-results/` retains
the original 42-case XMLs; `confirmed-expiry-count-defect.json` preserves both
single-case confirmation results. Both scopes are stopped and no containers remain
running. The locally built default ZIP now contains 0.15.0, with its 0.14 predecessor
retained in `previous-default/`; SDKs/images/retained deployments are not changed.
No full suite, corpus or FE/BE deployment ran. No commit or push was made after the
failed targeted check.

This adoption does not resolve the previous red full corpus, maintenance authority
finding, distributed inline transport, proper historical-delete filtering or other
production/read/write qualification limits. No skip, golden or assertion was
weakened to claim compatibility.
