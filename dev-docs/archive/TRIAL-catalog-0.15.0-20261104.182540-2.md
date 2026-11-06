# Published catalog 0.15.0 trial — 2026-11-04

Historical snapshot evidence, completed before the owner's subsequent
[formal 0.15.0 adoption attempt](VALIDATION-catalog-0.15.0.md) and
[0.15.1 adoption](VALIDATION-catalog-0.15.1.md). The trial-only init script was
removed and its useful tests folded into the released test lane. The snapshot's
copy-ABI failure and soft-budget behavior below were fixed before formal release;
these results are not relabeled as a full-suite run on the release.

Requested candidate: **`0.15.0-20261104.182540-2`**, producer main
`98d7018eafb661f7a7c3c34dc733278bb0aa7aee`. This is an opt-in trial, not adoption.
Normal catalog/replay/SLT pins remain **0.14.0**, JDBC remains **2.0.0.1**, and the
qualified Doris API27 SDK/runtime and default distribution remain unchanged.

## Published provenance and isolated lane

All candidate modules resolve exclusively from
`https://central.sonatype.com/repository/maven-snapshots/`, using the exact timestamp
coordinates, never a floating snapshot, local Maven catalog or local JAR substitution.
Independent HTTP downloads match all owner-supplied SHA256 values:

```text
14a3f495f3b52f4a5d50fa6a92d90104cbe0183a270521d75c43083491bd84aa ducklake-catalog
ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f ducklake-catalog test-fixtures
3b3ddd334e9eb9cd971062cc2b19b1b2d7ee535538c46f005b5db01277300c11 ducklake-test-corpus-replay
ee37d13d863437eaaf36fe4c34f8d66cfa1cb6d1349ef661ae4f50d2e91120be slt-format
```

`compose/catalog-0.15-trial.init.gradle` isolates output in
`build/catalog-0.15-trial/`, appends only the trial test source set
`test/catalog-015`, and verifies resolved component IDs and all four JAR hashes
across compile/runtime/test configurations before tests. The historical reader
candidate cannot be mixed into this lane. No default build configuration is edited.

Reproduction (JDK25 Gradle, Java17 toolchain, owned disposable PostgreSQL/MinIO):

```sh
DORIS_CATALOG_TRIAL_PROJECT="$PWD" ./gradlew \
  --project-cache-dir build/validation/catalog-0.15-trial/gradle-cache \
  -I compose/catalog-0.15-trial.init.gradle \
  verifyCatalog015Trial test java17PluginTest check assemble qualificationDependencies \
  --no-daemon --max-workers=1 -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m'
```

Run within one private 6 GiB/no-swap/300% CPU scope. Do not overlap another campaign.
No full corpus, FE/BE deployment, repeated CI matrix, upstream builds or retained
lake maintenance is part of this trial. A preliminary dependency/compile/lint-only
pass ran **zero tests**; the normal suites run once per their usual JVM lane.

## Consumer maintenance coverage

Six added trial-only tests cover maintenance/new-API behavior and interface/result
compatibility. The maintenance cases use real native-created PostgreSQL 1.0 metadata
and the existing `DuckLakeProcedureOps` implementation. Synthetic file metadata
keeps the page-boundary test small; it is not a 1,000-object upload or data-file scan.

- Existing `expire_snapshots`: dry run has no effects; full call cleans **1,002**
  dead files over multiple pages, preserves latest files, and never constructs a
  storage client. Existing result columns and catalog-wide scope are unchanged.
- PostgreSQL trigger failure during page two: verify snapshots and the first
  **1,000** scheduled/deleted metadata rows stay committed, while the failed page
  rolls back. Resume the remaining two through `cleanUpExpiredObjects`, without
  resubmitting expired IDs. A test-only repeat of the procedure demonstrates that
  no remaining eligible IDs means a no-op, **not recovery of unfinished cleanup**.
- Zero-budget expiry and capped resumptions: preserve files visible at a retained
  historical snapshot, then collect them only after that snapshot also expires.
- Table-scoped cleanup/listing: keyset pagination, invalid-limit refusal, isolation
  of other tables, dropped-table metadata collection, idempotent completion, and
  the documented soft-budget overshoot from attached delete files.
