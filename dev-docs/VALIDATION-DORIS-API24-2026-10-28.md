# Doris API-24 re-vendor qualification — 2026-10-28

## Status

**Matching FE/ASAN-BE live qualification and persisted-metadata restart passed.**
The tested eight-module compile closure is promoted to DuckLake's `mavenLocal()`;
compose defaults select the source-qualified API-24 images. Old API-13 images,
artifacts and volumes are preserved. No production rollout or performance
qualification is intended. Final default-build checks passed; Duckbridge delivery
is recorded separately below.

## Frozen inputs and isolation

- Apache Doris: `62e3d81a42b2b74ee2a5a577969df62edee06b78` (master, October 28),
  410 commits beyond `dd456ab7a89`; connector API **24.0**, JNI API **6.0**,
  BE execution version **19**, Thrift **0.24.0**, Maven revision `1.2-SNAPSHOT`.
- Build worktree: `/home/jayson/DEV/OSS/doris-ducklake-api24-62e3d81a`.
  The main Doris checkout remains at `8fc58e929b2` with its staged Duckbridge work intact.
  No Doris production patch is applied.
- Build image: `apache/doris@sha256:4d63f788e85d35ab4f29804e3a4cdd93fe0c70c4ce954e6fbf333ac21a45ab9d`,
  created October 27. JDK 17.0.2, Thrift 0.24.0, protoc 3.21.11.
- Image provenance: Lance-C archive strings identify
  `a51ab47a9c814a746cb1714c99fad93dd3a188b8`; pkg-config identifies
  gperftools 2.18.1 and librdkafka 2.16.0. Archive SHA-256:
  Lance-C `d09929c6aa5d9fb5683016351397109b565886aba4433935eb85b3a5c626cdc4`;
  librdkafka `f135c0fe5ec5bf9e5f7f59d53d8b2d9a48b6ac821c964d615ec0d253dc0988de`;
  tcmalloc `67de23bf320d2f366f435dad13037191b159c7dbe020a828357b1120c20f64cc`.
- Preflight: 26 GiB RAM, 8 GiB swap, approximately 1.1 TiB free disk; no running
  Docker services. Heavy Maven/Gradle/FE/BE phases run sequentially at bounded
  concurrency. Logs, Maven staging and image contexts are disk-backed, not `/tmp`.
- Rollback inventory and copies of old plugin/Maven artifacts:
  `<worktree>/output/rollback-api13/`. Existing API-13 images and volumes are retained.
  Fresh qualification FE/BE volumes do not modify the old metadata/storage.

## Source/SPI boundary

The source audit covers connector SPI and its frozen surface, filesystem and extension
APIs, JNI API, Thrift/protobuf and third-party changes. SPI additions are mostly default
methods; the streaming entry point now takes `ConnectorScanRequest`. DuckLake does not
override the removed signature. No new capability, batching/streaming, cache/native
partition hook, nanosecond mapping, property DDL or row-level DML is enabled.

`#68837` changes external report field 34 to Paimon commit messages and moves Paimon's
original-file-path field to 20. Tests now pin those wire facts in addition to the
existing query-option and Iceberg sink IDs. API-major equality alone is not a
matching FE/BE qualification.

Matching SPI/Thrift and reactor prerequisites were rebuilt with JDK 17, flatten enabled,
Maven build cache disabled, six workers and a pin-local `output/m2` repository. The
`-PdorisRepository=<path>` override is exclusive to `org.apache.doris`; it does not
redirect catalog or unrelated dependencies. Duckbridge's project-local repo is untouched.

| Artifact | SHA-256 |
|---|---|
| SPI | `3fc0048de4ea8d312e68d9582260c2c89c757dd5a8335774a65c5181be06d360` |
| Thrift | `37383ca16142bf301dc8fe95d64599ce87ec1689b14a75c82ba893d530df7b0f` |
| Plugin ZIP | `01e576a7480bfd991cae9c6d4399340e17897cec864de4a68ec680bb72ac6c6a` |

## Connector checks completed

Unchanged released catalog **0.12.0**, DuckDB JDBC **1.5.6.0**; the opt-in R07
candidate lane and its separately dated API-13 live harness were not selected.

- Java-25 `check assemble qualificationDependencies --rerun-tasks`: **254 passed,
  1 skipped, 0 failures/errors**; Detekt and Java-17 classfile closure gate passed.
- Isolated Java-17 plugin suite: **228 passed, 1 skipped, 0 failures/errors**.
- Cluster-free smoke-driver/lake suite: **19 passed**. A new regression rejects
  a matched but wrong-source pair before lake allocation; the FE/BE-skew guard remains.
