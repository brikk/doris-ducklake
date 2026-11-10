# Doris runtime and SDK pin

The connector SPI is not in any Doris release yet; the connector builds and runs against
a specific `apache/doris` master commit.

## Current pin

| | |
|---|---|
| Source | `9fb3e3a2a13f972a62cb999d5599fc8e8f17f306` (master; runtime short hash `9fb3e3a2`) |
| Connector API | 30.0 (`Doris-Connector-Plugin-Api-Version` in the plugin manifest must match the major) |
| Filesystem API / JNI API / BE exec max | 7.0 / 6.0 / 19 |
| Thrift | 0.24.0 |
| Maven revision | `1.2-SNAPSHOT` |
| FE image | `doris-fe:ducklake-9fb3e3a2-api30` — `sha256:abbc36e3dc4f3c528b6c6fbd2c162311075d26a0d21f58cb29bf35184686b9cd` |
| BE image | `doris-be:ducklake-9fb3e3a2-api30-asan` — `sha256:1f73d01a59e86a768e80bf035b6d49987ed3819103a7923e60ced398bf6d9bda` |
| SDK archive | `sha256:7317d2665f140d28336ee15e9b264e77c24d23e20d37458c535f26a8273e5c80` |

The images are local linux/amd64 builds, not registry publications. The BE is an ASAN
build: correct for the tested scope, not representative of release performance. The FE
builds patch-free. The qualification run that established this pin is
[VALIDATION-DORIS-API30-2026-11-10](archive/VALIDATION-DORIS-API30-2026-11-10.md).
This pin includes dictionary-encoded Parquet BOOLEAN reads (#69967), verified
through the connector and native S3 TVF. The new partition-view capability from
#67725 remains off and deferred in [TODO.md](TODO.md).

## SDK

Eight modules under `org.apache.doris` at `1.2-SNAPSHOT`: `fe`, `fe-foundation`,
`fe-filesystem`, `fe-filesystem-api`, `fe-extension-spi`, `fe-thrift`, `fe-connector`,
`fe-connector-spi`. The compile jars are byte-identical to the FE's own copies.

The build resolves `org.apache.doris` from `mavenLocal()` only (an exclusive repository
filter), or from `-PdorisRepository=<dir>` to try a candidate without disturbing `~/.m2`.
Never set a global `maven.repo.local`: it would also redirect the catalog dependencies.

On the qualification host the SDK is at
`/home/jayson/DEV/OSS/doris-ducklake-api30-9fb3e3a2/output/doris-api30-m2`. To install
elsewhere, verify the archive hash first and install only these eight modules; do not copy
a whole Maven cache over.

## Re-vendoring

Changing the pin means rebuilding SDK, FE and BE together: FE and BE Thrift must match,
and the SDK jars must equal the FE's. Use a fresh checkout at the target SHA.

Build inputs used for the current pin: builder image
`apache/doris@sha256:cbe85f9fbca617c1884e79d7ec0939c4e0e4179443ab93b955537a2f26ddbed8`
(JDK 17, Maven 3.9.9, Clang 20.1.7, protoc 3.21.11, Thrift 0.24), static Brotli 1.0.10,
Arrow/Parquet 24.0.0 with modular encryption and matching MongoDB C libraries.
Build phases were serial: two native compile workers, one LLD thread/link job,
13 GiB native hard cap and no extra swap; no OOM occurred. The exact scripts
(`api30-run.py`, `api30-export.py`, `api30-image.sh`) and memory telemetry are kept
beside the SDK on the qualification host, not in this repo.
Overlay Dockerfiles: [FE](../compose/fe-overlay/Dockerfile), [BE](../compose/be-overlay/Dockerfile);
the BE overlay must copy the complete JNI tree.

Steps:

1. Review SPI, filesystem, JNI, Thrift/protobuf, native reader and dependency changes
   between the old and new commits.
2. Keep the old SDK directory, bundles, images and volumes until the new pin is accepted.
3. Rebuild SDK, FE and BE; confirm the SDK jars equal the FE's and the Java 17 class floor holds.
4. Run the headless suite, the Python controller tests, a full smoke, A/B deployment with
   rollback, and the live probes against the new images.
5. Update the `connectorApiVersion` in `build.gradle.kts` and this page; promote only the
   bytes that were tested.

`enable_file_scanner_v2` is a no-op on this pin; V2 is the only file-scan path. New
capability flags, write defaults and timestamp semantics introduced upstream are not
adopted automatically; each gets its own entry in [TODO.md](TODO.md).

Previous pins (API13, API24, API26, API27) and their evidence are in the archive. Their SDK
directories, images and volumes on the qualification host are retained for rollback.
The [Duckbridge build-reuse handoff](HANDOFF-FOR-DUCKBRIDGE-DORIS-9fb3e3a2-API30.md)
records pristine artifacts and the separate patched-JNI qualification boundary.