- Existing `cleanup_old_files`: real disposable MinIO, duplicate identical queue
  tuples, already-deleted paths, schedule-row removal and an unscheduled-object
  preservation control. Repeat deletion/cleanup remains successful.
- `DuckLakeQualifiedReadCatalog` inherits the new methods; the existing two-argument
  result construction remains usable. No non-delegating concrete implementation
  needs adding methods in the released lane. The historical dynamic facade remains
  a separate read-only candidate, not promoted or newly qualified here.

## Results

**PASS for the Doris consumer trial**, with no blocking API request for Doris:

| Check | Passed | Failed / errors | Existing disabled |
|---|---:|---:|---:|
| Java-25 normal suite, including trial tests | 319 | 0 | 1 |
| Java-17 plugin suite, including trial tests | 276 | 0 | 1 |
| Trial-only subset, each JVM (already included above) | 6 | 0 | 0 |
| Build-logic tests | 8 | 0 | 0 |

The same parked inline-delete test remains disabled in each normal lane; no new
skip or weakened assertion was introduced. Each normal suite ran once; preparation
only resolved/compiled/linted. The complete normal-suite/build invocation finished
in **3m 13s** under the private resource cap. All six added cases passed on both JVMs.

Published identity, compilation, `check`, Detekt, assembly, artifact identity and
Java-17 runtime scan (**24,766 classes**) pass. The packaged trial ZIP contains the
exact attested catalog JAR; it is not installed into an FE. Procedure execution was
tested directly through the SPI entry point with real PostgreSQL/MinIO, **not by
starting an FE/BE SQL cluster**.

```text
trial build_id doris-ducklake-v1-5bd630a4c0fe0526678e655593bd11f419e1b0fc323eafa7b0a7e6a8d952c85e
trial plugin_zip_sha256 2b64b94eaf22e422cd787ab4cb548e81966301c18971059f9015c1477a3a6533
```

Both private scopes are unloaded, no containers remain running, and normal pins
plus the default ZIP/full identity receipt are byte-for-byte unchanged. No full
corpus, duplicate CI run, default promotion, upstream build, commit or push occurred.

Persistent `summary.json`, `jvm-summary.json`, logs, scope receipts, independent downloads and source hashes are under
`build/validation/catalog-0.15-trial/`; resolved Gradle identity, dependency and ABI
reports are under `build/catalog-0.15-trial/reports/`.

## Adoption considerations / requested APIs

The existing SQL procedure exposes neither the new cleanup budget/completion flag
nor metadata-cleanup resumption. `cleanup_old_files` only deletes objects already
scheduled; it is not `cleanUpExpiredObjects`. A failed expiry therefore needs explicit
metadata-cleanup recovery before physical file deletion. Do not teach callers to
blindly retry the same expired IDs.

Typed post-expiry cleanup failure and a strict/documented cleanup-budget bound are
useful before broader maintenance UX, matching Trino's non-blocking requests.
Published ABI inspection additionally shows that the two-argument result constructor
remains, but generated `ExpireSnapshotsResult.copy(int,int)` and its old default
bridge are absent after the third component was added. Doris does not use these
copy methods; this is **not a Doris blocker**, but retaining bridges would help
precompiled consumers if broader 0.14 binary compatibility is intended.

A Java-17 client compiled against the hash-verified published 0.14.0 JAR confirms
the distinction: constructor and copy calls pass with 0.14.0; the same old
constructor call passes with the exact 0.15 trial JAR, but the old copy call throws
**`NoSuchMethodError`**. This is an additional tiny ABI probe, not a repeated CI
suite; its expected negative is retained in
`build/validation/catalog-0.15-trial/abi/result.json`. The default-copy bridge change
is separately confirmed by published-class descriptors. Treat this as a release
compatibility consideration, not a claim that the Doris tests exercised those
unused copy methods successfully.

Normal dependency pins and production procedure behavior are not changed by this
trial. Maintenance authorization and the other pre-existing safety backlog are not
closed by a dependency compatibility result.
