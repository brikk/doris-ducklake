# Doris API30 re-vendor — 2026-11-10

## Status: scoped qualification passed; matching SDK/default bundle promoted locally

The owner requested that Doris pick up the blocking dictionary-encoded BOOLEAN
reader fix [#69967](https://github.com/apache/doris/pull/69967), and selected the
reviewed current master snapshot **`9fb3e3a2a13f972a62cb999d5599fc8e8f17f306`**.
This is 115 commits beyond the qualified API27 source. Connector API is **30.0**;
filesystem API **7.0**, JNI **6.0**, BE execution maximum **19** and Thrift
**0.24.0** remain unchanged. Runtime reports use the actual built short hash
`9fb3e3a2`; the full source SHA is recorded independently.

The catalog 0.18.0 adoption is already complete and is not repeated here.
Catalog/fixtures/replay/SLT remain final **0.18.0**, native JDBC **2.0.0.1** and
brikk-sql **0.18.1**. DuckLake 1.0 admission, goldens, skip lists, mirror minimums,
write/default behavior and feature flags remain unchanged.

[#67725](https://github.com/apache/doris/pull/67725) is a separate deferred TODO:
the new partition-view capability is **not** enabled by this re-vendor. API29's
typed absence exception and API30's optional cooperating split sources do not
automatically change the connector's existing behavior.

## Isolation, rollback and resource controls

- Independent pristine source:
  `/home/jayson/DEV/OSS/doris-ducklake-api30-9fb3e3a2` (`W` below), with metadata
  `/home/jayson/DEV/OSS/doris-ducklake-api30-metadata.git`.
- Shared `/home/jayson/DEV/OSS/doris` and the older Duckbridge source contain
  staged handler work; HEAD/index/working-diff fingerprints are preserved and
  neither checkout is built over or reset. Duckbridge is not dispatched or edited.
- The first shared-cache clone lacked promised blobs; it and its failed fetch
  remain as setup evidence. A fresh independent depth-one fetch materialized the
  entire exact source tree. No missing source is substituted with another pin.
- The exact-source worktree hook ran against an owned bootstrap holding the
  frozen `build.sh`, not the staged shared checkout. Native entry points alone
  initialize their required submodules.
- Previous bundle/identity receipt is retained under
  `build/validation/doris-api30-9fb3e3a2/previous-default/`; old SDK, images,
  metadata/storage volumes and retained stacks remain outside the adoption target.
- Heavy build phases are serial. SPI/FE: **8 GiB** container cap, 3 GiB Maven heap;
  native: **13 GiB**, two compile workers, 3 CPU, one serialized LLD link with one
  linker thread. Memory-swap equals memory, so no extra swap is admitted. Telemetry
  samples host/container memory and stops only the owned build if host available
  memory falls below 512 MiB. Logs are never overwritten on retries.
- A lightweight serial fake-Docker controller pass has a separate **512 MiB**
  hard/no-swap cap. No other build or live cluster overlaps native compilation.
- Owned runtime caps: FE 3 GiB, BE 7 GiB, PG/MinIO 512 MiB each;
  live JVM scope 2.5 GiB, no swap, leaving orchestration margin within 14 GiB.

## Build inputs and SDK evidence

Immutable amd64 builder:
`apache/doris@sha256:cbe85f9fbca617c1884e79d7ec0939c4e0e4179443ab93b955537a2f26ddbed8`.
JDK 17.0.2, Maven 3.9.9, Clang/LLD 20.1.7, protoc 3.21.11 and Thrift 0.24.0.
Genuine Arrow/Parquet encryption and MongoDB C dependencies are present. Brotli
1.0.10 archive hashes match the previously verified input; exact native library
hashes are captured in `$W/output/api30-preflight.log`. A preflight path-only
tcmalloc probe was corrected to its real gperftools path; no dependency changed.

SDK prerequisites, full unmodified `build.sh --fe -j2` and full unmodified ASAN
`build.sh --be -j2` have passed. Maven uses an owned repository
and disabled build cache. External dependency caches were copied without **any**
`org.apache.doris` module, so old SDK binaries cannot mask a new-source build.

Eight rebuilt SDK modules are exported (`fe`, `fe-foundation`, `fe-filesystem`,
`fe-filesystem-api`, `fe-extension-spi`, `fe-thrift`, `fe-connector`,
`fe-connector-spi`). Initial installed SPI/Thrift JARs differed from the full FE
only in embedded flattened Maven POM text. That export is preserved; the final
export selects the actual full FE-host JAR bytes while retaining their resolvable
standalone SDK POMs. All executable classes and API resources are identical. All five compile
JARs now byte-match FE, and **7,115 classes** pass the Java17 floor.

Final SDK archive SHA-256:
`7317d2665f140d28336ee15e9b264e77c24d23e20d37458c535f26a8273e5c80`.

All nine deployed JNI plugins pass all six upstream static layout checks. The
complete packaged engine manifest hashes **3,628 artifacts**, including **821 JNI
JARs**. The finished BE's maximum undefined GLIBC requirement is **2.10**, within
the preserved 2.17 baseline. This static evidence does not qualify every plugin's
runtime or reflection/ServiceLoader dependencies.

```text
native_BE_sha256 fa3c2d8a568cdabfa440c47ff94ef67500dfcaab53fed2b82eff696f2104e8ab
FE_main_jar_sha256 e9d9a652ae5e26418fa53ed5fb49c8e1dfd74a1760e5f72eef918260abd2850f
complete_JNI_manifest_sha256 c4f7f187a832405baddf4b2daf54c7ec1eccf452af084f2ea41fb38f9be9fba8
```

Measured container peaks: SPI **2.27 GiB**, FE **7.97 GiB**, BE **13 GiB**; zero
OOMs or safety-stop events. At a capped native sample most usage was reclaimable file
cache, not compiler anonymous memory. No cap was raised or extra swap admitted.

Consumer tests first used the explicit candidate repository and private build
directory. Only after all gates passed were the eight rebuilt module/version
directories promoted into the normal local SDK; complete displaced API27
directories and a separate 29-file backup remain for rollback.

## Immutable candidate images

- FE `doris-fe:ducklake-9fb3e3a2-api30`:
  `sha256:abbc36e3dc4f3c528b6c6fbd2c162311075d26a0d21f58cb29bf35184686b9cd`.
- BE `doris-be:ducklake-9fb3e3a2-api30-asan`:
  `sha256:1f73d01a59e86a768e80bf035b6d49987ed3819103a7923e60ced398bf6d9bda`.

Both labels identify the frozen full source/API30/JNI6. Actual files inside the
images—not labels alone—were independently hashed: native BE, FE main JAR and all
five compile SDK JARs match the built/audited inputs. The old immutable FE/BE
overlay bases are retained; only complete matching built output is overlaid.

## BOOLEAN blocker regression

The exact upstream 257-byte Parquet fixture is retained as base64 under
`compose/fixtures/`, decoded SHA-256:
`ba2016dcf7ba8af572371491f0b6701afb0b79c06a85930a80598628a020e5b2`.
It really uses `PLAIN, RLE, RLE_DICTIONARY` for nullable BOOLEAN; rewriting it with
a writer that emits PLAIN booleans would invalidate the blocker regression.

The owned native oracle registers those exact uploaded bytes through DuckLake
`add_data_files`. The new live test checks ordered values/NULL alignment, six
predicate shapes and aggregates through both native S3 TVF and the DuckLake
connector, with lazy materialization false/true: **28 query comparisons per JVM**.
All **56 new-runtime comparisons passed**, 28 on each JVM. The isolated
old API27 matched-runtime control **passed its expected failure assertions**:
both lazy-materialization settings fail with the original
`Boolean type cannot have a dictionary page` diagnostic. It used the exact
retained FE/BE image IDs and fixture SHA above, then cleaned only its UUID-owned
resources and verified unrelated container state unchanged. Evidence:
`$W/output/boolean-before-control/result.json`.

## Controller verification

The single frozen-source controller invocation passed: **155 tests**, **153 passed**,
zero failures/errors and two unchanged opt-in real-Docker skips. Its resource scope
was limited to 512 MiB, no swap and one CPU. Existing guard assertions and timeouts
were unchanged. After completion, source-version literals in the fake fixture and
attestation defaults were aligned with the compiled eight-character short hash;
this version-only correction does not require repeating the full controller suite.

## New-SDK consumer verification

The single new-runtime headless invocation executed **336 Java25 tests**
(**335 passed**, zero failures/errors, one existing inline-delete disabled test)
and **293 Java17 tests** (**292 passed**, same disabled test). All eight
build-logic tests passed. The existing disabled test and its reason were not
changed, and no new skip or golden adjustment was added.

That invocation's overall exit was **1**, solely because the newly added excluded
live BOOLEAN test exceeded the still-enforced `NestedBlockDepth` rule. It did
not fail any test, compilation, package or runtime-floor gate. The live test was
split into source-validation and nullable-row-reading responsibilities; **only
test compilation and Detekt were rerun**, successfully. Passing headless results
are reused: only a live-test class excluded from both headless suites changed.
The original failed log/receipt is preserved, not relabeled as an all-green run.

The final Detekt report contains **212 visible line-length warnings** and no
error findings. Other rule enforcement and the advisory-only policy stay intact.
Assembly, dependency recording and independent bundle verification passed;
**24,820 runtime classfiles** meet the Java17 floor. All four normally resolved
catalog-family JARs still match the final 0.18.0 release receipt. All five compile
SDK JARs in the plugin identity match the new FE-host JARs. No test tooling or
host-provided SDK JARs are accidentally bundled.

Qualified A bundle identity (also the final normal output after promotion):

```text
build_id doris-ducklake-v1-a83be86d835c1da321b736382df287bd7d2d11040cad27a9fdf6d865e800d0dd
plugin_zip_sha256 892755082fa5fd4d4ed0f29f794d99e1d956dc21101811768639f3f2eb0c22de
provider_jar_sha256 dd081a505927d45025c64525a7a775a42fca65b5971cded2d374760d6edc9271
compile_sdk_id 4a7cee96b2ec0f055f902112481571e409f728161e6eefdb2a451f30fa4ee2eb
```

Evidence: `consumer-checks.log`, `consumer-result.json`, `lint-recheck.log` and
`consumer-verified.json` under the project evidence root. Full suites were not
repeated for lint-only, version/hash-only or documentation edits.

## Actual runtime qualification and affected-only retry

The exact immutable API30 FE/BE images and A bundle passed:

- Full supported smoke: **28/28** checks, including writes/DDL/maintenance and
  current baseline refusals; actual loaded provider and FE/BE source verified.
- **3/3 live tests on Java25 and 3/3 on Java17**, zero failures/errors/skips:
  quoted/populated nested values and NULL parents, measured replay, and exact
  BOOLEAN dictionary reads through DuckLake/native S3 with lazy off/on.
- Replay: **2/2 positive mirrors per JVM**; intentional wrong Boolean golden hash
  fails before mirror allocation, while the later floating query still mirrors
  **1/1**. No golden/corpus SQL change or skip relaxation.
- Real A/B provider replacement preserves cluster/catalog/BE identity; complete
  verified A backup and actual A rollback preserve exact BOOLEAN values.
- Persisted FE restart retains B identity and exact reads. Four lifecycle-stage
  BOOLEAN readbacks agree: A, B, B after restart and restored A.
- Same-count wrong-tuple corruption is refused at the intended `w2_rows` check;
  passing correctness receipts are not left behind.

The original run's **overall status remains failed**: its final stale-provider
negative control encountered Doris's `[MEM_ALLOC_FAILED]` guard before reaching
the injected identity gate. The long-lived ASAN BE reported 6.41 GiB against its
6.30 GiB internal limit under the 7 GiB cgroup cap; no kernel OOM occurred. Earlier
positive results are not discarded or relabeled. No memory cap, timeout, expected
error or assertion was relaxed.

**Only that stale-provider control** was rerun on fresh UUID-owned infrastructure
with the same artifacts and caps. It passes the intended negative assertions:
the actual B inventory is observed, only its returned build ID is substituted
with stale A, the driver refuses before any lake allocation, and no passing
attestation survives. No full smoke, headless suite or live JVM suite was repeated.

`$W/output/api30-runtime-results-verified.json` independently binds the successful
original gates and focused retry while preserving the original failed receipt.
Both runs clean only owned resources and verify protected state unchanged. This
is scoped ASAN correctness evidence, not production/performance, exhaustive-corpus
or all-JNI runtime qualification.

## Promotion, preservation and handoff

`api30-promote-sdk.py` gates the exact consumer/runtime/hash proofs and original
installed SDK hashes before replacing only eight module/version directories. It
does not redirect global Maven configuration or touch Duckbridge's local SDK.
Normal `assemble verifyJava17Runtime qualificationDependencies` then passes with
**no repository override**, and its ZIP plus identity receipt match live-tested A
**byte-for-byte**. No full suite is repeated for promotion or documentation edits.

Shared Doris and Duckbridge HEAD/index/working diffs are reverified unchanged;
old SDK/image/bundle/volume rollback assets remain. All owned runtime fixtures
are cleaned. The completed [Duckbridge handoff](../HANDOFF-FOR-DUCKBRIDGE-DORIS-9fb3e3a2-API30.md)
is for the requester only; no receiver dispatch or adoption is performed.

## Evidence

Operational scripts and detailed logs/receipts: `$W/output/api30-*`.
Project preservation/controller evidence:
`build/validation/doris-api30-9fb3e3a2/`.

Core receipts: `api30-engine-manifest.json`, `api30-sdk-host-identity.json`,
`api30-baked-image-core-identity.json`, `api30-runtime-results-verified.json`,
`api30-sdk-promotion.json`, plus project `consumer-verified.json`, controller,
lint-only-recheck and normal-assembly logs. Detailed runtime receipts remain under
`$W/output/runtime-acceptance/` and `$W/output/stale-provider-control/`.

No push, release, tag, retained-stack migration or Duckbridge adoption was performed.
