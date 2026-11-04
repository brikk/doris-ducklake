# Catalog 0.14.0 adoption and validation

Historical pre-fix artifact baseline. Subsequent user-authorized local changes and
their separately identified validation are in
[Doris read safety](VALIDATION-DORIS-READ-SAFETY-2026-11-04.md).

## Release review and consumer decisions

Maven Central identifies **0.14.0** as the new release. Reviewed the published
release notes, compatibility contract and Trino's accepted consumer changes.
The 0.13.2 update was direct-Quack qualification only (not used here); 0.14.0 has
the substantive catalog/replay changes relevant to this connector.

- **Stored-format admission:** released Doris explicitly configures
  `requiredCatalogFormat = V1_0` (stored 1.0 / layout-identical 0.4). The library
  checks reads in their own view, not only at connector construction, so a later
  native migration is refused. `getSpecVersion` stays available for diagnostics.
  No migration, format-string edits, 1.1 write admission or experimental-reader
  promotion is part of this upgrade.
- **Independent replay lanes:** the released factory selects 1.0 setup, execution
  and mirror scopes. Explicit 1.1 files halt before executing their feature SQL;
  they are counted as scope halts, not successful files or new blanket exclusions.
- **PostgreSQL oracle SQL binding:** known rewritten metadata catalogs' schema
  qualifiers are rebound structurally from `main` to `public`. Logical schemas,
  custom metadata schemas, literal values and original corpus/golden files stay
  unchanged. Backend rewriting retains its previous all-ATTACH policy; the new
  CONNECTION_ENV_ONLY option is not silently substituted for it.
- **Reconnect correctness:** the Doris replay adapter now selects an acknowledged
  target from the attachment URI, not whichever database was most recently
  allocated. A/B/A reattachments and foreign-target refusal have regressions.
- **Other changes:** drop-time statistics cleanup is inherited. Recommended indexes
  remain explicit opt-in DDL and are not installed automatically; production pool
  defaults remain unchanged. JDBC stays 2.0.0.1; Quack is not added. Doris SDK/API27
  and native image pins are unchanged; the historical reader candidate remains isolated.

## Published artifact identity

Independently downloaded from Central and verified against SHA1 sidecars. SHA256:

```text
31a797e94a2a9a7a9e2796cc79b71c6256283376e9a86395e4ca2f37b29343cc ducklake-catalog-0.14.0.jar
ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f ducklake-catalog-0.14.0-test-fixtures.jar
3b3ddd334e9eb9cd971062cc2b19b1b2d7ee535538c46f005b5db01277300c11 ducklake-test-corpus-replay-0.14.0.jar
ee37d13d863437eaaf36fe4c34f8d66cfa1cb6d1349ef661ae4f50d2e91120be slt-format-0.14.0.jar
```

Downloads and published replay source inspection:
`build/validation/catalog-0.14.0/central/`.

## JVM/build validation

Fresh serial checks **pass** under a private 6 GiB / no-swap / 300% CPU scope,
with one Gradle worker, in-process Kotlin compilation and no retained daemon:

- Java 25: **289 passed, 1 existing disabled test**.
- Java 17 plugin tests: **252 passed, the same 1 disabled test**.
- Build logic: **8 passed**; Detekt, assembly, artifact identity and the Java 17
  runtime scan (**24,766 classes**) pass. All four resolved catalog-family JARs
  match independently verified Maven Central bytes.
- Stored-format migration/refusal/positive-read tests pass on both JVMs. Structural
  binding tests, including the real PostgreSQL oracle case, and owned-target
  reconnect/refusal tests pass at Java 25. The AST replay tools require Java 25.
- The private headless scope was stopped after completion; its receipt is
  `build/validation/catalog-0.14.0/default-scope.json` and log is `default.log`.

Default bundle:

```text
build_id doris-ducklake-v1-90f1f1c110c714bccaa3bf43bfb8ea8afdc23a6bc53535c3b5e99e68908888c8
provider_jar_sha256 6c904071f42af4f34bb1c55c5a614c5b36c2e2b2968e4570a7b8dd8bb9aa9e54
plugin_zip_sha256 f98163e90fd827f5682c18a97f92822f1d67454326b74f21421d7972b56f4f8c
```

## Full-directory campaign

The matching resource-only B bundle passed assembly/runtime checks in a separate
serial scope, which was stopped before corpus infrastructure was started. A/B
provider class bytes are identical; their resource/build identities differ as intended.

The full 1.0-lane campaign **completed and FAILED**. Persistent evidence root:
`build/validation/catalog-0.14.0/f24-e-f_eo2zij/`; controller log:
`build/validation/catalog-0.14.0/corpus-controller.log`.

- **475 files selected; 426 ran to end; 49 file skips/halts**, including one
  explicit 1.1 format-scope halt and one format-inspection failure. Existing
  curated exclusions and the 40-mirror cap were unchanged. No watchdog timeout
  or quarantine blocking occurred.
- Record outcomes: **8,734 passed / 163 failed / 100 skipped**. These are mixed
  statement/query outcomes, not 8,734 verified Doris reads.
- **2,280 oracle/golden query passes**. Actual Doris mirrors: **399 attempted,
  296 passed, 15 failed, 88 attempt-skipped**. There were **1,969 total mirror/gate
  skips**, including pre-attempt dialect and transaction gates; that larger skip
  total is not additional mirror attempts.
- JUnit: one corpus test executed, one failure, zero errors or assumption skips.
  Gradle exited 1 on the record-failure assertion, not a resource timeout/OOM.