- Actual packaged provider JAR declares **24.0**. ZIP inspection confirms no host
  SPI/Thrift/extension/filesystem/libthrift JARs are bundled.
- Existing actual-provider tests confirm read-default backfill and reusable ranges,
  synchronous planning and no new cache/native-partition opt-in.

Evidence under `<worktree>/output/`: `api24-spi-build.log`,
`api24-connector-java25.log`, `api24-connector-java17.log`,
`api24-connector-results.json`, `api24-smoke-unit.log`, copied XML/reports,
`api24-api-wire-build.diff` and `api24-toolchain-provenance.txt`.

## Engine and live gates

The pristine FE build passed via `build.sh --fe -j6`, JDK 17, six Maven workers,
10 GiB container memory and 5 GiB Maven heap. Only UI compilation was disabled;
Checkstyle, connector gates and filesystem/connector modules remained enabled.
FE JAR SHA-256: `7c5f86551790edbdbd8f56b38a5435d81391dbdec7a65624f58539d9284662a5`.
Source status remained clean after the build. Log: `api24-fe-build.log`.

The matching native BE compiled at ASAN `-j6` with the same pinned environment.
The glibc 2.17 gate passed; the executable's audited maximum GLIBC requirement is
2.10. All **9** JNI plugins passed all **6** static deployment-layout checks.
These checks do not establish that every JNI plugin loads or works at runtime.
The complete BE package includes CDC; its first packaging attempt failed because
the CDC Maven subprocess did not inherit `MVN_OPT`. Passing the same pin-local
repository through `MAVEN_OPTS` fixed the environment without source patches or
disabling CDC. Both attempt logs are retained. Two datasketches binary test fixtures
were line-ending-normalized by the upstream build; no production source changed.

| Engine artifact | Identity |
|---|---|
| FE image `doris-fe:ducklake-62e3d81a-api24` | `sha256:f41c6a1710c66f0d0ab0334d5431af85c2002e8399de09ae10b8559bccb8c22a` |
| BE image `doris-be:ducklake-62e3d81a-api24-asan` | `sha256:ccca761cae5bc1e6122b4449c21d01117d74a095ce203cf9f4d27094ede8a2de` |
| Native BE SHA-256 | `0421684d039254bc13dee865d33695cc1a14fd3ed923bcd40acb493d577c43ac` |
| JNI SPI SHA-256 | `6768ac6d119fdeb9d9d83637ce13fb92f19535bfee1e6ffb59fcdfe08226edfd` |
| JNI bootstrap SHA-256 | `8b2ec4fe99a059c1c02cb75705fa8feb59fc4a8b722a39407f43e42d31d9219b` |

The FE's actual SPI and Thrift JARs are byte-identical to the exported SDK: **137**
SPI classes and **6,814** Thrift classes match as well. Source-qualified image labels
name the full pin. `api24-engine-manifest.json` records **823** FE/native/shared/plugin
artifact hashes. The shared main-checkout staged patch remains byte-identical to
the rollback inventory.

### Live qualification

Both FE and BE report `doris-0.0.0-62e3d81a42b`; running FE/native-BE hashes match
the recorded output/image artifacts. The FE loads all 11 connector plugins and 14
filesystem plugins with `failureCount=0`, both at initial startup and after restart.

Two full isolated smokes pass without BLOCKED/CHECK/KNOWN-FAIL outcomes:

- `9f1088d848a647f9a83216e510e651e3`
- `f1ffb36ced4843c687b004e811d6cb13` (after persisted-metadata restart)

They cover TPC-H reads/counts, nullable COUNT (4/2), filter EXPLAIN, OPTIONAL file
position deletes (100 -> 93), database/table DDL, unpartitioned/bucketed INSERT,
CTAS and run-owned snapshot expiry/cleanup/orphan checks with foreign files retained.
Each owns only its `doris_smoke_<ID>` database/catalog and
`s3://ducklake/doris-smoke/<ID>/`. Shared substrate infrastructure is reused, not
the shared metadata database or warehouse. No old FE/BE volume is upgraded in place.

Targeted retained probes before **and** after restart:

- Four ASC/DESC × NULLS FIRST/LAST TopN result sets match independent DuckDB 1.5.6
  over one Parquet data file with **three row groups** and a real position-delete
  file. Counts and MIN/MAX match too. Scanner V2 is enabled; EXPLAIN shows the
  plugin scan's TopN filter target. These are correctness checks, not speed claims.
- Pre-epoch/fractional microsecond timestamps and BLOB embedded-NUL/trailing-zero
  bytes match the oracle. Current type mapping is unchanged; this does not qualify
  nanosecond round trips.
