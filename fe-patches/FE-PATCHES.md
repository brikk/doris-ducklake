# FE patches for the DuckLake plugin connector (OBSOLETE — historical)

The DuckLake connector is a Doris **plugin (SPI) connector**. The Doris FE used
to carry two generic guards keyed on a hard-coded catalog-type set that didn't
know about `"ducklake"`. Upstream #66135 (`fce5af4e041`, 2026-07-27) removed
both: a registered `ConnectorProvider` claiming its type is now sufficient, and
`ENGINE=` is optional/connector-owned. So the FE builds **PATCH-FREE** —
`ducklake-fe.patch` is no longer applied.

The patch file stays in-repo only as history (the two asks it tracked are now
resolved upstream). See [[doris-fe-build-macos]] + [[doris-compose-smoke-remote]].

 > **⚠️ THE SPI IS NOW IN APACHE `master` — BUILD FROM THE REAL apache/doris, NOT THE FORK.**
 > As of 2026-07-31 the connector-SPI landed upstream (`#64304` *decouple external catalogs
 > from FE core into loadable connector plugins* + the whole `fe/fe-connector` tree, incl.
 > `fe-connector-api` / `fe-connector-spi`). We now vendor from **`~/DEV/OSS/doris`, branch
 > `master`** (apache/doris), **not** the retired brikk fork `branch-catalog-spi`. Current
 > source/SPI pin: **`8fc58e929b2`** (apache/doris master, qualified 2026-09-19).
 > Last runtime-validated FE/BE pin: **`96d0ac68e84`** (2026-09-15). Master's `<revision>` is still
 > `1.2-SNAPSHOT`, so the installed `~/.m2` coordinates are unchanged.
 > **SPI-surface note:** #66407 merged `fe-connector-api` INTO `fe-connector-spi` and renamed the
 > `org.apache.doris.connector.api.*` packages to `…spi.*`. We now depend on **only** the
 > `fe-connector-spi` artifact and our imports moved `api.` → `spi.` (mechanical). Still
 > **PATCH-FREE** (unchanged since #66135). The `Doris-Connector-Plugin-Api-Version` gate (#66211) has
 > stepped **1 -> 5 (#66407) -> 6 (#66413) -> 7 (#67182) -> 8 (#67904) -> 9 (#68027)**;
 > we stamp **`9.0`** in `build.gradle.kts` to match the source/SPI pin.
 > **Deploy together:** the API-9 ZIP will be rejected by an API-7/8 FE. The API-9 FE image and
 > stock-4.1.4 compatibility smoke are qualified; the matching API-9 BE remains unbuilt.
 > Keep this note, the Re-vendor log, and `compose/README.md` in sync.

### Re-vendor log

- **2026-09-19 -> source/SPI pin `8fc58e929b2`**
  (`[fix](iceberg) Reject server planning before snapshot conversion (#68095)`).
  Eighty-seven commits after the runtime-validated `96d0ac68e84`; the clean local
  apache/doris checkout was fast-forwarded to the exact fetched master tip. Connector
  API advances **7.0 -> 8.0** at #67904 (additive retained-schema state on
  `ConnectorMvccSnapshot`, left false for DuckLake) and **8.0 -> 9.0** at #68027
  (public Hive OpenCSV scan-property contract, not emitted by DuckLake). No mandatory
  connector method or DuckLake production-code adaptation is required.

  **Source/SPI install:** Maven revision remains `1.2-SNAPSHOT`; Thrift remains
  **0.24.0**. SPI, Thrift, and reactor prerequisites were rebuilt and installed with
  JDK 17 in `apache/doris:build-env-ldb-toolchain-latest` digest
  `sha256:d92f8279993964ed6d7f54d0f89feda2beb8032e1e7f2e5788b368e8e1aa57f9`,
  with the Maven cache disabled and the image's native Thrift 0.24 compiler selected.
  Installed SHA-256 values: `fe-connector-spi`
  `6ce0e4fdc3724a220b255b803b819bdeb81eebb2c3c6098b4151a1977661d661`;
  `fe-thrift` `aae23748ed798308c948b24cdaae19ac1578d467cbfc7b17d1f276918c41ee11`.

  **Relevant delta:** #68142 makes Iceberg-discriminated ranges fail when a data or
  delete file is missing instead of silently skipping it; DuckLake emits those ranges,
  so matching new BEs inherit the safety fix. #67209 broadens generic CAST residual
  handling. Built-in Iceberg name-mapping and nested-Variant fixes do not repair
  DuckLake's F11/F12 dictionary gaps. F08 historical delete filtering, F09 nanosecond
  fidelity, O01 missing-column default predicates, and production inline transport
  remain open.

  **Connector qualification:** `./gradlew clean test detekt assemble --rerun-tasks`
  on Java 25.0.2 passed: **247 passed, 1 skipped, 0 failed**. The isolated Java-17
  plugin suite passed: **223 passed, 1 skipped, 0 failed**. All 15 cluster-free
  smoke-driver/lake tests passed, including a new fail-loud wrong-delete-count case.
  Archive verification confirms API **9.0** and excludes host SPI/Thrift jars. Plugin
  JAR SHA-256: `9aa7bc3e2481f09ae8d09b8c1892f4ddf6cb2362c946b99f21565e34d8c08060`;
  ZIP: `49a89278191dbc4f10a20bb84ab357eb2fefa3d1da90765223e692575380b1c3`.

  **FE/image:** a clean FE build succeeded in the same pinned build environment at
  six CPUs/10 GiB. FE jar SHA-256:
  `9f21e1beb5073599eabccd694b71b1faed8b3b0c5ec9437eb53f00549557f0d6`.
  The `doris-fe:pr62767-local` overlay digest is
  `sha256:5cf626e0a26bd743de570997e4343d4199591d4ad0e611ca97def2af624045a8`.
  Startup reported `doris-0.0.0-8fc58e929b2`; the API-9 DuckLake plugin loaded with
  zero connector failures.

  **Stock-BE compatibility smoke:** full isolated run
  `5724667282194d03968b284f67c80ec5` passed against `apache/doris:be-4.1.4`:
  TPC-H reads/counts and filter EXPLAIN, strict OPTIONAL position deletes (100 -> 93),
  DDL, INSERT, bucket equivalence, CTAS, snapshot expiry, cleanup and orphan removal.
  O01 remains reproduced: unfiltered defaults materialize, but `WHERE b=42` returns
  **0/3**. The run also exposed and fixed restart configuration losing
  `priority_networks` when FE metadata already existed; command-level coverage now
  pins the staged election network. An explicit FE restart retained the persisted
  `172.30.80.10` identity and returned both FE and BE alive.

  **Qualification boundary:** no matching API-9 BE build or corpus replay has been
  performed. Current master adds Lance-C and BRPC third-party patches not proven by
  the September 14 build image. `96d0ac68e84` remains the last matching-master
  runtime baseline; do not use its API-7 FE image with the API-9 ZIP.

- **2026-09-15 -> source/SPI/runtime pin `96d0ac68e84`**
  (`[fix](agg) Align complex aggregate null ordering (#67439)`). This is the first
  pin here containing merged #66729 (`1407093484a`): isolated BE Java plugins and
  lazy JVM startup. Connector API stays **7.0**; Maven revision stays
  `1.2-SNAPSHOT`; `ConnectorMetadata.listsPartitionsAtSnapshot` is a default-false
  opt-in and requires no DuckLake adaptation. Existing storage-predicate and nested
  prune capabilities remain off. `TIcebergDeleteFileDesc.file_size` and row-id-fetch
  scan metadata are additive; DuckLake does not emit the optional delete size yet.
  Upstream master advanced once after the build to `9d576e9e00a` (planner-overhead
  only; no SPI/Thrift/BE/runtime files changed), so the checkout intentionally remains
  at the exact built/tested pin rather than claiming that later commit.

  **Host/build discipline:** 24 hardware threads, 26 GiB RAM, 8 GiB swap, 1.4 TiB
  disk free, and a 14 GiB `/tmp` tmpfs. No heavy services were running. Builds were
  sequential, never alongside Gradle: Maven/SPI at 6 CPUs/6 GiB, FE at 6 CPUs/10 GiB,
  BE at **`-j8`** with 18 GiB RAM / 24 GiB memory+swap. Ccache and image staging were
  disk-backed (`~/.cache/doris-be-ccache`, Doris `output/`), not `/tmp`.
  Build environment `apache/doris:build-env-ldb-toolchain-latest` digest
  `sha256:d92f8279993964ed6d7f54d0f89feda2beb8032e1e7f2e5788b368e8e1aa57f9`
  (created 2026-09-14) carries Thrift 0.24, Rust 1.91, protoc, Lance, Snappy and
  libunwind artifacts matching this source window. Host passwd/group were mounted
  read-only so non-root UID 1000 resolved correctly.

  **Build results:** SPI/Thrift installed successfully. Clean FE build succeeded
  with OBS/COS excluded. Full C++ BE compile/link succeeded at `-j8`, including the
  GLIBC_2.17 symbol gate; the packaging rerun skipped the unrelated CDC client after
  its stale root-owned Maven target blocked resource copying. All eight isolated JNI
  plugin directories passed Doris's six deployment-layout checks. The BE overlay now
  replaces/copies `output/be/plugins` in addition to bin/lib/conf/www. Old root-owned
  generated/build/submodule trees were preserved, not deleted, under
  `output/backups/pre-96d0ac68/`; clean pinned contrib submodules were initialized.

  **Connector checks:** `./gradlew test --rerun detekt assemble` on Java 25.0.2:
  **247 passed, 1 skipped, 0 failed**. The Java-17 plugin suite: **223 passed,
  1 skipped, 0 failed**. Archive verification confirmed API 7.0 matches the SPI and
  host SPI/Thrift jars are excluded. The 13 cluster-free smoke-routing tests pass.

  **Images/artifacts:** `doris-fe:pr62767-local` image
  `sha256:7bb8466fb3a67ab9bedb7818743bccf3615ae09cb0a48d971672dd6d81d78b82`;
  `doris-be:master-local` image
  `sha256:ebf378d54f668cec6958897d403e8424e44d520a2da2b368264368c179b82e60`.
  FE jar `8dbc478383dd589db6a0263b3f0143071fd02c1a0cad58e3ac7e7bf4496efc5f`;
  BE binary `8f294f4d940cc37cbe2249e3b05da347e069395f4bce52871f7eb6f9f5323d94`;
  SPI `452ab12a3d4a47e6799accf047eb443dd25f50aa8d69c2ee9fa2da97a7b90df0`;
  Thrift `c9b7f056a9963b74e03ac13b712d9084eddd592a1adbec7e5e28ecb8dd87634f`;
  plugin ZIP `67886c0dd3ed612dae49294fda5e83e7a0d18fe2831e36720539c1e71ac847af`.

  **Live isolated smoke:** FE and BE both reported `doris-0.0.0-96d0ac68e84`.
  Run ID `15d35479433c4141816b33c1a0927621`, PostgreSQL database/catalog of the
  same `doris_smoke_<ID>` name, warehouse
  `s3://ducklake/doris-smoke/<ID>/`. Reads (orders 15000, lineitem 60175),
  nullable COUNT (4/2), filter EXPLAIN, file position deletes (100 -> 93), DDL,
  writes, bucket equivalence (1/2/3), CTAS, snapshot expiry, scheduled-file cleanup,
  orphan deletion and foreign-file preservation all passed. F03 was separately
  live-validated with reversed columns, a partial insert and bucketed reversed
  columns; Doris and DuckDB both returned `(10,ten),(20,twenty),(NULL,missing)` and
  `(1,alice),(2,bob)` respectively. F21's run-owned routing and GC were therefore
  exercised live; the shared database/warehouse were never selected by the driver.

  **O07 resolved for this workload:** master BE starts and serves native S3 Parquet.
  `/api/jni_plugin_status` reports `registryInitialized=false` and no plugin loaded;
  `/metrics` has no `jvm_*` series. `libjvm.so` being mapped is not evidence that a
  VM was created. This validates #66729's lazy path for this workload, not HDFS or
  each JNI plugin. FE/BE images and the isolated run are left available for follow-up.

  **O01 narrowed, still P0:** unfiltered read materializes evolved defaults correctly
  as `(1,42),(2,42),(3,42),(4,99)`, but `WHERE b=42` and
  `WHERE a IN (1,2,3) AND b=42` return **0**, with the old-file range still present.
  One follow-up query sequence produced the known `Const(INT)` versus
  `Nullable(INT)` error and a SIGSEGV in `_evaluate_constant_filters`; after BE
  restart, the individual predicates reproducibly returned 0 without crashing.
  So the current defect is the missing-column constant-predicate path, not general
  default materialization, and the crash is observed but not immediately repeatable.
  The smoke remains nonfatal on this known check (F25), so its final completion line
  must not hide this failure.

  **Still open:** F08 historical multi-snapshot delete visibility was not exercised;
  latest-snapshot file deletes alone passed. F11/F12 nested/per-file identities,
  production inline transport, F07 metadata rewrite consistency, and other review
  findings remain unchanged. No corpus replay was run because F22's broad cleanup
  guard remains open. `branch-4.2` has no connector SPI and remains unsuitable.
  Duckbridge follow-up is captured in
  [`../duckbridge/dev-docs/HANDOFF-doris-master-96d0ac68-lazy-jvm.md`](../../duckbridge/dev-docs/HANDOFF-doris-master-96d0ac68-lazy-jvm.md).

- **2026-09-09 -> source/SPI pin `0557668f405`**
  (`[fix](cloud) Exclude covered rowsets from compaction minimum timestamps (#67617)`).
  Forty commits after `2be8fba29d7`; the local source checkout was fast-forwarded
  cleanly. **Plugin API remains 7.0**, Maven revision `1.2-SNAPSHOT`, Thrift
  compiler/runtime **0.24.0**. #67545 adds default no-op
  `ConnectorProvider.validateCreateTable(Map)` for configuration-only preflight;
  DuckLake needs no override. Existing API-7 manifest and capability opt-outs were
  preserved. No production connector logic/type mapping changed in this check.

  **Wire/artifacts:** regenerated and installed SPI + Thrift and reactor dependencies
  with the same Java-17, 6-CPU/6-GB Maven invocation and disabled build cache used
  previously. Thrift IDL changed: `TIMESTAMP_NS = 45`, `TColumnAccessPath.version`,
  `TColumn.default_value_expr`, `TExprNode.is_strict_cast`, and the internal
  time-based change-read fence request/result/RPC. The Iceberg range descriptor
  and external schema Thrift are unchanged. FE gRPC advances **1.65.1 -> 1.75.0**;
  native third-party sources are unchanged from the previous pin, including the
  Lance-C-0.1.9 requirement that the September 1 build-env does not satisfy for a BE build.

  **Validation:** connector suite **247 passed, 1 skipped, 0 failures** on Java
  25.0.2; isolated Java-17 plugin suite **223 passed, 1 skipped, 0 failures**.
  Detekt/assemble passed, and the temporary archive check confirmed the actual
  packaged plugin manifest matches SPI **7.0**, with host SPI/Thrift JARs excluded.
  These are connector/headless and archive checks, **not full FE/BE or corpus runs**.

  **Useful upstream fixes:** #67573 distrusts known-bad Parquet writer null counts
  during V2 row-group/page-index pruning, preserving NULL candidates in affected
  imported files. #67574 reconciles nested projection alignment after predicate
  demotion or reader-added dependencies. It does **not** supply DuckLake's omitted
  nested field IDs: F12 remains open, as does F11's authoritative per-file mapping.
  #67700 prevents current-MV substitution for explicit VERSION/TIME snapshot scans,
  including plugin tables; this does not inspect every opaque MVCC handle pin or
  repair F07/F08. Position-delete snapshot filtering is still absent.

  **TIMESTAMP_NS (#66761):** a real new engine type, not `DATETIMEV2(9)`. The SPI
  type-converter fallback can recognize it, but that alone does not establish
  lossless native Parquet reads. Our `timestamp_ns` mapping, inline writer and
  Iceberg sink remain microsecond-based, with no new opt-in. **F09 is not fixed**
  by the upstream type addition; a mapper-only switch would not suffice.

  **Deployment warning:** #65805 versions typed DATA/META nested access paths.
  New BEs decode legacy paths, but old BEs need not understand new FE emission:
  qualify/upgrade BEs before FEs emitting those paths. The API-7 plugin gate is not
  a guarantee of FE/BE wire-semantic compatibility. Compose still defaults to the
  release-4.1.4 BE for limited compatibility work; that combination is not certified
  for this tip. No full engine build, image replacement, startup retry, or live
  smoke/corpus was performed.

  **Startup watch:** #67664 switches upstream's compose build to Debian 12/Liberica
  JDK 17 to avoid expired Bullseye APT metadata, and #67663 reads final config lines
  without a newline. Neither establishes a fix for O07's recorded JNI/Hadoop crash;
  our release-based overlay does not automatically adopt the new base image.
  #66729, #66773 and #66935 remain open. O01 still needs an artifact-identified
  live DEFAULT probe; the unmarked scan continues to preserve the FE expression
  in source, rather than following the old unconditional-clearing diagnosis.

  **Release branches:** `branch-4.2` remains at `9d671369d5f`, still without the
  connector SPI. `branch-4.1` has moved to `bdc3fcf4884`, so they no longer point
  to the same tip. GitHub still marks 4.1.3 latest stable; the 4.1.4 pre-release
  entry now names **4.1.4-rc03** (updated September 9), rather than rc04 as recorded
  on September 8. The inspected rc03 FE tree also lacks `fe/fe-connector`.
  Keep master as this plugin's development target.

  **Release-container follow-up (2026-09-11):** Docker Hub now publishes multi-arch
  `apache/doris:be-4.1.4` and `fe-4.1.4`. Compose and the master-BE overlay base now
  use **BE 4.1.4** for the stock compatibility axis. The released FE is not used:
  its source line has no connector SPI, while this plugin requires a master/API-7
  FE. Compose rendering and 14 cluster-free smoke tests pass; no 3GB BE image pull
  or live FE/BE qualification was performed. Historical 4.1.3 results below remain
  evidence for their stated runs, not current defaults.

  Installed-artifact SHA-256 values:
  `fe-connector-spi`: `8b8c0d629881815a1f029cb9854e91903ca357b8f6c4aefe32c0fb4ab21407f7`;
  `fe-thrift`: `43c8449812904e9cc554ae85f35fdf43e8b3b1c356fa5cbbc8c57bdf7b4fdb1d`.

- **2026-09-08 -> source/SPI pin `2be8fba29d7`**
  (`[improvement](snapshot) Add snapshot retained analysis interface (#67616)`,
  committed September 7). Sixteen commits after `b58b2c53ff5`.
  **Re-vendor required for current master:** #67182 (`eea19b3f3cf`) adds the public
  `SUPPORTS_STORAGE_PREDICATE_PRUNING` capability and bumps the gated plugin API
  **6.0 -> 7.0**. Even a connector leaving the new capability off must stamp 7.0
  to load on this FE. No new mandatory connector method is required. The local
  checkout was fast-forwarded, SPI/reactor artifacts reinstalled, and our manifest
  updated to 7.0. The new capability is deliberately **not declared**, with explicit
  regression coverage: ordinary DuckLake `applyFilter`/bucket/statistics pruning
  remains available, but the new monotonic-function-derived predicate pass is not
  opted into before connector-specific correctness validation.

  **Release/branch check:** [#67355](https://github.com/apache/doris/issues/67355)
  is titled **4.1.4 Release Note**, linking back to the 4.1.3 notes. GitHub still
  marks **4.1.3** as the latest stable release and **4.1.4-rc04** as a pre-release.
  A `branch-4.2` now exists, but at this check it points to exactly the same commit
  as `branch-4.1`: **`9d671369d5fb153ecded29f5f44d02b8b31ed89e`**. Its merge base
  with `4.1.4-rc04` is the RC's commit **`e5ae5ab9d040d020e0473248de84b53d4ad75d53`**,
  and it is 39 commits ahead with none unique to the RC side. Thus its current
  ancestry is the 4.1.4 release line, not master; this does not establish the exact
  branch-creation event. The 4.1.3, 4.1.4-rc04, and current branch-4.2 trees all
  lack `fe/fe-connector`. **Do not re-target this plugin to branch-4.2 yet.** The
  user's 30-45-day 4.2 estimate is a planning horizon, not a verified release date
  or a promise that the SPI will ship there. Recheck actual contents before switching.

  **Validation:** Maven source/SPI install succeeded on Java 17.0.2 using the same
  6-CPU/6-GB build-env invocation and skip/cache flags as September 5. Maven revision
  stays `1.2-SNAPSHOT`; Thrift stays **0.24.0**, with no Thrift IDL delta (a separate
  protobuf change adds `row_location_version`). Connector tests on Java 25.0.2:
  **247 passed, 1 skipped, 0 failures**; separate Java-17 plugin suite:
  **223 passed, 1 skipped, 0 failures**. Detekt and packaging passed. A temporary
  archive-verification task confirmed that the ZIP's actual plugin JAR declares
  **7.0**, matching the installed SPI resource, and does not bundle host SPI/Thrift
  JARs. This is archive/API evidence, **not** an isolated FE classloader/live test.

  **Other relevant changes:** #67441 adds V2 Parquet row-group-statistics pruning
  for `array_contains`, independently of the new FE capability. It is not a repair
  for F12's missing nested identities. #67496 advances **Lance C 0.1.8 -> 0.1.9**
  with both Doris PR-73/PR-74 patches. The September 1 build-env still has the old
  Lance library: it suffices for this FE-facing build but is not an exact-match BE
  environment. Verify/update third-party provenance before any full BE build;
  do not assume an unversioned `liblance_c.a` will fail a freshness check. The tip's
  snapshot-retained diagnostics are cloud Recycler interfaces, not DuckLake snapshot
  filtering. #64678's failed-write cleanup mentioned in the 4.1.4 notes was already
  in our previous master pin; its shared BE writer fix does not resolve M08's
  separate successful-file/failed-catalog-commit orphan case.

  **Runtime/deployment remains deferred:** no full FE/BE build, image replacement,
  startup retry, or live smoke/corpus. API-6 FE images must be rebuilt/replaced before
  installing this API-7 ZIP. The JNI/Hadoop startup path has no fix in this window;
  #66729 remains open/unmerged. F08/F12 remain source-traced correctness issues,
  and O01 still needs a current artifact-identified live DEFAULT probe. Retain the
  previous FE/plugin pair for existing deployments until the matching runtime is
  ready; re-vendoring the development artifacts is not a production upgrade.

  Installed-artifact SHA-256 values:
  `fe-connector-spi`: `6f71fab21fa748b2d797fc20ed9ad4086150e9da541735a02f4bbb045f9fc281`;
  `fe-thrift` (unchanged): `9f1af5b4ea587a9bc003209086ad70344868f795a2e6e4401a517f77fb3f2db1`.

- **2026-09-05 -> source/SPI pin `b58b2c53ff5`**
  (`[enhancement](thirdparty) fix arrow build bug and clear dangerous env... (#67535)`).
  Six commits after `4ab2cd71095`; local apache/doris checkout fast-forwarded cleanly.
  **Non-breaking connector API:** SPI source/resources unchanged, plugin API **6.0**,
  Maven revision `1.2-SNAPSHOT`, Thrift compiler/runtime **0.24.0**. Thrift IDL is
  additive, not identical: `TPaimonFileDesc` gains optional `original_file_path`;
  the Iceberg descriptors used by DuckLake are unchanged.

  **Artifacts/validation:** rebuilt and installed SPI + `fe-thrift` and reactor
  prerequisites using the September 1 build-env image
  `7bc419b9a7fd` (Java 17.0.2, native Thrift 0.24), capped at 6 CPUs / 6 GB.
  Maven command: `mvn -B install -P flatten -pl fe-connector/fe-connector-spi,fe-thrift -am -Dmaven.test.skip=true -Dmaven.build.cache.enabled=false`.
  The initial `-DskipTests` attempt hit a permission error copying an old generated
  test resource; skipping test resource/compilation phases resolved
  it without changing ownership or source. Maven's build cache was disabled so the
  new Thrift IDL was regenerated and compiled, rather than trusting cached artifacts.
  `./gradlew test --rerun detekt assemble` with Java 25.0.2: **247 passed, 1 skipped,
  0 failures**. Separate Java-17 plugin suite (temporary launcher override, excludes
  `**/corpus/**`): **223 passed, 1 skipped, 0 failures**. These are connector tests,
  **not live corpus or full FE tests**. The user's catalog `0.7.1` working-tree bump
  was retained unchanged. No connector implementation/API adaptation was needed.

  **Relevant changes:** #67207 adds native V2 file-location metadata support
  (`_file`/`_pos` for Iceberg; corresponding Paimon columns). DuckLake does not inherit
  the in-tree FE provider, so exposing these would require its own schema/handle/
  `SYNTHESIZED` classification and compatible-BE checks. This is useful row-location
  infrastructure, **not** inline-row transport or hidden snapshot filtering. F08
  (historical deletes) and F12 (nested dictionary omission) remain unchanged by
  source inspection. #67431 fixes reversed FLOAT/DOUBLE zone maps in Doris's internal
  storage, not DuckLake metadata statistics. #67535 repairs Arrow/LZO build resolution
  and isolates third-party build environments; it does not modify JNI startup.
  The other changes concern temporary-table CTAS/DROP, streaming-job status, and
  the FE JUnit-5 migration.

  **Runtime still deferred:** no full FE/BE build, image replacement, BE startup
  retry, or live smoke/corpus was performed. Nothing in this six-commit delta
  directly repairs the recorded JNI/Hadoop startup failure (O07); do not interpret
  that as a newly reproduced crash at this pin. A future BE retry remains sequential
  and capped at `-j14`. O01's DEFAULT backfill needs fresh live validation: the old
  unconditional-default-clearing explanation does not match the unmarked DuckLake
  scan path in either compared source pin; see the friction-log follow-up.

  **Monday watchlist:** [#66729](https://github.com/apache/doris/pull/66729) remains
  OPEN (updated September 5), proposing isolated BE Java plugins and lazy JVM startup.
  It is relevant to the startup failure class, but is **not merged or verified as
  our fix**. [#66773](https://github.com/apache/doris/pull/66773) (HDFS lazy open/delete
  file sizes) and [#66935](https://github.com/apache/doris/pull/66935) (Hadoop install
  symlink compatibility) also remain open. No `branch-4.2*` remote head was present.

  Installed-artifact SHA-256 values (provenance for these mutable local coordinates):
  `fe-connector-spi`: `64a14536686a91379764c4fb40d21153d2d12c0cb2dfbd985be131f65151bf54`;
  `fe-thrift`: `9f1af5b4ea587a9bc003209086ad70344868f795a2e6e4401a517f77fb3f2db1`.

- **2026-09-04 → pin `4ab2cd71095`** (`[feature](function) Add array_except_all scalar function (#67132)`).
  "Stay-current" bump from `952bfcbb40f` (+38 commits). **Non-breaking (FE):** no `fe-connector-spi`
  surface change, `<revision>` `1.2-SNAPSHOT`, api.version `6.0`; SPI+`fe-thrift` rebuilt natively at this
  tip (thrift 0.24 from the fresh build-env). **UNIT + CORPUS GREEN** — `./gradlew clean test` = **240
  tests, 0 failures**.
  - **Thirdparty:** `#67385` **removed paimon-cpp** (arrow-paimon-vars.sh −361 lines); this only *drops* a
    requirement, so the 2026-09-01 build-env still satisfies the BE build. thrift stays 0.24. No arrow bump.
  - **⛔ MASTER BE build/live-smoke DEFERRED (unchanged).** The `952bfcbb40f` master-BE startup SIGSEGV
    (JNI/hadoop `libhdfs` bootstrap in `Jni::Util::Init`, before any DuckLake code — see the 2026-09-02
    entry) is expected to persist: **nothing in the +38 window touches `jni-util` / the hadoop-libhdfs
    startup path** (BE changes were `#67442` memtable UAF, `#67451` arm64 build, `#67036` status.h
    decouple — none startup-related). A BE rebuild here was **not** re-attempted: parallelizing the 24-core
    BE compile with the gradle suite **OOM-rebooted the 26 GB dev box** (14 GB of it is a tmpfs `/tmp`),
    wiping the BE ccache. Retry the master-BE build **sequentially** (never alongside another heavy JVM)
    and **capped at `-j14`** (not `$(nproc)`=24) — see `compose/be-overlay/Dockerfile` — once a window fix lands. Connector **read path** stays live-validated (new FE + release `be-4.1.3`,
    per the 2026-09-02 entry; SPI surface is identical here). §12b carried forward.

- **2026-09-02 → pin `952bfcbb40f`** (`[chore](lance) update some patch about lance (#67262)`).
  Advances the `df36be5a86d` entry below (+13 commits) to fold in **#67330 revert of "Keep Arrow 17 and 24
  in shared thirdparty"** (#66546), which — together with a freshly-published build-env — unblocked the BE
  build. **Non-breaking (FE):** no `fe-connector-spi` surface change, `<revision>` `1.2-SNAPSHOT`,
  api.version `6.0`. **UNIT + CORPUS GREEN** (`./gradlew clean test` = **240 tests, 0 failures**).
  - **✅ THRIFT 0.24 now native.** Apache published a fresh `build-env-ldb-toolchain-latest` (2026-09-01
    15:23 UTC) carrying **thrift 0.24 compiler + `libthrift.a` + mecab-ipadic + lance-c 0.1.8**, so the
    from-source thrift-compiler hack (see df36be5a86d entry) is no longer needed — SPI+`fe-thrift` rebuilt
    natively in-container. **The fresh build-env matches this tip** (post-revert Arrow-24-only).
  - **✅ BE + FE BUILT.** `sh build.sh --be --fe` in the fresh build-env → `doris_be` + `doris-fe.jar`.
    Baked `doris-be:master-local` + `doris-fe:pr62767-local`. Recipe now persisted at
    `compose/be-overlay/Dockerfile` (mirrors `fe-overlay`; base `apache/doris:be-4.1.3`). Build gotchas
    (all resolved): run as **root** in-container (prior builds left root-owned artifacts); `git config
    --global --add safe.directory /doris`; wipe stale `be/build_Release` (prior cache was cut at a
    different mount path `/root/doris`); stage docker contexts on **disk** not `/tmp` (14G tmpfs).
  - **⛔ MASTER BE CRASHES AT STARTUP (open — deferred).** The freshly-built `952bfcbb40f` BE SIGSEGVs
    during embedded-JVM/hadoop `libhdfs` bootstrap in `Jni::Util::Init` (`could not find method
    getRootCauseMessage from class (null)`), **before any DuckLake code**. Ruled out: arch (ran AVX2/amd64),
    JDK (17.0.2 both), base image (base-6.0 at both pins; be-4.1.3 worked at the prior pin), and classpath
    (`JniUtil` + `commons-lang3` both load fine from the built CLASSPATH). Only new signal: benign
    `Unknown module: org.apache.arrow.memory.core` warning. Looks like a master-tip instability or a
    fresh-build-env (gcc15) toolchain artifact — **not a connector bug**. Live **master-BE** smoke
    (§8b/§12b/W1–W3/§13 GC) is therefore **deferred** pending root-cause / a later tip.
  - **✅ CONNECTOR LIVE-VALIDATED on the read path** with the new FE + **release `be-4.1.3`**: catalog
    load, `SHOW DATABASES/TABLES`, `COUNT(*) nation=25`, sample rows, `COUNT(*) lineitem=60175` (multi-file
    scan), EXPLAIN cardinality-from-stats=25. (`COUNT(col)` undercount is a known be-4.1.3 count-pushdown
    gap — correct on master BE per earlier smokes.) §12b carried forward (needs master BE).

- **2026-09-01 → pin `df36be5a86d`** (`[fix](point query) Keep point-query scan when partition pruning
  is empty (#67161)`). "Stay-current" bump from `1731787677f` (+61 commits over ~7 days). **Non-breaking
  (FE):** no `fe-connector-spi` surface change (`src/main` diff empty), `<revision>` still `1.2-SNAPSHOT`,
  api.version still `6.0` (stamp unchanged); plugin main+test compile clean. **UNIT + CORPUS GREEN** —
  `./gradlew clean test` = **240 tests, 0 failures** (incl. `DuckLakeScanRangeThriftParityTest`, which
  exercises the regenerated thrift classes → wire-compatible, and `DuckLakeConnectorMetadataTimeTravelTest`
  snapshot-scoped stats).
  - **⚠️ THRIFT 0.16 → 0.24 (#65990, `2cf32a3bbdc`, merged 2026-09-01).** `fe/pom.xml` libthrift +
    `thirdparty/vars.sh` compiler both bump; `fe-thrift`'s `check-thrift-compiler-version` guard fails if
    the local thrift **compiler** ≠ 0.24. Our `DORIS_THIRDPARTY` (`doris-catalog-spi/thirdparty`) ships the
    0.16 compiler, and the 2026-08-21 build-env image is 0.16 too. **Workaround (captured for reuse):**
    built a thrift **0.24.0 compiler** from source in the build-env container — cmake
    `-DBUILD_COMPILER=ON -DBUILD_LIBRARIES=OFF`, after dropping thrift's hardcoded bison `--file-prefix-map`
    flag (container bison is 3.5.1 < the 3.7 that flag needs) → `/tmp/opencode/thrift-build/thrift024/bin/thrift`
    (build scripts in that dir). Then rebuilt `fe-connector-spi` + `fe-thrift` into `~/.m2` **inside the
    container** (run as host user; explicit `-Dmaven.repo.local=$HOME/.m2/repository -Duser.home=$HOME`)
    with `-Ddoris.thrift.executable=…/thrift024/bin/thrift`. **BUILD SUCCESS.**
  - **⛔ BE BUILD + LIVE SMOKE DEFERRED (compile+unit+corpus-verified re-vendor; precedent 2026-08-02).**
    A `df36be5a86d` BE needs a build-env dated **after today** for two independent reasons: (1) thrift 0.24
    **libs** (#65990) — the compiler-only workaround above does NOT give the BE its thrift libs; (2)
    `arrow-paimon-vars.sh` `+345` lines (#66546 "Keep Arrow 17 **and** 24 in shared thirdparty", adds
    `ARROW_17`/`XSIMD_17`/`PAIMON_CPP_17`) trips the BE arrow/paimon freshness guard. The public
    `apache/doris:build-env-ldb-toolchain-latest` isn't rebuilt with these yet (both landed ~2026-08-31/09-01);
    a local thirdparty rebuild is multi-hour. **Pending a fresh build-env:** FE image rebuild,
    `doris-be:master-local` bake, and full compose smoke (catalog / reads / §8b-count / §12b / W1–W3 /
    §13 GC). §12b status is **carried forward from `1731787677f`** (unverified at this pin — no BE run).
  - No branch-4.2 yet; api.version steady at 6.0.

- **2026-08-25 → pin `1731787677f`** (`[chore](lance) update lance version to tag 0.1.7 (#67115)`).
  "Stay-current" bump from `168d0777833` (+~130 commits over ~8 days). **Non-breaking:** no
  `fe-connector-spi` surface change; plugin main+test compile clean; api.version still `6.0` (stamp
  unchanged). **FULL SMOKE + corpus GREEN** (FE+BE both `1731787677f`, freshly built): catalog load,
  reads, §8b-count `COUNT(v)=2`, Step-7 DELETE (93), W1/W2/W2c/W3, §13 GC, `corpusReplayTest`.
  - **✅ §12b BE crash RE-FIXED.** The `#66413` regression (SIGSEGV in `_evaluate_constant_filters`) is
    gone again — smoke completes end-to-end, BE stays alive. Back to the **correctness miss** (old rows
    read `0` not the DEFAULT); only `format_v2` touch in the window was `#66819`. See §12b friction entry.
  - No branch-4.2 yet; api.version steady at 6.0.

- **2026-08-17 → pin `168d0777833`** (`[fix](build) Unbreak master: stale unity-skip entry (BE) and
  dropped count probe (FE) (#66831)`). Bump from `b119273e3f0` (+18 commits; pinned to the tip because
  master was briefly un-buildable mid-window — #66831 is the fix). **api-version bumped 5 → 6** (#66413):
  compiles fine (the SPI change was additive `default` methods — `isWritesDataFiles`, `getBeExecVersion`,
  `canServeMetadataOnlyCount`), but the load gate rejects a major mismatch, so we bumped the manifest
  stamp to **`6.0`**. Still PATCH-FREE. **Smoke: mostly GREEN** (catalog load, reads, §8b-count
  `COUNT(v)=2`, Step-7 DELETE, W1/W2/W2c/W3, `corpusReplayTest`) —
  **but §12b REGRESSED to a BE crash** (see the §12b friction entry): #66413's `column_mapper.cpp`
  rewrite re-introduced the `Const(INT)` vs `Nullable(INT)` SIGSEGV in `_evaluate_constant_filters`
  (was a mere correctness-miss since #66589). §13 GC didn't run (BE down at §12b) but is unaffected by
  the cause.
  - **New opportunity:** `canServeMetadataOnlyCount(...)` (#66413/#66778 count-from-live-manifests) is a
    connector hook to serve `COUNT(*)` from DuckLake metadata — a future §8b-count optimization.

- **2026-08-16 → pin `b119273e3f0`** (`[fix](load) Keep graceful BE stop bounded when an audit stream
  load is in flight (#66797)`). Routine "stay-ready" bump from `b42e1ab294b` (+44 commits).
  **Non-breaking:** no `fe-connector-spi` surface change; plugin main+test compile clean; api.version
  still `5.0`. **FULL SMOKE + corpus GREEN** (FE+BE both `b119273e3f0`, freshly built): reads,
  §8b-count `COUNT(v)=2`, Step-7 DELETE (93), W1/W2/W2c/W3, §13 GC, `corpusReplayTest`. **§12b DEFAULT
  backfill unchanged** (still reads `0`, no fix in window). Timestamptz stays resolved.
  - **On our path this window:** #66628 `[fix](fe) Normalize connector table errors` edits
    `PluginDrivenScanNode` (our scans flow through it) — smoke shows no behavior regression.
  - **BE build notes:** #66783 bumped `HADOOP_LIBS_3_4 → hadoop-3.4.2.3-for-doris` (thirdparty) — use a
    build-env image **≥ 2026-08-15** (the one used here) or the thirdparty guard fails; several
    unity-build enablements (#66712/#66776/#66789) landed (ccache mostly cold this round, ~full rebuild).
  - **In-tree iceberg churn (reference, not our code):** #66348 harden external-write lifecycle/OCC,
    #66627 reject unsafe iceberg column drops, #66567 Alibaba OSS Tables REST catalog.

- **2026-08-08 → pin `b42e1ab294b`** (`[refactor](be) Remove FileScannerV2's per-range table reader
  rebuild (#66589)`). Routine bump from `a82564ced5d` (+15 commits). **Non-breaking:** the only
  `fe-connector-spi` change (#66507 "same property layout / one reader per key") touched `package-info.java`
  only; plugin main+test compile clean, api.version still `5.0` (stamp unchanged). **FULL SMOKE now
  COMPLETES end-to-end** (FE+BE both `b42e1ab294b`): reads, §8b-count `COUNT(v)=2`, Step-7 DELETE (93),
  W1/W2/W2c/W3, **§13 GC (expire/cleanup/orphan) — all GREEN**, `corpusReplayTest` GREEN.
  - **✅ §12b BE CRASH RESOLVED.** The `format_v2::TableReader::_evaluate_constant_filters` `Const(INT)`
    vs `Nullable(INT)` SIGSEGV is gone (fixed in the `a82564..b42e1ab` window; #66589 reworked the
    FileScannerV2 reader lifecycle). §13 GC + full completion reached on master for the first time.
  - **❌ New (lesser) §12b issue — DEFAULT value not backfilled:** pre-ADD rows read `b=0`, not the
    DEFAULT `42` (explicit `b=99` row is correct; no NULLs; `be-4.1.3` returned `42`). Likely the
    connector must emit the DuckLake column default as the Iceberg V3 `initial-default` for master's
    new default machinery (#65851). Not a crash, not corruption — see the §12b friction entry.

- **2026-08-06 → pin `a82564ced5d`** (`[fix](iceberg) Fix MVCC and nested schema evolution edge
  cases (#66345)`). Bump from `0c01156be7f` (+74 upstream commits). **BREAKING SPI change, adapted:**
  **#66407 `[refactor](fe) Merge fe-connector-api into fe-connector-spi`** collapsed the two-module
  split into one (`fe-connector-api` module deleted) and renamed the whole
  `org.apache.doris.connector.api.*` package tree to `…spi.*` (Trino-style single-module contract).
  Adaptation: dropped the `fe-connector-api` artifact from `build.gradle.kts` (both compileOnly and
  test), rewrote `connector.api.` → `connector.spi.` across 34 plugin files (imports only — no logic
  change).
  - **⚠️ API-VERSION MAJOR BUMPED 1 → 5.** The breaking merge also bumped
    `fe/fe-connector/pom.xml <connector.plugin.api.version>` to **`5.0`**, so the fail-closed gate
    now **rejects** a major-1 plugin at load (`incompatible Doris-Connector-Plugin-Api-Version='1.0':
    major 1 but this FE serves CONNECTOR plugin API 5.0`). We updated the `jar` manifest stamp to
    `5.0` (`build.gradle.kts`). Without this the plugin compiles but the FE refuses to load it — the
    first re-smoke attempt hit exactly this (`No connector plugin claimed catalog type 'ducklake'`).
  - **FULL SMOKE re-run (FE+BE both `a82564ced5d`, BE freshly built + baked `doris-be:master-local`):**
    catalog load ✅, reads ✅, **§8b-count `COUNT(v)=2` GREEN**, EXPLAIN ✅, **Step-7 DELETE GREEN**
    (93 rows), W1 DDL ✅, W2/W2c/W3 INSERT/CTAS/bucket ✅, **`corpusReplayTest` GREEN**. Still PATCH-FREE.
  - **❌ Still-open blocker (at this pin):** §12b DEFAULT-backfill read still crashed the BE
    (`Const(INT)` vs `Nullable(INT)` in `format_v2::TableReader::_evaluate_constant_filters`) — #66345/
    #65851/#65446 did **not** fix it. (Crash later resolved at `b42e1ab294b` — see the 2026-08-08 entry.)
  - **Other SPI-window commits (no action):** #66331 ADBC catalog (new, not our path); #66403/#66247
    paimon fixes; #65126 external metadata-cache refactor.

- **2026-08-02 → pin `0c01156be7f`** (`[feat](thirdparty) add arrow-adbc to the thirdparty build
  (#66358)`). Routine bump from `ded91fb9fb3` (+9 upstream commits). **Zero plugin `.kt` changes**
  — main + test compile clean. The one SPI-touching commit is **#66347 `[feat](connector) give each

- **2026-08-02 → pin `0c01156be7f`** (`[feat](thirdparty) add arrow-adbc to the thirdparty build
  (#66358)`). Routine bump from `ded91fb9fb3` (+9 upstream commits). **Zero plugin `.kt` changes**
  — main + test compile clean. The one SPI-touching commit is **#66347 `[feat](connector) give each
  connector plugin its own conf file`**: adds `ConnectorConf`/`ConnectorConfFile`, a **default**
  `ConnectorContext.getConnectorConfig()` (reads `<pluginDir>/<name>.conf`), and a **default**
  `ConnectorProvider.name()` (= `getType()`). Both are `default` methods and we only *consume*
  `ConnectorContext`, so **non-breaking** — no adaptation needed. Optional future use: park
  deployment-level settings (e.g. a default warehouse root) in `ducklake.conf` instead of catalog
  properties. Still PATCH-FREE; api.version still `1.0`. (Adjacent, not our path: #66344 fixes the
  TIMESTAMPTZ *arrow*/Flight-SQL reader — NOT the BE parquet reader, so the timestamptz friction
  stands.) Compile-verified only; not re-smoked (no SPI-surface change vs the 2026-07-31 full pass).

- **2026-07-31 → MIGRATED TO apache/doris `master`, pin `ded91fb9fb3`** (`[fix](ci) Skip
  usage-limited Codex review accounts (#66319)`). The connector SPI was merged upstream, so we
  retired the brikk fork branch `branch-catalog-spi` and now build the FE + the `~/.m2` compile
  jars straight from **apache/doris master** (`~/DEV/OSS/doris`). Master is +316 commits over the
  old fork merge-base (#65299); the fork's 13-commit P0–P6 series is now redundant (upstream
  landed its own SPI). **Zero plugin `.kt` changes** — main + test compile clean against master's
  SPI; the ~7k-line api/spi surface churn (ConnectorScanRangeType→ConnectorScanRequest/Profile,
  ConnectorContext refactor, new ConnectorStorageContext/ForwardingConnectorContext,
  ScanNodePropertyKeys) doesn't touch the subset the plugin uses. Still PATCH-FREE; api.version
  still `1.0`.
  - **Build:** `JAVA_HOME=<jdk17> DORIS_THIRDPARTY=<any doris thirdparty w/ thrift+protoc>
    DISABLE_BUILD_UI=ON ./build.sh --fe`, then
    `cd fe && <mvn> install -P flatten -pl fe-connector/fe-connector-api,fe-connector/fe-connector-spi,fe-thrift -am -DskipTests`.
  - **Smoke: FULL PASS** on the master-built FE overlay (reads, W1 DDL, W2/W2c/W3 INSERT/CTAS/bucket,
    §12b DEFAULT backfill, §13 GC) + `corpusReplayTest` green. Known-blocked unchanged (both
    pre-existing, upstream/BE — NOT connector): §8b-count bare `COUNT(v)` (`colUniqueId=-1`) and
    Step-7 delete BE parquet-nullability (`Not nullable column has null values`).

- **2026-07-29 → pin `0da96f1ad3e`, subject `[chore](handoff) record the 2026-07-30 rebase
  onto 794d514479e (upstream #65991)`** (SHAs churn on rebase — match the subject). Bumped
  from `a0c10f0672b`. Still PATCH-FREE (unchanged since #66135). Driver: the Doris team is
  about to use this connector as their first external SPI test case, so we want to be current.
  - **⚠️ #66211 (`88abe41a4e3`) — fail-closed plugin API-version gate. Every plugin author
    hits this.** The FE now rejects any directory-loaded connector plugin whose factory JAR
    does not declare a `Doris-Connector-Plugin-Api-Version` MANIFEST main attribute. The
    kernel expects major version 1; the SPI ships `1.0` in
    `META-INF/doris/connector-plugin-api-version.properties`. Absent = refused at load
    (`STAGE_API_VERSION`). We added the attribute to our `jar` task (`build.gradle.kts`);
    verified in the zip and live (FE load summary `failureCount=0`, ducklake registered).
    Bump the stamped value when the SPI baseline's major changes.
  - **Other commits checked, no action:** `3d88dcb32db` CTAS-atomicity port (admission now
    checks connector `getWritePlanProvider()` + INSERT — we satisfy both; fe-core-internal
    otherwise); `486ce433609` dead storage/credential surface deletion (our `s3.*`/`AWS_*`
    forwarding untouched); iceberg/hive/paimon-only fixes; plugin system-table pin fixes (we
    have no system tables).
  - **Zero source (`.kt`) changes this bump.**
  - **Smoke: FULL PASS.** All green incl. bucket-partitioned no-`ENGINE=` CREATE TABLE,
    W2/W2c/W3, DEFAULT backfill, GC. Known-blocked unchanged: §8b-count `COUNT(v)`
    (`colUniqueId=-1`) and Step-7 delete nullability.
- **2026-07-27 → pin `a0c10f0672b`, subject `[chore](handoff) record the 2026-07-27c rebase
  onto e7b7f1d1359 (upstream #66004 storage facade)`** (SHAs churn on rebase — match the
  subject). **First PATCH-FREE build.** #66135 (`fce5af4e041`) removed BOTH FE-patch anchors:
  `CatalogFactory.SPI_READY_TYPES` (a provider claiming its type is enough — "installing a
  plugin is all it takes") and `CreateTableInfo.pluginCatalogTypeToEngine` (`ENGINE=` is now
  optional/connector-owned via `ConnectorProvider.acceptedCreateTableEngineNames()`, default
  empty; PARTITION BY / DISTRIBUTED BY validation is the connector's job; `displayEngineName()`
  defaults to `getType()`). `ducklake-fe.patch` is now obsolete/history.
  - **Two behavior wins, both verified live:** (1) no whitelist — `CREATE CATALOG type=ducklake`
    works on the UNPATCHED FE; (2) no ENGINE padding — W1's bucket-partitioned
    `CREATE TABLE ... PARTITION BY LIST (bucket(4, name)) ()` (no `ENGINE=`) succeeds on the
    generic path, transform recorded `bucket(4)`; `SHOW TABLE STATUS` shows `Engine: ducklake`.
  - **SPI churn our plugin adapted to** (all mechanical, behavior identical): `planScan` overloads
    collapsed into `ConnectorScanRequest` (+ `getDeleteFiles(TTableFormatFileDesc)`, `getScanRangeType`
    removed); `ConnectorScanRange` lost `getRangeType`/`getDeleteFiles` overrides;
    `ConnectorMvccSnapshot.Builder` lost `timestampMillis`; `supportsCreateDatabase` removed,
    `dropDatabase` gained a `force` arg (rejected — no CASCADE); `ConnectorPropertyMetadata`
    removed → plain `REQUIRED_KEYS`; tests adapted (planScan shims, `ConnectorPartitionSpec` 3rd
    arg List→Boolean, `getWriteContext`→`getStaticPartitionSpec`, `ConnectorType.of("STRUCT")`→`structOf`).
    239 tests + detekt green.
  - **Smoke: FULL PASS.** Reads green, W1/W2/W2c/W3 green, S3 reads green. **Known-blocked unchanged:**
    §8b-count `COUNT(<nullable col>)` (colUniqueId=-1) and Step-7 delete nullability — both pre-existing
    upstream, tracked in `../dev-docs/TODO-read.md`.
- **2026-07-22 → tip subject `[fix](catalog) migrate rebased-in PhysicalStorageLayerAggregateTest
  to PluginDrivenExternalTable`** (was `d56c8f356c3`; SHAs churn on rebase — match the subject).
  Bumped from `568c4bb4571` past 5 new catalog commits (another rebase). Committed
  `ducklake-fe.patch` applied **`git apply --check` clean** (both anchors survived); FE built, SPI
  jars reinstalled, plugin `check` green, overlay rebuilt with jar SHA parity. **Unlike prior bumps,
  this one required TWO connector source fixes** (both runtime, not compile) + surfaced one upstream
  blocker — live smoke was essential to catch them:
  - **FIX 1 (required) — bundle the iceberg SDK in the plugin zip.** `#65893` stripped the iceberg
    SDK from fe-core, so our INSERT/CTAS hit `NoClassDefFoundError: org.apache.iceberg.types.Types$IntegerType`
    at write-plan time (our `DuckLakeIcebergSchema`/`DuckLakeWritePlanProvider` use `Types`/`SchemaParser`/
    `PartitionSpecParser`). Moved iceberg-api/-core from `compileOnly` → `implementation` (child-first,
    Avro excluded — FE-provided), mirroring how fe-connector-iceberg now owns its SDK. `build.gradle.kts`.
  - **FIX 2 (correct, insufficient) — `tSink.setCollectColumnStats(true)`.** `#65782` added the
    `TIcebergTableSink.collect_column_stats` flag (defaults false → BE skips footer column stats).
    DuckLake always wants them (read-path pruning + `ducklake_file_column_stats`), so we set it true.
    `DuckLakeWritePlanProvider`. (Does NOT fix the COUNT(col) blocker below.)
  - **⛔ BLOCKER (upstream, not connector-fixable) — bare `COUNT(<nullable col>)` on a plugin scan is
    non-deterministic** on this baseline (`4/0/3/…`, want `2`; `COUNT(*)`/`SELECT *`/mixed-agg all
    correct). Pushed-down single-column count keys per-column stats off the scan slot's `colUniqueId`,
    which is `-1` for plugin external columns; regressed by the `#65548`/`#65782` count-path port to
    the plugin-driven scan. Deterministically correct on `568c4bb`. Full writeup + fix options in
    `../dev-docs/ducklake-doris-friction.md` (2026-07-22); tracked in `../dev-docs/TODO-read.md`. Smoke
    marks §8b-count KNOWN-BLOCKED. **We adopt d56c8 anyway** — read/write/GC all green; only bare
    single-column count on a nullable column is affected, and data integrity is intact.

  Per-commit impact:
  - `9a0937651` port #65782 collect_column_stats sink flag + write-metrics → **fe-connector-iceberg
    only** (our writes use our own `DuckLakeWritePlanProvider`, not the iceberg connector — inert;
    the `collect_column_stats` sink flag is worth a later glance if we add write-side stats).
  - `76485a636` port #65784 authoritative iceberg name mapping → scan path (**iceberg-only**; we
    carry our own `DuckLakeSchemaDictionary` — inert).
  - `876bbbd5b` **#65893 — strip residual iceberg/hive/hudi deps from fe-core, delegate DDL
    validation to connectors, remove `hudi_meta` TVF.** The notable one. Behavioral / API notes:
    - fe-core `CreateTableInfo` **no longer** hardcodes the iceberg/paimon `DISTRIBUTE BY`
      rejection, iceberg sort-order validation, or hive `NOT NULL` rejection — those move into each
      connector's `createTable` (inline, throwing `DorisConnectorException`; no new generic
      `validateCreateTable` SPI hook). **We're covered:** `DuckLakeCreatePartitionMapper` already
      rejects `DISTRIBUTE BY`, and our `DuckLakeConnectorMetadata.createTable` validates its own
      columns/partitions. Patch #2 (ENGINE_ICEBERG padding) still applies clean and is still needed
      for engine-name padding + `checkEngineWithCatalog`.
    - **NEW capability gate:** `CreateTableInfo` now rejects `CREATE TABLE ... ORDER BY (...)` on a
      plugin catalog **unless the connector declares `ConnectorCapability.SUPPORTS_SORT_ORDER`**.
      We do NOT declare it (only `SUPPORTS_MVCC_SNAPSHOT`), so ducklake `ORDER BY` CREATE TABLE is
      now cleanly rejected instead of accepted-and-ignored (arguably more correct). If/when we do
      sorted writes (TODO-write phase W), declare `SUPPORTS_SORT_ORDER` and consume
      `ConnectorCreateTableRequest.getSortOrder()` → DuckLake `ducklake_sort_key` / `getSortKeys()`.
    - `ConnectorCapability.SUPPORTS_METADATA_TABLE` was **removed** (hudi-only; we never declared it
      — no compile break). `ConnectorMetadata.getMetadataTableRows` removed (we never implemented it).
    - Compile-surface churn: `ConnectorCapability`, `ConnectorMetadata`, `write/ConnectorWritePartitionField`,
      + thrift (`DataSinks`, `ExternalTableSchema`, `PlanNodes`, `Types`). All additive-enough — our
      plugin compiled + tested clean against the rebuilt SPI jars.
  - `ca840c9db` extract `fe-trino-connector-common` so fe-common no longer depends on Trino (build
    structure refactor; inert for us).
  - `d56c8f356` migrate `PhysicalStorageLayerAggregateTest` → `PluginDrivenExternalTable` (fe-core
    test-only; confirms our path is the storage-layer-aggregate / count-pushdown test target now).
- **2026-07-21 → tip subject `[perf](catalog) two-level cross-query cache for external
  partition derived views (#65829)`** (was `568c4bb4571`; SHAs churn on rebase — match
  the subject). Bumped from `b2dff681aad` past 4 new catalog commits (full rebase; all
  hashes changed). **Connector unaffected — recompiles with zero source changes:**
  the only `fe-connector-api`/`-spi` deltas are **100% additive `default`s / a new
  optional interface** — `ConnectorSession.getStatementScope()` (default `NONE`), the
  new opt-in `ConnectorStatementScope`, and `ConnectorContext.newStorageUriNormalizer()`
  (default delegates) — **zero removed/changed signatures, no thrift/gensrc change**.
  The committed `ducklake-fe.patch` applied to the fresh tip with **`git apply --check`
  clean** (both anchors survived, no re-diff needed). Per-commit impact:
  - `e697837760d` **port #65548 COUNT(\*)/COUNT(col) semantics** — the plugin-scan count
    gate moved from `getPushDownAggNoGroupingOp()==COUNT` (fired for BOTH COUNT(\*) and
    COUNT(col)) to `isTableLevelCountStarPushdown()` (COUNT **with empty count-slot
    list** = COUNT(\*) only). **Fixes a latent over-count our COUNT(\*) pushdown was
    exposed to** on the prior pin (a `COUNT(col)` on a nullable column would have been
    served `sum(record_count)`, ignoring NULLs). No connector change — the connector
    can't distinguish COUNT(\*) from COUNT(col) at the SPI (no count-slot info), so it
    correctly trusts the engine's `countPushdown` boolean, now gated right. See
    `../dev-docs/TODO-read.md`.
  - `1ea735ff0a5` **iceberg deletion-vector metadata validation (#65676)** — validates
    puffin DV blob offset/length bounds in `fe-connector-iceberg` only; **does NOT touch**
    our pending REQUIRED-vs-OPTIONAL parquet position-delete nullability blocker (that
    remains open). Inert for us.
  - `777a61671ab` **hot-path caching + per-statement metadata funnel** — iceberg/hive
    caching + the additive API above. For us: fe-core now funnels `getMetadata(session)`
    once per statement and shares it across resolvers (safe — our metadata wrapper is
    immutable; minor perf win). Optional future opt-in: memoize our own catalog/table
    loads via `session.getStatementScope()`.
  - `568c4bb4571` **two-level partition derived-view cache (#65829)** — fe-core perf;
    `PluginDrivenMvccExternalTable` now implements `SupportBinarySearchFilteringPartitions`.
    Inert for us TODAY: Cache B only engages when the table exposes **Nereids-level
    partition items** (`getNameToPartitionItems`/`SortedPartitionRanges`), and our
    connector prunes at the **file level in `applyFilter`** (stats + bucket), not via
    Nereids partition items — so no ranges are cached. No breakage. **Future
    optimization** (tracked in `../dev-docs/TODO-read.md`): if we ever surface Nereids
    partition items, this cross-query cache engages for free.
- **2026-07-18 → tip subject `[feat](catalog) fe-connector-iceberg: port #64966 REST
  401 re-auth to the connector`** (was `b2dff681aad`; SHAs churn on rebase — match the
  subject). Re-diffed after the **Hive P11 migration** (`[refactor](catalog) Catalog spi
  11 hive (#65473)`, 791 files) + fe-core dead-code removals. **Connector unaffected:**
  `fe-connector-api`/`-spi` grew ~1,884 lines but **100% additive `default`s — zero
  removed/changed signatures**, and **no thrift/gensrc change**, so the plugin recompiles
  with zero source changes. **Both FE-patch anchors survived, patch re-diffed (not
  rewritten):** patch #1 — upstream added `"hms"` to `SPI_READY_TYPES`, so the context
  line changed (`…"iceberg", "hms"`) and our append is now `…"hms", "ducklake"`; patch #2 —
  `pluginCatalogTypeToEngine()` relocated (hunk moved `@@ -941 …` → `@@ -931 …`) and gained
  a `case "hms" → ENGINE_HIVE`, our `case "ducklake" → ENGINE_ICEBERG` still slots in right
  after the iceberg arm. Regenerated `ducklake-fe.patch` from pristine tip content and
  verified with `git apply --check` (clean). Full impact analysis:
  `../dev-docs/REPORT-doris-p6-iceberg-spi-cutover.md` §"2026-07-18". FE rebuild/re-image
  not yet run for this tip — the patch is ready to apply when we next build the FE.
- **2026-07-08 → `3ba75b7cf8a`.** Bumped from `8b391c7` to the branch tip. Two new
  catalog commits on top of P6, **neither affecting our connector**:
  `34bd8eede75` "jdbc: keep driver classloaders alive per URL to stop Metaspace
  leak" (touches `fe-connector-jdbc`, which we don't use — but the same leak class
  applies to our own `Class.forName("org.postgresql.Driver")`; tracked as a TODO in
  `../dev-docs/TODO-read.md`), and `3ba75b7cf8a` "drop dangling MaxComputeExternalTableTest"
  (fe-core test-compile fix). **`fe-connector-api`/`-spi` unchanged since `8b391c7`**,
  so the connector recompiled with **zero** source changes (unlike the P6 rebuild's
  3 compile-break fixes); thrift changes in the gap don't touch our iceberg types.
  Patch #1 (`CatalogFactory`) applied clean; patch #2 (`CreateTableInfo`) needed only
  a line-offset refresh (`--3way`), regenerated here. FE rebuilt, SPI jars re-installed
  to `~/.m2` (`-P flatten`), plugin zip + `doris-fe:pr62767-local` overlay image rebuilt,
  module suite + detekt + checkAbi green.

## Build + rebuild (PATCH-FREE, current)

No patch step — `ducklake-fe.patch` is historical. Build the exact current pin; do
not build a blind moving tip.

```bash
cd ~/DEV/OSS/doris && git fetch origin master && git switch --detach 8fc58e929b2151c9ff4ae71d07375a7f8e944697
JAVA_HOME=<jdk17> DISABLE_BUILD_UI=ON ./build.sh --fe --clean
# then re-install the SPI artifacts our gradle build compiles against (mavenLocal):
#   cd fe && <mvn> install -P flatten -pl fe-connector/fe-connector-spi,fe-thrift -am \
#     -Dmaven.test.skip=true -Dmaven.build.cache.enabled=false \
#     -Ddoris.thrift.executable=<thrift-0.24-bin>
# (stale ~/.m2 SPI jars => connector compiles against old API, NoSuchMethodError at FE load)
# re-image the overlay (FROM apache/doris:fe-4.1.4, COPY ./output/fe):
docker build -f compose/fe-overlay/Dockerfile \
  -t doris-fe:pr62767-local \
  --build-arg BASE_IMAGE=apache/doris:fe-4.1.4 --build-arg OUTPUT_PATH=./output <disk-backed-staging>
# then tear the cluster down (-v) and rerun compose/smoke.sh so the fresh FE loads.
```

## The patches (`ducklake-fe.patch`) — HISTORICAL, no longer applied

Both anchors below were removed by upstream #66135 (2026-07-27); the FE builds patch-free.
Kept for the record of what the two asks were.

### 1. `CatalogFactory.SPI_READY_TYPES` += `"ducklake"`  — the route/write gate
`fe/fe-core/src/main/java/org/apache/doris/datasource/CatalogFactory.java`

Whitelists `type=ducklake` as an SPI-driven catalog. Without it
`CREATE CATALOG ... type=ducklake` → "Unknown catalog type", and INSERT/DDL are
never routed to the connector. This is the gate the W2/W2c INSERT smokes already
depend on. (Tracked in `../dev-docs/ducklake-doris-friction.md`, 2026-05-19 "SPI_READY_TYPES
whitelist silently drops unknown ConnectorProviders".) As of the 2026-07-18 tip the
upstream set is `{jdbc, es, trino-connector, max_compute, paimon, iceberg, hms}`
(Hive P11 added `"hms"`) — still a hard-coded set, no connector-declared registration
seam.

### 2. `CreateTableInfo.pluginCatalogTypeToEngine` += `case "ducklake" → ENGINE_ICEBERG`  — the CREATE TABLE gate
`fe/fe-core/src/main/java/org/apache/doris/nereids/trees/plans/commands/info/CreateTableInfo.java`

`paddingEngineName()` pads a legacy engine name for a no-ENGINE `CREATE TABLE` on
a plugin catalog; `pluginCatalogTypeToEngine()` only mapped `"max_compute"`, so
every other plugin type (including `"ducklake"`) fell to `default → null` and the
else-branch threw **"Current catalog does not support create table"**
(`CreateTableInfo.java:928`) — *before* the connector was ever consulted. This is
purely an FE engine-padding gap; `PluginDrivenExternalCatalog.createTable()` is
generic (it converts the request and calls `metadata.createTable`), and the
connector mapping is headless-green (`DuckLakeDdlTest`, 96 tests).

Padding **`ENGINE_ICEBERG`** is the correct fix, not just a non-null placeholder:
- DuckLake is Iceberg-shaped — the BE sink is a `TIcebergTableSink` and
  partitioning uses the Iceberg transform family (`bucket`/`year`/`day`/…).
- The iceberg engine path is the one that **accepts `PARTITIONED BY (bucket(N, col))`**
  and **rejects `DISTRIBUTE BY`** (`CreateTableInfo.java:792`), which exactly matches
  the connector's own `DuckLakeCreatePartitionMapper` contract (murmur3 bucket only
  via the iceberg-transform path; CRC32 `DISTRIBUTED BY` rejected).
- `checkEngineName()` accepts `ENGINE_ICEBERG` and marks the table external; the
  catalog-engine consistency check (`checkEngineWithCatalog`, line 396) calls the
  same `pluginCatalogTypeToEngine`, so it stays consistent automatically.
- Routing is by catalog **instance** (a `PluginDrivenExternalCatalog`), not by the
  engine string, so the padded name never diverts CREATE TABLE to the native
  Iceberg DDL handler — it stays on the generic connector path.

Read-side engine display (`PluginDrivenExternalTable.getEngine()/
getEngineTableTypeName()`) is intentionally **left generic** for ducklake: the read
path is already shipped/green and some BE dispatch keys on the literal engine
string, so we don't perturb it for a write-DDL fix.

P6 note: upstream added `case "iceberg" → ENGINE_ICEBERG` to the same switch,
so our patch is now literally one more case-arm beside it. Mapping to
ENGINE_ICEBERG additionally opts ducklake CREATE TABLE into (a) catalog-level
`table-default/override.format-version` + row-lineage-column validation and
(b) `ORDER BY (...)` sort-order acceptance (flows into
`ConnectorCreateTableRequest.getSortOrder()`). Acceptable; watch for
iceberg-only validation semantics that don't fit DuckLake.

**Upstream ask:** generalize `pluginCatalogTypeToEngine` (and the read-side
switches) to consult the connector's declared capabilities/engine rather than a
hardcoded per-type switch, so a new SPI full-adopter doesn't need an FE edit.
(P6's own javadoc on the switch acknowledges the sync burden; the
`RowLevelDmlRegistry` design doc hints capability-keyed engine dispatch is
planned but not present at `8b391c7`.)
