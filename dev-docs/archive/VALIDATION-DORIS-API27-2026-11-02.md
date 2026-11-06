# Doris API27 re-vendor — 2026-11-02

## Status — scoped qualification complete; SDK/default bundle promoted

The user authorized the exact reviewed Apache Doris snapshot
**4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146**. SDK prerequisites and the isolated
eight-module export, full FE and full ASAN BE/JNI/CDC are built. Both immutable
runtime images, final consumer/package/Python checks and isolated runtime/read
acceptance pass. The exact tested eight-module SDK and ordinary default ZIP/receipt
are promoted locally only after these gates pass. No retained metadata migration,
existing-stack reload or production/performance qualification is intended.

**Handoff instruction:** give the completed Duckbridge build-reuse document to the
requester. **Do not send or dispatch a message or project note to Duckbridge.** No
Duckbridge adoption, edits, builds, tests, dependency changes or deployment are
delegated. This user instruction supersedes automatic FYI delivery for this task.

## Frozen inputs and preservation

- Source:37 commits beyond qualified `8e10b235cf030d486b6840cfd64b6218ab515e6d`;
  see [the source-only review](archive/REVIEW-DORIS-UPSTREAM-2026-11-02.md).
- Connector API **27.0**, filesystem API **7.0**, JNI **6.0**, BE execution **19**,
  Thrift **0.24.0**, Maven revision `1.2-SNAPSHOT`.
- Independent pristine source worktree:
  `/home/jayson/DEV/OSS/doris-ducklake-api27-4bc7f426` (`W` below), with disk-backed
  independent metadata at `/home/jayson/DEV/OSS/doris-ducklake-api27-metadata.git`.
  The shared main Doris checkout/staged Duckbridge patch are not switched or built over.
- The exact-source worktree hook ran using an owned bootstrap with the frozen
  `build.sh`; no borrowed staged source replaced it. Submodules are left to the
  required scoped native-build entry point, not initialized indiscriminately.
- Existing project default nested-read fixes, reports and Quack report were
  fingerprinted. The current default ZIP/receipt and installed eight-module API26
  SDK closure are backed up at `$W/output/rollback-api26/`, together with the
  working diff and untracked documents. Existing API26/API24/API13 images/volumes
  and Duckbridge's project-local repositories remain outside the adoption target.
- Default catalog/fixtures/replay/SLT remain0.12.0, JDBC1.5.6.0; the separately pinned
  published candidate remains0.13.0-20261101.082015-33/JDBC2.0.0.0. No candidate/default
  promotion, parser backport or extra1.1 feature opt-in follows from re-vendoring Doris.

## Verified immutable build environment

The selected current amd64 image is
`apache/doris@sha256:922fc3d4d44e7fdd47a611b68b0097a888e0da5a101151917b1052c4c8abb17d`;
remote multi-platform index was
`sha256:aa72ec8a829e1747fdfd9abefd04c2ec6cd44b9a54079b98627487701408a4e9`.
It was pulled by digest, preserving the older build image/tag and outputs.

JDK17.0.2, Maven3.9.9, Clang20.1.7, Thrift0.24.0 and protoc3.21.11 are verified.
The image has no Brotli pkg-config file, so that first path-based probe is retained
as failed setup evidence. A subsequent executable linked the actual installed
static decoder/encoder/common archives and requires both `Brotli*Version()` values
to be `0x100000a` (**1.0.10**). It passes. Arrow is24.0.0, librdkafka2.16.0 and
gperftools/tcmalloc2.18.1. Actual installed archive SHA256s:

| Archive | SHA256 |
|---|---|
| Brotli decoder | `0fc8866b6748cb07286c50a6d755c09972713febe8fdf93f70d0bc1657995bd7` |
| Brotli encoder | `7888f2a1f34ba9408a338c3a0d8786e12892647502ce269c307f51666aa77697` |
| Brotli common | `7247cbf6b95f5dcaab3acfea640201c55d360254dc9c732901f4e515a35cc03e` |
| Arrow | `63a01a540dc8d5e4a1b1adcc15413b6f31fb678a8e6da7ff89be14ece293d590` |
| Lance-C | `a9340f7925bf8a4ced9c71a9dacd43a838eeaab6d49b0d14309413f19c913784` |
| librdkafka | `f135c0fe5ec5bf9e5f7f59d53d8b2d9a48b6ac821c964d615ec0d253dc0988de` |
| tcmalloc | `67de23bf320d2f366f435dad13037191b159c7dbe020a828357b1120c20f64cc` |

