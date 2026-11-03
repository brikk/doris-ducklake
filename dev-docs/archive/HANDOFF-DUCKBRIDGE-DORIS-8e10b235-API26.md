# Duckbridge build reuse — Doris 8e10b235 / connector API26 / JNI6

## Status

**Pristine matching SDK, FE and full ASAN BE/JNI/CDC are built; scoped DuckLake
runtime qualification and controlled promotion pass.** Informational delivery is
recorded below. It is build-reuse information, not Duckbridge runtime qualification
or an instruction to start adoption, builds, tests or deployment.

## Frozen source and build inputs

- Apache Doris `8e10b235cf030d486b6840cfd64b6218ab515e6d` (reviewed October31
  snapshot); 59 commits beyond DuckLake's `62e3d81a42b` and58 beyond the currently
  recorded Duckbridge baseline `082736138b7f0e767f966cbb1434bc9ba85f29ec`.
- Connector API **26.0**, filesystem API **7.0**, JNI API **6.0**, BE execution
  maximum **19**, Thrift **0.24.0**, Maven `1.2-SNAPSHOT`.
- Let `W=/home/jayson/DEV/OSS/doris-ducklake-api26-8e10b235`.
  This is an independent, pristine detached source worktree; the shared main
  checkout and its staged Duckbridge handler files were not changed or built over.
- Build environment:
  `apache/doris@sha256:4d63f788e85d35ab4f29804e3a4cdd93fe0c70c4ce954e6fbf333ac21a45ab9d`.
  JDK17.0.2, Maven3.9.9, Clang20.1.7, Thrift0.24.0, protoc3.21.11.
  Lance-C archive `d09929c6aa5d9fb5683016351397109b565886aba4433935eb85b3a5c626cdc4`,
  librdkafka2.16.0 archive `f135c0fe5ec5bf9e5f7f59d53d8b2d9a48b6ac821c964d615ec0d253dc0988de`,
  tcmalloc2.18.1 archive `67de23bf320d2f366f435dad13037191b159c7dbe020a828357b1120c20f64cc`.
- ASAN correctness build, **not a release-performance artifact**. No Doris
  production patch. `build.sh --fe/--be` remains the native entry point.
  Heavy phases are sequential; disk-backed objects/Maven/context/log storage.
- Initial six-job native compilation reached final links, then two concurrent
  links hit cgroup OOM. All objects were retained. Successful retry uses
  `build.sh --be -j1`, generated-cache `-Wl,--threads=1`,8GiB RAM/no extra swap,
  Maven heap3GiB. Sanitizers/glibc/layout gates remain enabled; no unrelated job
  was stopped. Both attempts and resource settings are retained under `$W/output/`.

## Reusable build artifacts

| Artifact | Location / identity |
|---|---|
| Rebuilt eight-module SDK closure | `$W/output/doris-api26-m2/` |
| Closure archive | `$W/output/doris-api26-m2.tar.gz` |
| Archive SHA256 | `b7b576a5f4cd61fd31b80b6bcbb3d2a672570b26491eb2179c0f9efed86cb79b` |
| Connector SPI JAR SHA256 | `adac215555b537759b95c73e57f31981ac27d24aca891e3190bbf2d792144de1` |
| Thrift JAR SHA256 | `671dbe075a2949d2847dfe3c4ea20cc6ec956cb8e0e31083719720ad32abc37b` |
| FE output / image | `$W/output/fe/`; `doris-fe:ducklake-8e10b235-api26` |
| FE image ID | `sha256:509f6ee46af21a51407788bef5df9afe878197d38ec3439e3edcdeaf98fad317` |
| FE JAR SHA256 | `056bcba381ca562f76fde32140383da823ead67134f8dccc7ff82f949e2dfd59` |
| Native BE SHA256 | `881cb91a719f132e8ed0cb8fc7d8efd3e2a161f3bb2cf274b2b2eb8164ec4e28` |
| Complete pristine BE output / image | `$W/output/be/`; `doris-be:ducklake-8e10b235-api26-asan` |
| BE image ID | `sha256:2155eba1d7a51b1a00e1c02972876b7fbcb36fb3058b28fa1f16f7b6516c80fb` |
| JNI SPI SHA256 | `6768ac6d119fdeb9d9d83637ce13fb92f19535bfee1e6ffb59fcdfe08226edfd` |
| JNI bootstrap SHA256 | `8b2ec4fe99a059c1c02cb75705fa8feb59fc4a8b722a39407f43e42d31d9219b` |

