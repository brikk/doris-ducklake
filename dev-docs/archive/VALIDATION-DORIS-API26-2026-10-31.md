# Doris API-26 re-vendor qualification — 2026-10-31

## Status — scoped runtime qualified, promoted and handed off

The reviewed API-26 snapshot is built and scoped runtime qualification passes.
The tested eight-module SDK is promoted to `mavenLocal()`; default assembly and
its adjacent receipt are **byte-identical to the exact A bundle tested live**.
Both source-qualified images, fresh Java25/Java17, build-logic/Python gates,
full supported smokes, typed probes, persisted restart and negative controls pass.
Duckbridge build-reuse handoff is delivered as recorded below. Repository changes
remain uncommitted/unpushed by this task.

No retained stack is migrated or restarted. Catalog 0.12.0, DuckDB/JDBC
1.5.6/1.5.6.0, other dependency pins, synchronous planning and capability opt-outs
stay unchanged. F08/F11/F12/F26, broader R07, production inline transport and
production/performance qualification remain separate.

## Frozen inputs and isolation

- Apache Doris `8e10b235cf030d486b6840cfd64b6218ab515e6d`, the exact snapshot in
  [the upstream review](REVIEW-DORIS-UPSTREAM-2026-10-31.md), 59 commits beyond
  qualified `62e3d81a42b2b74ee2a5a577969df62edee06b78`.
- Connector API **26.0**, filesystem API **7.0**, JNI API **6.0**, BE execution
  version **19**, Thrift **0.24.0**, Maven `1.2-SNAPSHOT`.
- Pristine detached build worktree:
  `/home/jayson/DEV/OSS/doris-ducklake-api26-8e10b235`.
  Its independent Git metadata is disk-backed at
  `/home/jayson/DEV/OSS/doris-ducklake-api26-metadata.git`; shared source objects
  are read-only during builds. The main Doris checkout and staged Duckbridge
  changes are not switched, reset or compiled over.
- Pinned build image:
  `apache/doris@sha256:4d63f788e85d35ab4f29804e3a4cdd93fe0c70c4ce954e6fbf333ac21a45ab9d`.
  JDK17.0.2, Maven3.9.9, Clang20.1.7, Thrift0.24.0 and protoc3.21.11 verified.
  Third-party required-version files are unchanged; the new phmap patch is an
  AArch64 control-scan optimization, not an x86_64 dependency-version update.
- Actual selected image-prefix dependencies, not the unused host placeholder,
  were checked: Lance-C archive SHA256
  `d09929c6aa5d9fb5683016351397109b565886aba4433935eb85b3a5c626cdc4`,
  librdkafka2.16.0 archive
  `f135c0fe5ec5bf9e5f7f59d53d8b2d9a48b6ac821c964d615ec0d253dc0988de`,
  gperftools2.18.1/tcmalloc archive
  `67de23bf320d2f366f435dad13037191b159c7dbe020a828357b1120c20f64cc`.
- All logs, Maven staging, native objects and image contexts are disk-backed,
  not the nearly full `/tmp` tmpfs. Heavy JVM/native build phases run sequentially
  with six workers. ASAN is retained; no release-performance build is requested.

The worktree setup hook was run with an owned bootstrap containing the exact
pinned `build.sh` and the selected environment; no borrowed source/build output
was installed. Native build entry points remain unmodified. Submodules are pinned.
The upstream FE build normalizes datasketches test fixtures, as the API-24 build
did; inspect/report that submodule-only change rather than call it a production
patch or overwrite shared work.

## SDK/FE checks completed

SDK prerequisites were rebuilt with JDK17, flatten enabled, Maven build cache
disabled and `output/m2` as the isolated repository. Non-Doris dependency cache
was copied for efficiency; old `org/apache/doris` artifacts were explicitly
excluded. Exactly the eight rebuilt reactor modules are exported, not unrelated
old cached modules:

`fe`, `fe-foundation`, `fe-filesystem`, `fe-filesystem-api`, `fe-extension-spi`,
`fe-thrift`, `fe-connector`, `fe-connector-spi`.