`api27-build-image.json`, `api27-toolchain-provenance.txt`,
`api27-thirdparty-verified.txt` and `api27-brotli-version.c` retain the inputs/probe.
No system-package substitution, old Brotli reuse or dependency gate bypass was used.

## Build procedure and current outcome

`$W/output/api27-build.sh` verifies the pin and unchanged source `build.sh`, selects
the immutable image, and runs SPI/FE/BE phases sequentially. Maven uses isolated
`output/m2` with build cache disabled; only external dependency-cache bytes are
seeded. Old Doris FE SDK modules are excluded and rebuilt, not reused.

SPI prerequisites pass and exactly eight rebuilt reactor modules are exported:
`fe`, `fe-foundation`, `fe-filesystem`, `fe-filesystem-api`, `fe-extension-spi`,
`fe-thrift`, `fe-connector`, `fe-connector-spi`. Export verifies the actual API27
resource/capability and filesystem7 parent declaration. Archive
`doris-api27-m2.tar.gz` SHA256:
`c95e6561a1ee3b586a52092c5b9be0590ae4e2fe4ca8cecf0bcaca6553c8d3ed`.
The global SDK remained API26 through qualification; its controlled promotion to
the exact exported API27 bytes is recorded in the final closeout below.

The initial full FE attempt fails resolving the unchanged historical external
dependency `org.apache.doris:je:18.3.14-doris-SNAPSHOT`, absent from the live Maven
repositories. Its exact previously cached published build
`18.3.14-doris-20221116.020838-2` is restored into only this owned Maven cache:
timestamp/base-alias JAR bytes match, SHA1 sidecars verify, and all copied SHA256s/
original Maven metadata are retained in `api27-je-cache-provenance.json`. This is
third-party dependency-cache reuse, not an old FE SDK module or a new local-JAR
catalog dependency. No source, POM pin, repository or Maven global setting changed.
The failed log remains `api27-fe-build-attempt1-je-missing.log`; the full unmodified
FE retry passes. The five actual exported compile JARs are byte-identical to the
FE host copies (`api27-sdk-host-identity.json`). Source-qualified FE image
`doris-fe:ducklake-4bc7f426-api27` is built as
`sha256:54d04bc777b9eb3ae7ebfc7841969a3dc7db0ec81286dc4e640e98ab717b8053`.

Native builds keep ASAN and upstream build/layout/glibc gates. Resource settings
are four compile workers, a14GiB container/no added swap, a single Ninja link pool
and one linker thread, specified in the owned generated CMake cache. The unmodified
`build.sh --be` remains the entry point. This avoids repeating the prior concurrent
final-link OOM rather than disabling source, sanitizers or checks.

The first native configure stops in CMake's trivial compiler test because the
generated resource cache named `mold`, which is not installed in the selected
image. It compiles no Doris object before failing. The actual available LLD is
verified by a successful one-thread C++ link; only the owned generated cache/
resource runner changes to `-fuse-ld=lld`. The failed log is retained separately.
The retry configures ASAN normally; generated `rules.ninja` confirms link-pool
depth1 and `build.ninja` confirms one-thread LLD flags. Source `build.sh`, native
sources, sanitizers, hygiene and glibc gates are unchanged. Settings and the
linker probe are retained in `api27-native-resource-settings.json` /
`api27-linker-probe.txt`.

The complete native retry exits0, passes the upstream glibc2.17 gate and packages
JNI/CDC successfully. Independent shipped-ELF audit finds actual maximum undefined
GLIBC requirement2.10. All nine JNI plugins pass all six upstream static layout
checks; the optional warning/exit2 path is not substituted for that result.
`api27-engine-manifest.json` records3,231 artifact hashes and the ELF audit.

