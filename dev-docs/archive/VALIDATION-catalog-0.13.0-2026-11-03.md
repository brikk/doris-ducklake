# Catalog 0.13.0 adoption — 2026-11-03

**Result: adopted and verified within the supported scope below.** No commit or push.

Historical artifact evidence. The default has since advanced to
[0.13.1](VALIDATION-catalog-0.13.1-2026-11-03.md), which fixes the shared replay
bootstrap-format blocker documented at the end of this report.

## Dependency and behavior changes

- Default catalog, test fixtures, corpus replay and SLT resolve released **0.13.0**
  from Maven Central. Native test JDBC is **2.0.0.1**. The production catalog
  connection remains PostgreSQL; DuckDB JDBC is test-only and Quack JDBC is absent.
- The default mapper now uses `DucklakeTypeSignature.parse`, shared with the
  historical reader candidate. Quoted names, case, order and nested DECIMAL commas
  are preserved; malformed/over-budget signatures and empty/case-colliding names
  refuse. Scalar mappings and feature opt-ins are unchanged.
- Native JDBC fixture creation explicitly selects `DUCKLAKE_VERSION '1.0',
  AUTOMATIC_MIGRATION FALSE`. Existing lakes are not migrated. Python/CLI live
  producers stay at 1.5.6 to retain cross-version interoperability coverage.
- Replay delegates BLOB rendering to the selected shared replay library as well
  as floating rendering. Native comparisons reproduced the old adapter's wrong
  quote/backslash escapes; the new regression compares empty, special and all-256-byte
  payloads with actual DuckDB 2.0 casts. The bounded live mirror also includes BLOBs.
- Doris uses `commitInsert`, not the snapshotless DELETE/MERGE/flush APIs rejected
  by 0.13. No materialization-snapshot call-site migration was needed.
- INSERT propagates unknown-outcome/post-commit exceptions unchanged, without an
  automatic retry. Rollback discards in-memory fragments, not files or registrations.
  New regressions inject each error after a real PostgreSQL catalog publication,
  retaining an actual native-produced Parquet and the original exception identity.
  This is connector-boundary coverage, not network-partition/reconciliation or
  engine-wide retry/GC qualification. Uncertain writes require manual reconciliation.
- The separately pinned `0.13.0-20261101.082015-33` experimental reader lane remains
  isolated. Release adoption does **not** promote its 1.1 reader or enable 1.1 writes.
  Doris SDK/API27, engine images and all other dependency pins remain unchanged.

## Published artifact identity

Downloaded independently from Maven Central, checked against its SHA1 sidecars,
and compared byte-for-byte with Gradle's resolved JARs. SHA256:

```text
01ffed5abb0b970b307dd4de1b7d7ab42320e33016447896204e5a36506a3041 ducklake-catalog-0.13.0.jar
ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f ducklake-catalog-0.13.0-test-fixtures.jar
d588b8fa135133d26c8007681c724b36ec57f5dc15efb1f2d711318ea4626cb0 ducklake-test-corpus-replay-0.13.0.jar
ee37d13d863437eaaf36fe4c34f8d66cfa1cb6d1349ef661ae4f50d2e91120be slt-format-0.13.0.jar
6cb8b0e0fc51b2e45aead2d2c1e1fd09dd10267db2966e69aaab0de67319f826 duckdb_jdbc-2.0.0.1.jar
```

The catalog and fixtures match the prior attested candidate bytes; replay and SLT
are newer. Local download evidence: `/tmp/opencode/doris-catalog-0.13-central.json`.

Released plugin identity:

```text
build_id doris-ducklake-v1-2ce3ff63fc19e82dd97b0f4c4625c01f55f13413b6beec537f1ace1caf0f6406
provider_jar_sha256 ef3291252dc4943b5c991cb825f1be07e7fb78691c9f5be585c793d5cb4f893c
plugin_zip_sha256 867b2adce6e79360b5098e363c370d71b180553222a85643db226d8ce2163f81
```

Output: `build/distributions/doris-ducklake-0.0.1-plugin.zip` and its adjacent
`.identity.json`. The new BLOB adaptation is test-only; it does not change the
provider or its runtime dependencies.

## Verification

- Fresh nonincremental Java 25 suite: **277 passed, 1 existing disabled test**.
- Fresh nonincremental Java 17 plugin suite: **246 passed, the same 1 disabled test**.
- Build-logic tests: **8 passed**. Detekt, assembly, bundle identity and Java 17
  runtime verification pass (**24,759 classes** checked).
- Python controller suite: **131 passed, 2 opt-in Docker tests skipped** (133 total).
  The new release guard tests reject old/mixed catalog bundles before allocation.
  Shell syntax checks and `git diff --check` pass.
- Historical candidate regression: **289 passed / 1 existing disabled** at Java 25,
  **258 passed / the same 1 disabled** at Java 17. Exact artifact/checksum attestation,
  lint, assembly and its Java 17 runtime gate (**24,788 classes**) pass. This is
  headless compatibility evidence, not a fresh 1.1 live campaign or candidate promotion.
- The ZIP contains exactly the release catalog, no DuckDB/Quack JDBC, replay/SLT
  test tooling or duplicate host SPI/Thrift. A/B provider class bytes are identical;
  B differs only by its test resource and resulting content identity.
