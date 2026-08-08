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
 > pin: **`b42e1ab294b`** (apache/doris master, 2026-08-08). Master's `<revision>` is still
 > `1.2-SNAPSHOT`, so the installed `~/.m2` coordinates are unchanged.
 > **SPI-surface note:** #66407 merged `fe-connector-api` INTO `fe-connector-spi` and renamed the
 > `org.apache.doris.connector.api.*` packages to `…spi.*`. We now depend on **only** the
 > `fe-connector-spi` artifact and our imports moved `api.` → `spi.` (mechanical). Still
 > **PATCH-FREE** (unchanged since #66135), and the `Doris-Connector-Plugin-Api-Version` manifest
 > gate (#66211) is still `1.0` on both sides. Keep this note, the Re-vendor log, and
 > `compose/README.md` in sync.

### Re-vendor log

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

## Build + rebuild (PATCH-FREE)

No patch step — `ducklake-fe.patch` is historical (see the warning box). Just check out
the pin and build pristine.

```bash
# ⚠️ PIN (branch-catalog-spi REBASES — don't build from a blind branch tip). Pin (2026-07-29):
#    0da96f1ad3e, subject "[chore](handoff) record the 2026-07-30 rebase onto 794d514479e
#    (upstream #65991)". SHA GC'd? check out the commit with that exact subject.
cd ~/DEV/OSS/doris-catalog-spi && git checkout -- . && git checkout 0da96f1ad3e   # pristine, NO PATCH
JAVA_HOME=<jdk17> DISABLE_BUILD_UI=ON ./build.sh --fe                 # ~2 min incremental
# then re-install the SPI artifacts our gradle build compiles against (mavenLocal):
#   cd fe && <mvn> install -P flatten -pl fe-connector/fe-connector-api,fe-connector/fe-connector-spi,fe-thrift -DskipTests
# (stale ~/.m2 SPI jars => connector compiles against old API, NoSuchMethodError at FE load)
# re-image the overlay (FROM apache/doris:fe-4.1.0, COPY ./output/fe):
docker build -f compose/fe-overlay/Dockerfile \
  -t doris-fe:pr62767-local \
  --build-arg BASE_IMAGE=apache/doris:fe-4.1.0 --build-arg OUTPUT_PATH=./output <staging>
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