Archive `output/doris-api26-m2.tar.gz` SHA256:
`b7b576a5f4cd61fd31b80b6bcbb3d2a672570b26491eb2179c0f9efed86cb79b`.
The filesystem compile API JAR is not its host version anchor: exported parent
POM declares7.0; the full FE's `fe-filesystem-spi` resource independently declares
7.0. Connector SPI's resource declares26.0 and includes `ConnectorScanContext`.
An initial export check looked in the compile API rather than the SPI anchor;
the unqualified partial export is retained separately, not silently certified.

Pristine `build.sh --fe -j6` passes with JDK17, Checkstyle enabled, build cache
disabled and only UI compilation disabled. The five actual compile SDK JARs are
**byte-identical** to the full FE's host JARs:

| Artifact | SHA256 |
|---|---|
| Connector SPI | `adac215555b537759b95c73e57f31981ac27d24aca891e3190bbf2d792144de1` |
| Thrift | `671dbe075a2949d2847dfe3c4ea20cc6ec956cb8e0e31083719720ad32abc37b` |
| Filesystem API | `50b831673f5ed608ea84b07fa09f51029e42c4e00d85fdd67c0f1018d587086e` |
| Extension SPI | `39f5e357db500283343a71de7515ca2fd2c4353af2557c738736ae1da3c60a07` |
| Foundation | `79f3d71fd8b4bf8433c5489759dfe02c102447a15d58248839652814ae0039ae` |

Evidence: `output/api26-{spi,fe}-build.log`, `api26-export.log`,
`api26-toolchain-provenance-verified.txt`, `api26-m2-manifest.json` and
`api26-sdk-host-identity.json`. An early toolchain pathname check used
`liblance.a` instead of the actual `liblance_c.a`; the subsequent explicit archive
checks above passed without changing the image or bypassing a dependency.

## Candidate connector and runtime gates

Candidate packaging/preflight defaults are API26; old API13/24/25 bundles are
rejected before Docker mutation. Current snapshot/source-qualified image and
volume names are distinct from retained API24/API13 names. F23 loopback policy,
F24 stopped-install/fresh-load attestation and F25's required28 correctness checks
remain mandatory; the new correctness scope is `doris-smoke-supported-api26-v1`.
New request-context ABI coverage is a headless SDK contract check, not a claim
that DuckLake enables streaming or consumes asynchronous cancellation today.

Final pre-live candidate checks (not runtime certification): Java25 **266
passed/one existing skip**, Java17 **235 passed/one existing skip**, zero failures;
Detekt, Java17 scan of **24,630 classes** and candidate assembly pass. All **130
Python checks**, no skips, pass with Python/shell/YAML hashes frozen. The initial
39-test subset is not counted as the final matrix. A new build-logic guard refuses to stamp
a target API using a mismatched/missing/duplicate compile-SDK API declaration;
all **eight build-logic tests**, including its regression, pass. Its actual
`pluginIdentity` task also rejects the still-installed API24 SDK for target26
before writing a payload identity (`api26-old-sdk-rejection.json`). This closes
the ordinary failure path where mutable `1.2-SNAPSHOT` mavenLocal still serves
API24 while the candidate manifest target is26.

Candidate A identity is
`doris-ducklake-v1-af57a1321fa06b9d0028a24d1569ac2a78c4ad83aabe7794c4ab199142354f66`,
ZIP SHA256 `db08a07eb6498f9da014e284b985e64ed8b6cd2ded6b318465129f0855a287af`.
Resource-only B identity is
`doris-ducklake-v1-676e5af497a4b6d4892d5a2d3ec0c66535ff5de02c7083d33ef8bbc878f9541e`.
Existing payload/classes, SDK and dependencies match exactly; only
`META-INF/f24-acceptance-build.txt` is added. These are candidate identities, not
loaded-code claims. FE image `doris-fe:ducklake-8e10b235-api26` is built as
`sha256:509f6ee46af21a51407788bef5df9afe878197d38ec3439e3edcdeaf98fad317`.
The complete BE build and image pass after the resource retry below. BE image
`doris-be:ducklake-8e10b235-api26-asan` is
`sha256:2155eba1d7a51b1a00e1c02972876b7fbcb36fb3058b28fa1f16f7b6516c80fb`.
The native executable SHA256 is
`881cb91a719f132e8ed0cb8fc7d8efd3e2a161f3bb2cf274b2b2eb8164ec4e28`;
FE JAR SHA256 is
`056bcba381ca562f76fde32140383da823ead67134f8dccc7ff82f949e2dfd59`.
JNI SPI/bootstrap hashes are respectively
`6768ac6d119fdeb9d9d83637ce13fb92f19535bfee1e6ffb59fcdfe08226edfd` /
`8b2ec4fe99a059c1c02cb75705fa8feb59fc4a8b722a39407f43e42d31d9219b`.
Those two rebuilt shared JARs are byte-identical to API24; this alone does not
qualify reuse of an entire patched JNI/plugin closure.