- A final ordinary default build (no candidate property or acceptance init script)
  reproduces the live-qualified ZIP **and complete identity receipt exactly**.
  Its resolved runtime/test graph contains no candidate artifacts or Quack driver.

Early reruns exposed missing generated Kotlin classes when output lanes shared
task history; isolated project caches and the documented nonincremental cold rerun
fixed this without changing assertions. The new outcome
tests were also isolated from the existing orders pruning fixture so unknown-stat
files cannot contaminate its independent range-pruning assertions.

## Owned live acceptance

**Passed** on the exact API27 FE/ASAN-BE images and released A bundle above:

- Full supported smoke: **28/28 checks passed**, including INSERT/CTAS, native
  DuckDB read-back, DDL, pruning, deletes, defaults and maintenance effects.
- Java 25 and Java 17: **2/2 live tests per JVM, no failures/errors/skips**.
  Quoted nested names, nested DECIMAL, LIST/MAP values, NULL parents and literal
  `NULL` strings retain their native identity and complete field IDs.
- Each JVM measures **2/2 positive replay mirrors** over the same PostgreSQL/S3
  lake: Boolean and floating/BLOB queries. A deliberately wrong Boolean hash
  fails before its mirror, while the later floating/BLOB query still mirrors
  successfully (**1/1**). This is authored bounded coverage, not a full corpus run.
- The runner completed cleanup and its protected-state comparison passed. No
  retained/shared stack or lake was installed into, migrated, restarted or reset.
  A separate post-run Docker check confirms both attempts left no owned containers,
  volumes or networks behind.

Evidence: `/tmp/opencode/f24-e-3tx2_l1x/acceptance.json`, adjacent live XML,
native oracle and deployment/smoke receipts. Log:
`/tmp/opencode/doris-catalog-0.13-live-final.log`.
The earlier `/tmp/opencode/f24-e-8w8h1bd1` attempt was deliberately interrupted
to add BLOB coverage; its cleanup/protected-state comparison completed, but its
campaign status is failed and it is **not** counted as successful live acceptance.

```sh
python3 compose/read_correctness_acceptance.py --released \
  --bundle-a build/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/catalog-0.13-acceptance-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /home/jayson/.local/share/mise/installs/duckdb/1.5.6/duckdb \
  --evidence-root /tmp/opencode
```

## Reproduction and limits

Commands (JDK 25 selected through `JAVA_HOME`, Java 17 toolchain installed):

```sh
./gradlew test java17PluginTest check assemble qualificationDependencies \
  --project-cache-dir /tmp/opencode/doris-catalog-0.13-default-gradle-cache \
  --continue --rerun-tasks -Pkotlin.incremental=false --max-workers=1
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s compose -p 'test_*.py'
bash -n compose/smoke.sh compose/smoke_run.sh
```

Native BLOB reproduction/fix logs:
`/tmp/opencode/doris-catalog-0.13-blob-before.log` and
`/tmp/opencode/doris-catalog-0.13-blob-after.log`.
Final release JVM/build log: `/tmp/opencode/doris-catalog-0.13-default-verified.log`.
Candidate log: `/tmp/opencode/doris-catalog-0.13-candidate-verified.log`.
Ordinary build log: `/tmp/opencode/doris-catalog-0.13-normal-build.log`.
Controller log: `/tmp/opencode/doris-catalog-0.13-python.log`.

## Full-corpus follow-up: bootstrap format blocker

The requested full-directory replay was **not started**. Inspection of the published
`ducklake-test-corpus-replay:0.13.0` sources confirms `ReplayAttachmentState.prepare`
rewrites only the metadata URI, leaving ATTACH options unchanged; `DuckDbOracle`
does not inject a format selection either. This repo's ordinary corpus ATTACHes
omit a format pin. With JDBC 2.0.0.1, they initialize **DuckLake 1.1**, which the
released Doris lane intentionally refuses.

A native probe on fresh owned local catalogs verified stored metadata directly:

```text
default ATTACH:                                              version 1.1
DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE:             version 1.0
```

Evidence: `/tmp/opencode/doris-catalog-0.13-bootstrap-format.log` and
`/tmp/opencode/doris-corpus-format-5478664988340882688/`.
Downloaded publication sources:
`/tmp/opencode/ducklake-test-corpus-replay-0.13.0-sources.jar`.

The bounded live tests above supply explicit 1.0 options and are unaffected. A
meaningful full **1.0** connector campaign needs an explicit oracle-bootstrap format
policy in the shared replay harness/consumer seam first. Do not edit stored format
strings, silently rewrite tests that intentionally exercise 1.1/migration semantics,
enable the experimental reader to hide the mismatch or count format refusals as
successful Doris coverage. A disposable full-campaign controller was prepared at
`/tmp/opencode/run-doris-full-corpus-0.13.py` but has not been launched.

The full upstream corpus, direct Quack driver, Duckbridge and production/performance
qualification are outside this adoption. No retained stack or shared lake is an
acceptance target. Existing correctness gaps remain in [REVIEW-FINDINGS.md](REVIEW-FINDINGS.md).
