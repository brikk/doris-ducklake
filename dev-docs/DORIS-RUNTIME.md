# Doris runtime and SDK pin

The connector SPI is not in any Doris release yet; the connector builds and runs against
a specific `apache/doris` master commit.

## Current pin

| | |
|---|---|
| Source | `4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146` (master) |
| Connector API | 27.0 (`Doris-Connector-Plugin-Api-Version` in the plugin manifest must match the major) |
| Filesystem API / JNI API / BE exec max | 7.0 / 6.0 / 19 |
| Thrift | 0.24.0 |
| Maven revision | `1.2-SNAPSHOT` |
| FE image | `doris-fe:ducklake-4bc7f426-api27` — `sha256:54d04bc777b9eb3ae7ebfc7841969a3dc7db0ec81286dc4e640e98ab717b8053` |
| BE image | `doris-be:ducklake-4bc7f426-api27-asan` — `sha256:6666e38d600f56ba689e21747db038d4cd2626286e69d094467089abd27ba7e8` |
| SDK archive | `sha256:c95e6561a1ee3b586a52092c5b9be0590ae4e2fe4ca8cecf0bcaca6553c8d3ed` |

The images are local linux/amd64 builds, not registry publications. The BE is an ASAN
build: correct for the tested scope, not representative of release performance. The FE
builds patch-free. The qualification run that established this pin is
[VALIDATION-DORIS-API27-2026-11-02](archive/VALIDATION-DORIS-API27-2026-11-02.md).

## SDK

Eight modules under `org.apache.doris` at `1.2-SNAPSHOT`: `fe`, `fe-foundation`,
`fe-filesystem`, `fe-filesystem-api`, `fe-extension-spi`, `fe-thrift`, `fe-connector`,
`fe-connector-spi`. The compile jars are byte-identical to the FE's own copies.

The build resolves `org.apache.doris` from `mavenLocal()` only (an exclusive repository
filter), or from `-PdorisRepository=<dir>` to try a candidate without disturbing `~/.m2`.
Never set a global `maven.repo.local`: it would also redirect the catalog dependencies.

On the qualification host the SDK is at
`/home/jayson/DEV/OSS/doris-ducklake-api27-4bc7f426/output/doris-api27-m2`. To install
elsewhere, verify the archive hash first and install only these eight modules; do not copy
a whole Maven cache over.

## Re-vendoring

Changing the pin means rebuilding SDK, FE and BE together: FE and BE Thrift must match,
and the SDK jars must equal the FE's. Use a fresh checkout at the target SHA.

Build inputs used for the current pin: builder image
`apache/doris@sha256:922fc3d4d44e7fdd47a611b68b0097a888e0da5a101151917b1052c4c8abb17d`
(JDK 17, Maven 3.9.9, Clang 20.1.7, protoc 3.21.11, Thrift 0.24), static Brotli 1.0.10,
Arrow 24.0.0. The BE link needed one LLD thread, four compile workers and 14 GiB to avoid
OOM. The exact build/export/image scripts (`api27-build.sh`, `api27-export.py`,
`api27-image.sh`) are kept beside the SDK on the qualification host, not in this repo.
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

Previous pins (API13, API24, API26) and their evidence are in the archive. Their SDK
directories, images and volumes on the qualification host are retained for rollback.
