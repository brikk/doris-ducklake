# Current Doris runtime and SDK

## Exact inputs

- Source: `4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146` (Apache Doris master).
- Connector API 27.0; filesystem API 7.0; JNI API 6.0; BE execution maximum 19;
  Thrift 0.24.0; Maven revision `1.2-SNAPSHOT`.
- FE image: `doris-fe:ducklake-4bc7f426-api27`, ID
  `sha256:54d04bc777b9eb3ae7ebfc7841969a3dc7db0ec81286dc4e640e98ab717b8053`.
- BE image: `doris-be:ducklake-4bc7f426-api27-asan`, ID
  `sha256:6666e38d600f56ba689e21747db038d4cd2626286e69d094467089abd27ba7e8`.

Images are local **linux/amd64** artifacts, not published registry distributions.
The ASAN BE is correctness-qualified within the recorded scope, not release
performance/all-JNI/production qualified. Current release branches and checked
4.2 RC lack the connector SPI; don't substitute a stock runtime. The FE builds
patch-free. [Latest upstream review](REVIEW-DORIS-UPSTREAM-2026-11-03.md) is not an
authorized runtime bump.

## SDK installation/selection

The SDK is an exact rebuilt eight-module closure under `org/apache/doris`:
`fe`, `fe-foundation`, `fe-filesystem`, `fe-filesystem-api`, `fe-extension-spi`,
`fe-thrift`, `fe-connector`, `fe-connector-spi`, each at `1.2-SNAPSHOT`.
The five compile JARs are byte-identical to their FE host copies. Coordinates
alone are mutable and do not identify these bytes.

On the qualification host:

```sh
W=/home/jayson/DEV/OSS/doris-ducklake-api27-4bc7f426
# Consumer build without changing installed SDKs:
./gradlew check assemble -PdorisRepository="$W/output/doris-api27-m2"
```

The normal build uses scoped `mavenLocal()` for `org.apache.doris` only. To install
on another machine, first preserve those existing module/version directories, verify
the exported manifest/archive and host identity, then deliberately install only
this closure. Do not copy an entire old Maven cache or overwrite Duckbridge's
project-local `doris-m2`. Never set global `maven.repo.local` to select catalog JARs.

SDK archive SHA256:
`c95e6561a1ee3b586a52092c5b9be0590ae4e2fe4ca8cecf0bcaca6553c8d3ed`.
Full module, FE/native/JNI and image hashes are in
[API27 validation](VALIDATION-DORIS-API27-2026-11-02.md) and its local evidence manifests.

## Build/re-vendor contract

Use a **new isolated source checkout** at the exact SHA. Do not switch/reset the
shared Doris checkout or reuse its staged Duckbridge patch. The qualified build
uses immutable amd64 image
`apache/doris@sha256:922fc3d4d44e7fdd47a611b68b0097a888e0da5a101151917b1052c4c8abb17d`,
JDK17/Maven3.9.9/Clang20.1.7/protoc3.21.11/Thrift0.24. Verify installed static
Brotli **1.0.10**, Arrow **24.0.0** and the remaining third-party inputs, not host
package versions.

The frozen `output/api27-build.sh`, `api27-export.py` and `api27-image.sh` at the
evidence location record the exact build/export/image commands. They are local
qualification artifacts, not scripts checked into this repository. Build the
SDK reactor, full FE and full ASAN BE/JNI/CDC; retain native glibc and all layout
gates. Serialized one-thread LLD links and four compile workers/14GiB avoided
final-link OOM without source/sanitizer/gate changes. Do not replace this with
an arbitrary toolchain or relabel old output.

The unchanged historical JE dependency required exact cached published bytes
(`18.3.14-doris-20221116.020838-2`); recorded aliases/sidecars/hashes distinguish
third-party cache reuse from old SDK-module reuse. See the evidence before reproducing.
Overlay Dockerfiles are [FE](../compose/fe-overlay/Dockerfile) and
[BE](../compose/be-overlay/Dockerfile); copy the **complete** JNI shared/plugin tree.

For an authorized change:

1. Freeze source/API/wire/build inputs; inspect SPI, filesystem, JNI, Thrift/protobuf,
   native readers, execution versions and dependency deltas on master and release branches.
2. Preserve SDK directories, bundles/receipts, images, retained volumes and shared work.
3. Rebuild matching SDK/FE/full BE/JNI/CDC; verify host equality, ELF floors and layouts.
4. Qualify default/candidate separately: fresh JVM/build/Python gates, loaded exact-A
   full smokes, A/B/rollback, persisted restart, value-level probes and both refusals.
5. Promote only the exact tested SDK/bundle bytes; verify a normal default build
   reproduces them and clean only acknowledged owned acceptance resources.

V2 is the only file-scan path; `enable_file_scanner_v2` is a no-op, not a fallback.
New write-default/timestamp/cancellation/capability surfaces are not automatic opt-ins.

## Preservation and handoff

Previous SDK directories, default bundle backups and API26/API24/API13 evidence,
images and volumes are retained. Archiving their docs does not authorize removing
or upgrading that state. No in-place old-metadata migration is qualified.

The [API27 Duckbridge handoff](HANDOFF-FOR-DUCKBRIDGE-DORIS-4bc7f426-API27.md) is
for the requester only: no message, session dispatch or Duckbridge project note.
Pristine native S3 results do not qualify its patched JDBC/Quack runtime.