The upstream deployment checker reports **nine plugins passing all six static
layout checks**; it ran successfully, not with its optional warning/exit2 path.
CDC compiles/packages successfully with the same pin-local Maven environment.
The final native ELF has maximum undefined GLIBC requirement **2.10** and passes
the source's2.17 gate. These static/build checks do not establish every JNI plugin
works at runtime. `output/api26-engine-manifest.json` retains3,231 artifact hashes
and the shipped ELF audit; actual native S3/JNI-lazy status remains a live gate.

### Native build resource retry

The first full ASAN native build completed compilation and reached both final
executable links (`doris_be` and `kuromoji_build_dict`). Both linker processes
were killed; Docker recorded cgroup OOM events for this owned build container.
This is retained as **failed build evidence**, not a completed BE package:
`output/api26-be-build-attempt1-oom.log` and `api26-be-oom-events.json`.

Other work was using host RAM/swap. No unrelated job/container was stopped. The
retry reuses the compiled objects through the unchanged `build.sh --be -j1`,
sets `-Wl,--threads=1` in this owned generated CMake cache, and bounds the build
container to8GiB RAM/no extra swap with Maven heap3GiB. This serializes the two
links without changing source, ASAN, the glibc2.17 gate or JNI/CDC packaging.
Settings are retained in `output/api26-be-resume-settings.json`; the retry exits0,
passes the glibc gate, packages complete JNI/CDC and passes all deployment checks.
No C++ source patch, sanitizer downgrade or package omission was needed.

Candidate build output is `build/api26-qualification/`, initially selected with the
pin-local `-PdorisRepository` override. Candidate JVM suites, including F01
real storage checks, execute freshly; the eight build-logic checks also execute.
Frozen XMLs and result bindings are in `output/api26-final-xml/`,
`api26-final-check-results.json` and `api26-python-final-results.json`.
Real F24/F25 acceptance is independently verified below, not inferred from compilation.

Build/qualification commands and logs live under the worktree's `output/`.
No failed assertion will be waived with a generic expected failure or new skip.

## Live acceptance and persisted restart

**Passed:**
`output/runtime-acceptance/f24-e-yzqc_t9a/acceptance.json`.
The copied public driver, actual API26 FE/full ASAN BE, private PostgreSQL/MinIO
and released DuckDB1.5.6 own new UUID resources only. Both full supported smokes
use **exact A**, not a merely same-API or equivalent-class substitute:

- `3ca164d1a3e04c9db64e8ca6e3265534`:28/28 required checks.
- `49de259f638c49bfa023577bfdcf7c4b`:28/28 required checks after explicit persisted
  FE restart and restoring exact A from the resource B exercise.

Real A->B stopped replacement preserves cluster/catalog IDs, container/mounts,
BE process and exact read-back. B changes only one acceptance resource, existing
classes/API/SDK/dependencies match, and its rollback archive verifies as exact A.
Explicit B restart preserves cluster ID **882301238**, FE/catalog identity and
BE process while changing FE startup time; actual loaded B registry identity is
rechecked. Restoring A is a stopped replacement, not a hot JAR swap.