Only modules actually rebuilt by the SPI reactor are exported: `fe`,
`fe-foundation`, `fe-filesystem`, `fe-filesystem-api`, `fe-extension-spi`,
`fe-thrift`, `fe-connector`, `fe-connector-spi`. Unrelated old cached Doris modules
are excluded. Per-file export hashes: `api26-m2-manifest.json`.
All five actual compile SDK JARs match their FE host copies byte-for-byte;
`api26-sdk-host-identity.json` records that comparison.

`api26-engine-manifest.json` records3,231 FE/native/shared/plugin JAR/ELF hashes.
JNI packages are under `output/be/plugins/jni/`; shared SPI/bootstrap are under
`output/be/lib/jni/spi/`. All nine plugins pass all six upstream static layout
checks; CDC is included. The native ELF passes glibc2.17 enforcement, with actual
maximum undefined requirement2.10. Static layout does not prove JDBC/Quack,
reflective drivers or all native libraries embedded inside JARs work at runtime.

## Duckbridge-specific adoption boundary

Your source of truth remains **your** `doris-patches/BASELINE`; your compile repo
remains **your** `doris-duckbridge/doris-m2/`, not DuckLake's mavenLocal. Neither
was edited by this work. Keep your current patched runtime/rollback outputs.

Read-only `git apply --check` succeeds for your current canonical
`doris-patches/be/0001-duckdb-type-handler.patch` against this exact pristine pin.
It was **not applied** here. The Java handler is absent from this pristine build;
patch applicability is not JDBC semantic or runtime qualification.

If the user later authorizes adoption:

1. Use a separate exact-pin checkout; preserve staged/shared work and back up
   your project-local SDK and patched distributions.
2. Deliberately stage the exported closure into your repo, rebuild your connector
   for API26 and keep existing capability opt-outs. Do not just alter a manifest.
3. Apply/check your Java-only handler patch in that checkout. Reuse this pristine
   FE/native executable **only after verifying identical source/native build inputs
   and that your own delta remains Java-only**; this can avoid repeating C++ work.
4. Rebuild the complete matching shared Java/JNI/plugin layer from your patched
   source using the upstream build entry point and its Java-extension targets.
   Keep packaging/layout gates. Do not splice an old scanner JAR into the tree.
5. Package separately named complete patched output/image and perform your own
   JDBC/Quack/type/timezone/concurrency/plugin-load/failure-isolation acceptance.

The entire JNI source subtree and the rebuilt shared SPI/bootstrap bytes are
unchanged from API24. That does **not** establish equality of every packaged
plugin/transitive dependency or remove the need for your patched complete tree.
The pristine DuckLake BE is not a Duckbridge-qualified BE.

## Compatibility and qualification limits

API24/25 ZIPs do not load into API26 FE. Filesystem family is now7; exact manifest
matching is not a replacement for matched source/wire/runtime checks. Native
reader, split-lifecycle and I/O changes are described in the
[upstream review](REVIEW-DORIS-UPSTREAM-2026-10-31.md).

DuckLake retains catalog0.12.0, JDBC1.5.6.0, synchronous planning and capability
opt-outs. Fresh JVM checks pass (Java25:266pass/one existing skip; Java17:235pass/
same skip), as do eight build-logic checks and130 frozen-source Python checks.
Exact candidate A passes two full28-check smokes; A/B load/metadata/read/rollback,
persisted FE restart,450 exact DEFAULT queries, TopN across three row groups with
real deletes and typed/native write read-back pass. Same-count tuple corruption
and stale loaded identity are rejected without current passing negative receipts.
Native S3 leaves JNI uninitialized. SDK/default ZIP byte-identity and plain
mavenLocal checks pass; see [evidence and limits](VALIDATION-DORIS-API26-2026-10-31.md).
No native-S3 result is JDBC coverage, and no extra type/inline/CDC/lease/fleet
support or production/performance qualification is inferred.

Reproduction: `$W/output/api26-build.sh`, `api26-image.sh`,
`api26-be-resume-settings.json`, build/image logs and source-qualified manifests.
Pinned overlay bases are in `api26-overlay-bases.json`. Rollback inventory and
old DuckLake SDK/default-distribution copies are under `output/rollback-api24/`.
No Duckbridge rollback artifact/repository was overwritten.

## Delivery

Final qualification passes. Informational delivery was dispatched to the existing
Duckbridge `doris-upstream` session `ses_f4491b970ffeOAw5swIcPceRUx`, with
`promptDispatched=true` and explicit instructions not to start adoption, edits,
builds, tests or deployment. Duckbridge project note
`984dd3f8-7102-4ad5-ba9e-964402850607` retains this handoff and artifact/reuse
boundaries. Delivery is complete; acknowledgement and adoption are not claimed.