- Native BE SHA256: `21084b84cec99d4a79a7c65c95fade2770ae464e2b41fca1983531f257a03778`.
- FE main JAR: `31cce4a13b6668072ab29ac7c83973a2cc007fff7a383fadafff6594feec1359`.
- JNI SPI/bootstrap: `6768ac6d119fdeb9d9d83637ce13fb92f19535bfee1e6ffb59fcdfe08226edfd` /
  `8b2ec4fe99a059c1c02cb75705fa8feb59fc4a8b722a39407f43e42d31d9219b`.
- BE image `doris-be:ducklake-4bc7f426-api27-asan`:
  `sha256:6666e38d600f56ba689e21747db038d4cd2626286e69d094467089abd27ba7e8`.

These build/static results are not runtime qualification of all JNI plugins.

## Consumer / runtime completion gates

Candidate packaging/preflight now target API27 and source-qualified separate image/
volume names. New headless ABI assertions pin query-option field1002/default true
and additive report fields18/19; `SUPPORTS_WRITE_ONLY_DEFAULTS` stays explicitly
off. Existing synchronous planning/cancellation boundaries, timestamp-TZ opt-in,
complete current-native nested dictionaries, unsupported history/mapping/inline
guards and F01 raw-key deletion refusal remain intact.

Initial fresh default Java25 tests: **269 passed/one existing skip**; Java17:
**238 passed/same skip**, with Detekt/runtime/package checks passing. Actual
`pluginIdentity` also refuses the installed global API26 SDK for target27 before
publishing a false identity (`api27-old-sdk-refusal.json`). Global SDK/distribution
remained unchanged during these initial tests; the eight-module API27 export was
selected by scoped repository override. Headless results alone were not treated
as loaded-provider/runtime qualification.

Fresh published-candidate API27 Java25 tests also pass **286/one existing skip**;
Java17 **255/same skip**, including strict catalog/fixture/replay/SLT hashes and
runtime/package checks. It remains a separate dependency lane, not the default.

Default/candidate and actual runtime/read checks pass as recorded below. The final
section records the completed Python rerun, controlled SDK/default promotion,
normal-default byte verification and handoff closeout. Neither previous API26
receipts nor upstream PR test claims are relabelled as API27.

Both new runtime images and actual runtime qualification pass. The final handoff includes frozen
source/API/wire versions, build-env and SDK/FE/native/JNI/image hashes, commands,
qualification limits, patched Duckbridge reuse caveats and rollback. It will be
provided **to the user only**, with **no Duckbridge message/dispatch**.

## Main API27 runtime qualification

**Passed:** `$W/output/runtime-acceptance/f24-e-dpl0n0m4/acceptance.json`, independently
verified in `api27-runtime-results-verified.json`. The exact API27 A bundle passes
two full28-check supported smokes, actual A/B provider identity, preserved
cluster/catalog/read identity, complete verified A rollback, persisted FE restart,
and both same-count tuple-corruption and stale-provider refusal controls. Negative
run results remain failed and cannot leave a current passing receipt; stale provider
refuses before lake allocation. Owned resources are cleaned and protected state is
unchanged. No existing retained FE/BE is reloaded.

Three native probe stages (before replacement, after persisted restart, and after
restoring A) pass450 exact DEFAULT projection/equality/combined-predicate queries;
TopN ASC/DESC/NULL-order results over300,000 source rows, three row groups and a
real position-delete file; timestamp/BLOB values; complete ordinary STRUCT/LIST/MAP
values and NULL-parent/container identity; reversed/partial-column and bucket writes
with independent native read-back. Ordinary naive timestamps retain expected values
in UTC and Asia/Shanghai. These are not new INT96/TZ-marker or equality-delete claims.
Native S3 leaves the JNI registry uninitialized and JVM metrics absent.

The default final rerun initially found its generated test directories missing while
Gradle reported compilation up-to-date. The failed class-load XML/log is retained;
a fully cold serial rebuild (`--rerun-tasks`, incremental compilation disabled) then
passes269/238 Java25/17 tests plus the same one existing skip per JVM, with the exact
same A ZIP/receipt bytes as the successful live run. No source, assertion or skip
was changed to waive that build-cache failure.