### Failure-stage triage

All **163** failures are retained in `corpus-report.txt` and the generated
`failure-index.csv`; `summary.json` records the counts and independent cleanup check.
Stages below identify where an error was reported, **not proof of its root cause**:

- **7 Doris divergences**:
  - `delete/multi_deletes.test:80`: historical snapshot 2 expects **7,500 rows /
    sum 37,500,000**, but Doris returns **5,000 / 25,000,000** (the current result).
    This demonstrates the documented F08 historical-delete correctness gap on
    these exact artifacts; it is not a passing/refusal case.
  - `types/timestamp.test:27,32,37`: infinity predicates expect counts **1,4,4**,
    but Doris returns **0,0,0**. Unsupported non-finite temporal semantics need
    proper admission/handling, not a blanket text-based skip.
  - `types/map.test:30,37,52`: whole-MAP results differ in representation
    (`{i=1, j=2}` versus `{"i":1, "j":2}`). This proves a renderer mismatch;
    the retained result heads alone do not prove additional value/row loss.
- **8 Doris mirror errors**: two nested imported/mapped-file refusals, one nested
  historical-scan refusal, three encrypted-Parquet footer errors, and two missing
  `snapshot_id` virtual-column errors. None were converted into new skips.
- **2 adapter-connect failures** in `transaction/transaction_schema.test`: these
  fixtures use explicit `metadata_s1`/`metadata_s2` PostgreSQL metadata schemas,
  while the connector reads its default search path. Do not misclassify them as
  an uncommitted-transaction or catalog-version problem.
- **1 execution-format inspection failure** in `catalog/quoted_identifiers.test`:
  quoted/modified connection and metadata catalog/schema identifiers are not
  handled by the current fixture adaptation. Inspection failed loudly rather
  than manufacturing an out-of-scope skip.
- **145 other oracle/harness record failures**: golden/result/error-message
  differences, transaction-abort cascades, TIME JDBC conversion limits and other
  fixture/SQL-adaptation differences need a matched native/backend baseline.
  This consumer uses corpus `c23aca43b6033acfac2b8cc8d80b15ae7fa729b2`, whereas
  0.14.0's producer lane documents corpus `417a9117f590e8141742ac0579d02f6a90162e37`.
  These failures must not all be labelled new catalog regressions or known-benign
  failures without investigation; inline scratch-file side effects are another
  existing harness boundary.

### Narrow harness correction after the full run

One of those 145 failures (`metadata/hide_metadata_catalog.test:30`) exposed a
consumer binder false positive: a metadata name **only inside a string literal**
sent valid `FROM (SHOW ALL TABLES)` syntax through an AST parser that cannot parse
that native construct. The binder now token-checks for a real `catalog.main.`
identifier path before AST parsing, preserving literal/comment-only mentions.
The original failed full-run evidence remains unchanged. **5/5 focused binding/native
PostgreSQL regressions and Detekt pass** in separate output/cache/scope; the scope
was stopped afterward. The full corpus has **not** been rerun after that test-harness-only
correction, and the original 163 failures are not retrospectively reduced or marked green.
Focused log: `build/validation/catalog-0.14.0/binder-check-final.log`; XML is under
`build/catalog-0.14.0-binder-check/test-results/test/`. The first focused pass had
passing tests but a four-entry destructuring lint failure; that style issue was
corrected without relaxing the gate, then both tests and lint passed.

### Resource and preservation outcome

Owned containers, volumes and networks were cleaned. The oracle/Gradle systemd
scope was stopped, and a separate check confirms it is unloaded and no resources
with this run's project prefix remain. Protected stack state and artifact hashes
are unchanged. No retained/shared deployment, migration or lake cleanup occurred.
The tested plugin ZIP remains the exact A identity above; the post-run binder
correction is test-only and does not change its runtime bytes.

Final checks confirm the default ZIP **and complete identity receipt** still match
the full-run A artifact exactly, the corpus checkout is clean, `git diff --check`
passes, and no containers remain running. No commit or push was made.

## Qualification boundary and next work

The dependency adoption and scoped JVM/build contracts are verified; **full-corpus
compatibility is not qualified**. Keep F08 open, investigate timestamp infinity
semantics and typed MAP rendering, and separate native/backend/corpus-baseline
failures from connector regressions before any broad green claim. Existing nested,
custom-metadata-schema, encryption and virtual-column restrictions remain visible.
No new skip, expected failure, weakened assertion or edited upstream golden was
used to turn this run green. Additional full reruns/fixes remain explicit follow-up work.

- Isolated controller guards: 7/7 passed; release-bundle guards: 3/3 passed.
- New consumer tests cover read/write admission after a real native migration,
  stored 1.1 refusal, positive 1.0 Parquet/inline metadata, explicit 1.1 scope halts,
  structural binding and an actual PostgreSQL oracle fixture.
- The reusable full controller is now tracked at `compose/corpus_acceptance.py`;
  the earlier temporary controller did not survive the pause. Persistent evidence
  goes under `build/validation/catalog-0.14.0/`.
- Full-directory selection retains this repository's corpus pin and existing
  curated exclusions / 40-mirrors-per-file cap. Oracle passes, actual mirrors,
  format-scope halts, skips, failures and resource/watchdog limits must remain distinct.

Earlier 0.13.x reports retain their original artifact identities and results.
No commit, push, retained-stack upgrade or shared-lake modification is requested.
