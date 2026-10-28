# Duckbridge handoff: Doris 62e3d81a / connector API 24 / JNI API 6

## Status

**Matching pristine FE/full ASAN BE built and DuckLake-native runtime qualified.**
Two isolated smokes, persisted-metadata restart and retained TopN/type/default/write
probes passed. This is build-reuse information, **not Duckbridge runtime qualification
or an instruction to begin adoption**. Final delivery is recorded below.

## Shared source and build inputs

- Full source pin: `62e3d81a42b2b74ee2a5a577969df62edee06b78`, Apache Doris master.
  It is 410 commits beyond our shared `dd456ab7a89` pin.
- Connector API **24.0**, JNI API **6.0**, BE execution version **19**,
  Thrift **0.24.0**, Maven revision still `1.2-SNAPSHOT`.
- Pristine build worktree: `/home/jayson/DEV/OSS/doris-ducklake-api24-62e3d81a`.
  Existing shared staged Duckbridge work at `/home/jayson/DEV/OSS/doris` was not
  switched, reset or built over. No Duckbridge baseline, patch or repository was edited.
- Immutable build environment:
  `apache/doris@sha256:4d63f788e85d35ab4f29804e3a4cdd93fe0c70c4ce954e6fbf333ac21a45ab9d`.
  JDK 17.0.2, Thrift 0.24.0, protoc 3.21.11; Lance-C `a51ab47a9c814a746cb1714c99fad93dd3a188b8`,
  gperftools 2.18.1, librdkafka 2.16.0. Exact archive hashes and evidence are in the
  [DuckLake validation report](VALIDATION-DORIS-API24-2026-10-28.md).
- Builds are ASAN correctness builds, **not release-performance artifacts**.
  Heavy phases ran sequentially with six workers; FE memory 10 GiB/Maven heap
  5 GiB, BE memory 18 GiB/memory+swap 24 GiB/Maven heap 4 GiB. All staging is on disk.

## Reusable artifacts

Let `W=/home/jayson/DEV/OSS/doris-ducklake-api24-62e3d81a`.

| Artifact | Location / identity |
|---|---|
| Rebuilt SPI reactor closure | `$W/output/doris-api24-m2/`; archive `doris-api24-m2.tar.gz` |
| Closure archive SHA-256 | `46e5a081938c68a592b094fb0ff7b6c5cddf1a60a38a5dc7d3f461115808fa1c` |
| SPI JAR SHA-256 | `3fc0048de4ea8d312e68d9582260c2c89c757dd5a8335774a65c5181be06d360` |
| Thrift JAR SHA-256 | `37383ca16142bf301dc8fe95d64599ce87ec1689b14a75c82ba893d530df7b0f` |
| Pristine FE | `$W/output/fe/`; `doris-fe:ducklake-62e3d81a-api24` |
| FE image ID | `sha256:f41c6a1710c66f0d0ab0334d5431af85c2002e8399de09ae10b8559bccb8c22a` |
| FE JAR SHA-256 | `7c5f86551790edbdbd8f56b38a5435d81391dbdec7a65624f58539d9284662a5` |
| Native BE SHA-256 | `0421684d039254bc13dee865d33695cc1a14fd3ed923bcd40acb493d577c43ac` |
| Pristine complete BE image | `doris-be:ducklake-62e3d81a-api24-asan` |
| BE image ID | `sha256:ccca761cae5bc1e6122b4449c21d01117d74a095ce203cf9f4d27094ede8a2de` |
| JNI SPI SHA-256 | `6768ac6d119fdeb9d9d83637ce13fb92f19535bfee1e6ffb59fcdfe08226edfd` |
| JNI bootstrap SHA-256 | `8b2ec4fe99a059c1c02cb75705fa8feb59fc4a8b722a39407f43e42d31d9219b` |

All 823 FE/native/shared/plugin artifact hashes are in
`$W/output/api24-engine-manifest.json`; the complete JNI tree is at
`$W/output/be/plugins/jni/`, with shared SPI/bootstrap at `output/be/lib/jni/spi/`.
All nine plugins pass all six upstream static layout checks; the native executable
passes the glibc 2.17 gate. This is static/build evidence, not JDBC runtime evidence.
The actual FE SPI/Thrift JARs are byte-identical to the SDK export.

The Maven export includes only the eight modules rebuilt by the SPI reactor:
`fe`, `fe-foundation`, `fe-filesystem`, `fe-filesystem-api`, `fe-extension-spi`,
`fe-thrift`, `fe-connector`, `fe-connector-spi`. It does not export unrelated stale
Doris modules from the dependency cache. Per-file hashes: `$W/output/api24-m2-manifest.json`.

