# User handoff for Duckbridge — Doris 4bc7f426 / API27

## Status — completed; requester-only handoff

Matching pristine SDK/full FE/full ASAN BE/complete JNI/CDC are built. Static
build/layout/glibc, scoped runtime and final consumer/Python checks pass. The exact
tested SDK and normal default bundle are promoted locally. This handoff is not
permission to start Duckbridge adoption or deployment.

**Delivery is to the requester only. Nothing is sent or dispatched to Duckbridge.**
No recipient acknowledgement or Duckbridge adoption is claimed. This document is
build-reuse information, not JDBC/Quack or production/performance qualification.

## Frozen source and environment

- Apache Doris **4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146**.
- Connector API **27.0**, filesystem API **7.0**, JNI API **6.0**, BE execution
  maximum **19**, Thrift **0.24.0**, Maven revision `1.2-SNAPSHOT`.
- `W=/home/jayson/DEV/OSS/doris-ducklake-api27-4bc7f426` is an independent pristine
  detached source worktree. Shared staged Duckbridge source and project-local SDK/
  patched artifacts are not overwritten. Upstream build setup only normalizes two
  datasketches test fixtures; no Doris production patch is applied.
- Immutable amd64 build image:
  `apache/doris@sha256:922fc3d4d44e7fdd47a611b68b0097a888e0da5a101151917b1052c4c8abb17d`.
  JDK17.0.2, Maven3.9.9, Clang20.1.7, Thrift0.24.0, protoc3.21.11.
- Required Brotli **1.0.10** is verified by an executable linked against the actual
  static decoder/encoder/common archives, not by the host system package. Arrow
  **24.0.0**, librdkafka2.16.0 and tcmalloc2.18.1 are checked. Full archive hashes
  are in `api27-thirdparty-verified.txt` and the validation report below.
- Native source entry remains unmodified `build.sh --be -j4`, ASAN,14GiB RAM/no
  extra swap, one Ninja link at a time and one LLD thread. The initial generated
  cache mistakenly named absent mold; CMake rejected its trivial compiler link
  before Doris compilation. The corrected LLD probe/configuration passes. No
  source, sanitizer, compatibility or layout gate was disabled.
- FE initially could not resolve unchanged historical Berkeley JE from its current
  repositories. Only the exact old published dependency cache
  `org.apache.doris:je:18.3.14-doris-20221116.020838-2` was restored into this isolated
  Maven repository, with alias equality/sidecar checks/hashes recorded. This is not
  reuse of old SDK modules, a POM pin change or a global Maven setting change.

## Built artifacts

| Artifact | Location / SHA256 or image ID |
|---|---|
| Eight-module rebuilt SDK | `$W/output/doris-api27-m2/` |
| SDK archive | `$W/output/doris-api27-m2.tar.gz` |
| Archive SHA256 | `c95e6561a1ee3b586a52092c5b9be0590ae4e2fe4ca8cecf0bcaca6553c8d3ed` |
| Connector SPI JAR | `9b8896271296be1c70551e83deefe1d4553db12a6b85dd27576e08659f3a0368` |
| Regenerated Thrift JAR | `991be99cf279dead8c1b20be5901f8cf9e6bc30a20674736fb8574dbd67a084d` |
| FE output | `$W/output/fe/` |
| FE main JAR | `31cce4a13b6668072ab29ac7c83973a2cc007fff7a383fadafff6594feec1359` |
| FE image | `doris-fe:ducklake-4bc7f426-api27` |
| FE image ID | `sha256:54d04bc777b9eb3ae7ebfc7841969a3dc7db0ec81286dc4e640e98ab717b8053` |
| Complete pristine BE output | `$W/output/be/` |
| Native ASAN BE | `21084b84cec99d4a79a7c65c95fade2770ae464e2b41fca1983531f257a03778` |
| BE image | `doris-be:ducklake-4bc7f426-api27-asan` |
| BE image ID | `sha256:6666e38d600f56ba689e21747db038d4cd2626286e69d094467089abd27ba7e8` |
| JNI SPI | `6768ac6d119fdeb9d9d83637ce13fb92f19535bfee1e6ffb59fcdfe08226edfd` |
| JNI bootstrap | `8b2ec4fe99a059c1c02cb75705fa8feb59fc4a8b722a39407f43e42d31d9219b` |

Exactly eight rebuilt modules are exported: `fe`, `fe-foundation`, `fe-filesystem`,
`fe-filesystem-api`, `fe-extension-spi`, `fe-thrift`, `fe-connector`,
`fe-connector-spi`. All five actual compile JARs match their FE host copies
byte-for-byte. Per-file export hashes: `api27-m2-manifest.json`; host comparison:
`api27-sdk-host-identity.json`. Never identify a mutable1.2-SNAPSHOT SDK by its
coordinate alone or copy every old cached org.apache.doris module as a closure.