- DEFAULT exact projection `(1,42),(2,42),(3,42),(4,99)` and both equality/combined
  predicates passed **50 rounds per side of restart: 300 exact-result queries**.
- Reversed same-type and partial-column INSERT read `(aye,bee),(NULL,partial)`;
  reversed bucket-column INSERT read `(1,alice),(2,bob)`. Independent DuckDB
  read-back agrees; fixtures remain in the first catalog.

The actual FE restart retained name
`fe_ae1e46e9_a084_4dca_96cc_b20ea0b410a3`, address `172.30.124.10`, cluster ID
`1376005475` and the exact source version; startup time changed and FE/BE returned
alive. Native S3 left `registryInitialized=false`, no JNI plugin loaded and no
`jvm_*` metrics. Captured BE logs show no sanitizer/crash diagnostic in these probes.

Evidence includes `api24-smoke{,-after-restart}.log`, `api24-probe-oracle.json`,
`api24-probes{,-after-restart}.log`, `api24-topn-explain.txt`, restart JSON/logs,
`api24-final-runtime.txt`, `api24-jni-status.json` and `runtime-logs/` in the worktree.

### Promotion and rollback

The eight-module SDK export was copied to the existing `~/.m2/repository` layout
and every exported file hash verified. No global Maven setting or Duckbridge repo
was changed. Generic `doris-fe:pr62767-local` / `doris-be:master-local` tags remain
API-13 rollback references; new defaults use explicit source-qualified tags and
`doris-ducklake-api24-62e3d81a-*` metadata/plugin/storage/log volumes. The historical
R07 compose is marked API-13-only, not silently repinned to a new runtime.

Final default `check assemble qualificationDependencies` passed using the promoted
`mavenLocal()` closure; test/package tasks were up-to-date against the identical
artifact bytes, and the Java-17 runtime gate checked **24,630 classes**. The ZIP is
byte-identical to the one deployed in both live smokes. Final Python **19/19**,
shell syntax, compose rendering and diff-whitespace checks pass. Logs:
`api24-promoted-check.log` and `api24-final-smoke-unit.log`.

Rollback: retain/restore the API-13 source/configuration and old plugin volume with
its matching FE/BE image IDs from `rollback-api13/inventory.json`. The preflight
Maven closure and plugin ZIP/JAR copies are there as well. Do not pair an API-24
ZIP with the old FE or reuse old metadata/storage as an assumed validated upgrade.
The new isolated cluster/fixtures are retained for inspection; nothing was volume-deleted.

## Scope limits

F08 historical deletion visibility, F11/F12 mapping gaps and production inline-row
transport remain open. New native timestamp support does not change our microsecond
mapping. Arrow load support is not an FE-inline-rows SPI. No broad corpus cleanup,
R07 campaign, dependency repin, migration, new skip, Duckbridge deployment or release
benchmark was performed. The FE core Parquet version is **1.18.1**, while our
compile/test writer pins remain 1.17.0; the shared FE lib directory no longer has
a standalone `parquet-avro` JAR. Experimental FE-side inline-writer class loading
was not qualified by these native-file checks and needs its own classpath/adoption
work; no extra writer dependency was bundled as an unrequested workaround.

## Duckbridge completion gate

The source-qualified durable [build-reuse handoff](HANDOFF-DUCKBRIDGE-DORIS-62e3d81a-API24.md)
was delivered on October 28 via an informational prompt to Duckbridge's
`doris-upstream` session `ses_f4491b970ffeOAw5swIcPceRUx` (`promptDispatched=true`).
The Duckbridge project's knowledge note `3f0ba199-8afa-4a44-bac5-e6a32c3819fc` also
retains the artifact/reuse boundaries. No adoption/build/deployment was requested;
recipient acknowledgement is not claimed. Their handler patch and complete JNI-6
rebuild/qualification remain separate; the pristine DuckLake BE must not be described
as a Duckbridge-qualified distribution.

Read-only `git apply --check` of their canonical
`doris-patches/be/0001-duckdb-type-handler.patch` succeeds against the pristine
candidate. It was not applied. The Java DuckDB handler is still absent upstream; this
check is not a JDBC build/test or a runtime qualification.

The exported **eight-module rebuilt SPI reactor closure**, excluding unrelated old
Doris modules from the dependency cache, is `<worktree>/output/doris-api24-m2.tar.gz`
(SHA-256 `46e5a081938c68a592b094fb0ff7b6c5cddf1a60a38a5dc7d3f461115808fa1c`).
Its per-file checksums are in `api24-m2-manifest.json`.