Your source of truth remains `doris-patches/BASELINE`; your compile repository remains
`doris-duckbridge/doris-m2/`, **not `~/.m2`**. Back up and deliberately stage the
exported closure there only when you choose to adopt the new pin. No automatic
copy, manifest change or adoption has been performed on your behalf.

## Duckbridge-specific work still required

`git apply --check` of your canonical
`doris-patches/be/0001-duckdb-type-handler.patch` succeeds on the pristine new source.
It was **not applied** to the DuckLake build. `DuckDbTypeHandler` remains absent
upstream, and `JdbcTypeHandlerFactory` has changed since API 13; inspect and test the
actual new context rather than assuming applicability equals semantic qualification.

1. Use a separate worktree at the exact full pin; preserve your staged/shared checkout.
2. Check/adapt and apply your Java-only handler patch there. Update your API manifest
   and compile against the exact candidate closure, keeping existing capability gates.
3. You may reuse our pristine FE and native `doris_be` only after confirming identical
   source/native build inputs and that your local delta is still Java-only. This can
   avoid repeating the multi-hour C++ build.
4. Rebuild the **complete matching Java shared layer and JNI plugin tree at API 6**
   from your patched exact-pin source, retaining the upstream packaging/layout gates.
   Use the upstream `build.sh` entry point with its appropriate Java-extension target;
   do not splice an old API-5 scanner JAR into the API-6 distribution.
5. Package a separately named complete patched BE distribution/image, preserving its
   source/patch/native/JNI hashes, and perform your own JDBC/Quack live qualification.

The pristine DuckLake BE image is **not** Duckbridge-ready merely because the native
binary can be reused. This handoff does not authorize a migration, new dependency
pin, source workaround, release, deployment or new feature enablement.

## Compatibility and qualification boundaries

- API-13 connector ZIPs fail the API-24 FE gate. API equality is not wire compatibility:
  `#68837` changes external report field 34 and moves Paimon original-file-path to 20.
  Keep FE/BE source identities and the complete Java/plugin deployment matched.
- New security-surface declarations and diagnostic contracts merit connector-specific
  adoption review, especially JDBC properties; no Duckbridge security review is claimed.
- DuckLake's headless checks passed with unchanged catalog 0.12.0: Java 25 **254 passed,
  1 skipped**, Java 17 **228 passed, 1 skipped**, plus **19** cluster-free smoke tests.
  Two full smokes passed on matching `62e3d81a42b` engines, including OPTIONAL deletes,
  reads/counts, DDL, INSERT/CTAS/buckets and run-owned maintenance/foreign-file checks.
  FE restart retained persisted identity and cluster ID; all targeted probes still
  passed. This includes 300 exact DEFAULT queries, TopN across a three-row-group file
  with real position deletes, and pre-epoch microsecond timestamp / BLOB byte checks.
  Native S3 left the JVM/plugin registry uninitialized; no observed sanitizer/crash
  diagnostic in the captured BE logs. See the linked report for exact evidence.
- Native S3 smoke is not JDBC coverage. You still own native UUID/LARGEINT/ARRAY/BLOB,
  TIMESTAMPTZ and timezone semantics, sequential/concurrent Quack scans, lazy JDBC
  plugin load and plugin-failure isolation validation.
- Latest stable remains 4.1.5; the inspected branch-4.2 still has no connector SPI.
  These artifacts remain master-development correctness artifacts.

## Reproduction, evidence and rollback

Exact build commands/container limits are retained in `$W/output/api24-build.sh`
and `api24-image.sh`; logs are `api24-{spi,fe,be}-build.log` and image logs/JSON.
The CDC client's Maven subprocess requires the pin-local repository in `MAVEN_OPTS`
as well as the top-level build's `MVN_OPT`; the runner records this environment-only
fix. The initial packaging failure log is retained, not hidden by disabling CDC.

Existing API-13 images, old metadata/storage volumes and your project-local repo are
retained. DuckLake rollback copies and inventories are under `$W/output/rollback-api13/`;
your historical patched API-13 BE remains `doris-be:duckbridge-local` unless your own
project deliberately changes it. Do not use DuckLake's pristine API-13 BE as a
substitute for your patched rollback image.

## Delivery

All build/live/default-build gates passed. Informational handoff dispatched on
2026-10-28 to Duckbridge's `doris-upstream` session
`ses_f4491b970ffeOAw5swIcPceRUx` (`promptDispatched=true`), with an explicit instruction
not to start adoption, edits, builds, tests or deployment. The Duckbridge project
also retains knowledge note `3f0ba199-8afa-4a44-bac5-e6a32c3819fc` linking this file
and the artifact manifests. Delivery is recorded; recipient acknowledgement is not claimed.