Before replacement, after persisted restart and after A restoration, four exact
ASC/DESC × NULLS FIRST/LAST TopN results agree with native DuckDB over300,000
source rows, a real position-delete file and **three row groups**. Counts/MIN/MAX,
pre-epoch/fractional TIMESTAMP and embedded-NUL/trailing-zero/empty BLOB results
match too. Scanner V2 and the successful TopN filter target are observed. Each
stage passes50 DEFAULT rounds/150 exact projection/equality/combined-predicate
queries: **450 exact DEFAULT queries** total. Reordered, partial-column and
reversed bucket-column writes agree with independent native read-back.

All three probe stages report `javaSupport=true`, `registryInitialized=false`,
no loaded JNI plugins and no JVM metrics. This native-S3 result is not JDBC/HDFS
coverage or proof every JNI closure works reflectively at runtime.

The same-count negative first reads actual correct three-row data, changes only
one returned string, and fails `w2_rows` with no current passing deployment/
correctness receipt. The stale-provider negative verifies actual B then returns
A's build ID, refuses before lake allocation and leaves no current passing
receipt. Failed negative run-results remain explicitly failed even though the
compound acceptance of those refusal gates passes.

Copied controller/probe/tooling hashes match the executed sources; required
ledgers and receipts are independently verified, with no credentials in persisted
proof. Protected retained state/default files match before/after this isolated
acceptance; acknowledged disposable infrastructure is cleaned. Evidence includes
`api26-runtime-results-verified.json`, the frozen oracle, probe/EXPLAIN logs,
positive/negative ledgers and restart/rollback identities. No performance, all
query/type, unsupported history/mapping or production claim is inferred.

## Controlled promotion and default checks

Promotion requires complete live evidence, exact-A28/28 proofs, both negative
controls, frozen public-driver hashes and unchanged preflight SDK fingerprints.
Only the eight rebuilt module/version directories are replaced; the exact old
directories and separate rollback copies remain. Unrelated Doris modules,
global Maven settings and Duckbridge's project-local repository are untouched.
`output/api26-sdk-promotion.json` records each old/new directory and installed
file manifest. An unfamiliar intervening SDK change would refuse promotion.

Plain default `mavenLocal()` checks (no repository override) then execute the
Java25/Java17 suites freshly:266/235 pass respectively, with the identical one
existing skip per JVM and zero failures. Detekt, ABI, package and dependency
checks pass. Default ZIP/receipt matches live-tested A exactly, including the
five compile SDK hashes and build ID. `api26-default-verification.json` records
that proof; default XMLs are in `api26-default-xml/`.

Default image/volume pointers name this source explicitly; retained API24/API13
images/volumes are preserved, not migrated or adopted by name. The main Doris
staged patch and unrelated untracked Quack report are unchanged. No source
workaround, new dependency, feature opt-in, extra skip or retained deployment
was used to obtain a passing result.

## Rollback and handoff gate

The original installed eight-module Maven closure, default ZIP/receipt and
preflight fingerprints are backed up under `output/rollback-api24/`; existing
runtime images/volumes are retained. Duckbridge's project-local repository and
patched artifacts are not adoption targets for this task.

After matching FE/full native BE/complete JNI packages and scoped runtime gates
pass, deliver a source/build/hash/command/rollback handoff to Duckbridge so it can
reuse matching inputs. Pristine DuckLake JNI does not include its staged Java
DuckDB type handler and must not be described as Duckbridge-qualified. Delivery
and any acknowledgement will be recorded distinctly. No handoff completion is
claimed merely because a handoff file exists. Delivery status is recorded below
and in the API26 handoff (handoff note, removed).

Informational FYI was dispatched to Duckbridge's existing `doris-upstream`
session `ses_f4491b970ffeOAw5swIcPceRUx` (`promptDispatched=true`), explicitly
without requesting adoption, edits, builds, tests or deployment. The Duckbridge
project also retains note `984dd3f8-7102-4ad5-ba9e-964402850607` with the artifact
paths/hashes and reuse limits. Delivery is complete; recipient acknowledgement
and Duckbridge adoption/qualification are not claimed.

`output/api26-closeout-results.json` independently confirms the final source/
receipt/SDK/default-package bindings, exact native/FE image file hashes, preserved
old API24 image IDs, staged shared patch/Quack report, rollback directories,
cleaned runtime resources/claim and informational handoff dispatch. No source or
runtime limitation above is converted into a blanket production claim.