The separate default read harness also passes API27/catalog0.12 native populated
STRUCT/LIST/MAP/literal-NULL/NULL-parent cases on actual Java25/17 at
`$W/output/read-acceptance/f24-e-_tl_589u/acceptance.json`, with its own28-check smoke.
The separate published-candidate quoted-name/replay run also passes on both Java25/17 at
`$W/output/read-acceptance/f24-e-q1w6wgw6/acceptance.json`, with its own28-check smoke.
The positive native mirror count is2/2 per JVM; an intentionally wrong Boolean hash
fails before mirror allocation, followed by a valid1/1 floating mirror. Raw metadata,
native Parquet IDs, external names and populated nested values agree without changing
goldens or fabricating values. `api27-read-results.json` independently binds both lanes.

The first final130-case Python run executes all cases with frozen sources but hits
four30-second mock-driver timeouts under concurrent workloads. No timeout, guard or
assertion is relaxed. It is being rerun with two disjoint workers on the already
owned disk-backed temporary roots; the failing six-worker result is not final green.
The subsequent passing rerun and gated promotion are recorded below; the failed
attempt is retained, not relabelled as passing.

## Final promotion and user handoff

**Passed:** `$W/output/api27-closeout-results.json`. The complete two-worker frozen
Python rerun executes **130/130 tests, no skips**, with unchanged production
Python/shell/YAML hashes, assertions and timeouts. The earlier six-worker timeout
run remains explicitly failed. Fresh build-logic checks pass8/8.

Fresh default checks (scoped repository, then plain normal `mavenLocal()` after
promotion) pass **269 Java25 /238 Java17**, with exactly the same one pre-existing
parked inline-delete skip per JVM. The separate published-candidate lane passes
**286 Java25 /255 Java17**, with that same skip. Detekt, ABI/runtime floors,
dependency and packaging checks pass; no new expected failure or semantic golden
change was added. Live read tasks additionally pass1/1 per JVM for default nested
reads and2/2 per JVM for candidate nested/replay reads, with no skips.

`api27-promote-sdk.py` first requires independently verified exact-A28/28 proofs,
both negatives, persisted restart, verified rollback,450 DEFAULT queries, nested
and separate actual-read proofs, frozen sources/Python and unchanged installed
preflight SDK hashes. Only the eight rebuilt module/version directories are
replaced. Their exact old directories and separate backup copies remain; unrelated
Doris modules, global Maven settings and Duckbridge's local repositories are untouched.
The ledger is `api27-sdk-promotion.json`.

Plain default cold tests/assembly then run with **no Doris repository override**;
the rebuilt default ZIP and adjacent receipt equal the actual live-tested A bytes:

- Build ID: `doris-ducklake-v1-89d005fa0ed91bb435a3722b489843d6fbf6b4fcf574223b8d487877561b9673`.
- ZIP SHA256: `093adf4500c2479c8053bc6f8546f74a56686188def516d6d6df92e6f8c81c60`.
- Catalog0.12.0/JDBC1.5.6.0 remain the default; the -33 snapshot remains separately
  opted in and qualified, not silently promoted.

The final audit verifies actual FE/native files inside the immutable images,
complete installed SDK hashes, source/receipt bindings, all owned live projects
cleaned, old API26 image IDs retained, shared staged/unstaged work unchanged, and
the default nested encoder/test/seed and Quack report hashes unchanged from
pre-re-vendor fingerprints. No unrelated user change was reset or discarded.
`api27-complete-JNI-SHA256SUMS` fingerprints821 shared/plugin JARs; its SHA256 is
`9f6a4c0a5c2a3011579742227964d16e0d6ce45c8e094b3dd9879f0be7ea1f0c`.

The completed [handoff for Duckbridge](HANDOFF-FOR-DUCKBRIDGE-DORIS-4bc7f426-API27.md)
is provided **to the requester only**. No Duckbridge session message, dispatch,
project note, adoption assignment or acknowledgement is sent/claimed. Source
applicability of its canonical Java-only handler patch is checked read-only;
pristine native-S3 results do not qualify that patched JDBC/Quack runtime.

The completed changes were committed/pushed as `21fdcb0` (default nested-read fix)
and `19d235f` (API27 re-vendor and requester-only handoff). This is a scoped re-vendor,
not closure of broader R07, history/mapping/default evolution, inline/CDC, all-JNI,
production or performance work.