The native ELF passes glibc2.17 enforcement, with actual maximum undefined GLIBC
requirement **2.10**. All **nine JNI plugins pass all six static layout checks**;
CDC is built. The3,231 FE/native/JNI/plugin hashes and shipped ELF audit are in
`api27-engine-manifest.json`. Layout correctness does not establish complete
reflective JDBC/Quack or every embedded native dependency at runtime.
The complete821-file shared/plugin JNI hash manifest is
`$W/output/api27-complete-JNI-SHA256SUMS`, SHA256
`9f6a4c0a5c2a3011579742227964d16e0d6ce45c8e094b3dd9879f0be7ea1f0c`.

## Duckbridge reuse boundary

This is the **pristine DuckLake native build**, not your patched JDBC runtime.
Read-only `git apply --check` of your canonical
`doris-patches/be/0001-duckdb-type-handler.patch` succeeds on this exact pin;
patch SHA256 is `8fe662b83d41cce06070913945a054e3c81a531fa81cf2353316a9473b1cd9e9`.
It was **not applied** and its Java DuckDB handler is absent from this build.

If the user later authorizes adoption, preserve your current BASELINE, project-local
`doris-m2` and complete patched distributions first. Deliberately stage the exact
API27 closure into your own repository and rebuild the connector for API27; an API26
ZIP cannot be upgraded just by changing its manifest. Do not enable new capabilities
automatically. The new `SUPPORTS_WRITE_ONLY_DEFAULTS` models Iceberg write-defaults,
not a generic instruction for every catalog.

Native C++ reuse can avoid another long compile **only after** proving identical
source/native/build inputs and that your local delta remains Java-only. Rebuild and
package the complete matching patched shared Java/JNI/plugin layer, run its layout
gates, then independently qualify JDBC/Quack/types/timezones/concurrency/lifecycle.
Do not splice an old scanner JAR into the pristine output. Unchanged JNI source/API
and shared SPI/bootstrap bytes are not whole-plugin/transitive-closure proof.

File scans now always use V2; `enable_file_scanner_v2` is a deprecated no-op, not a
legacy-reader rollback switch. Timestamp/DEFAULT semantics and native-reader changes
are summarized in [the reviewed delta](REVIEW-DORIS-UPSTREAM-2026-11-02.md).

## Commands, evidence and rollback

Build/export/image commands and logs are under `$W/output/`:
`api27-build.sh`, `api27-export.py`, `api27-image.sh`, image JSONs, static manifests,
`api27-native-resource-settings.json`, `api27-linker-probe.txt`,
`api27-je-cache-provenance.json` and the exact-case qualification helpers.
Overlay bases are pinned by digest in `api27-overlay-bases.json`.

Old DuckLake global SDK/default-distribution files, preflight fingerprints, working
diff and untracked reports are retained under `$W/output/rollback-api26/`; earlier
API26/API24/API13 images/volumes are not migrated/deleted. Your local repositories,
patched artifacts and the shared staged work are preserved, not rollback targets
we modify. See [API27 validation/status](VALIDATION-DORIS-API27-2026-11-02.md).

## Scoped DuckLake runtime results

API27 exact default A passes two full28-check supported smokes, real A/B provider
replacement, metadata/read identity preservation, complete A rollback and persisted
FE restart. Three native stages pass450 exact DEFAULT queries, TopN with real
position deletes/three row groups, timestamp/BLOB values, ordinary nested values/
NULL parents/containers, non-UTC naive-timestamp checks and reordered/partial/bucket
writes with independent native read-back. Both same-count tuple corruption and
stale loaded identity are refused without current passing negative receipts.
Native S3 leaves JNI uninitialized. These are not JDBC/Quack results.

Separate fresh Java25/17 native read harnesses pass both default catalog0.12 ordinary
nested reads and published-candidate quoted-name/Boolean/float replay checks, each
with its own28-check full smoke. Candidate mirror coverage is measured (2/2 positive
mirrors per JVM); a deliberately wrong Boolean hash fails before mirror allocation,
then a later valid floating query still mirrors successfully. No full corpus or
unsupported nested history/legacy mapping/default evolution/inline/CDC claim follows.

Evidence: `$W/output/api27-runtime-results-verified.json`, `api27-read-results.json`
and their source/build/runtime-bound ledgers/XMLs. Exact tested bytes are not
substituted by an equivalent version label. Global SDK/default promotion and
final user-delivery closeout pass. The complete frozen Python suite is130/130,
no skips; fresh build-logic8/8. Default Java25/17 suites pass269/238 plus one
unchanged parked skip each; candidate286/255 plus the same skip. Plain normal
`mavenLocal()` cold checks pass after promotion. Default ZIP/receipt are exactly
the live-tested API27 A, build ID
`doris-ducklake-v1-89d005fa0ed91bb435a3722b489843d6fbf6b4fcf574223b8d487877561b9673`,
ZIP SHA256 `093adf4500c2479c8053bc6f8546f74a56686188def516d6d6df92e6f8c81c60`.
Full closeout: `$W/output/api27-closeout-results.json`; promotion/old directory
ledger: `api27-sdk-promotion.json`. Default catalog0.12/JDBC1.5.6.0 and separate
candidate pins are unchanged; retained stacks are not reloaded/migrated. All changes
remain uncommitted/unpushed at handoff creation.
No message, session prompt, adoption request or project note is sent to Duckbridge.
