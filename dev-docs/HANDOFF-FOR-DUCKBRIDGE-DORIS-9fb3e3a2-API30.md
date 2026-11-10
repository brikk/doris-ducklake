# Duckbridge build-reuse handoff — Doris API30

**Ready for a separately authorized Duckbridge adoption.** The pristine matching
SDK/FE/ASAN-BE is built and DuckLake's scoped qualification is complete. This
handoff does not qualify Duckbridge's patched JDBC/Quack runtime.

This handoff is for the requester to give Duckbridge. No Duckbridge message,
session dispatch, source edit, dependency update, test or deployment is performed
by the Doris-DuckLake re-vendor.

## Frozen target

- Exact upstream: `9fb3e3a2a13f972a62cb999d5599fc8e8f17f306`.
- Compiled SQL version: `doris-0.0.0-9fb3e3a2` (the independent exact-pin repository
  abbreviates to eight characters; do not mistake this for a different full SHA).
- Connector API **30.0**, filesystem API **7.0**, JNI API **6.0**, BE execution max
  **19**, Thrift **0.24.0**, Maven modules **1.2-SNAPSHOT**, runtime Java **17**.
- Includes the native dictionary-encoded Parquet BOOLEAN fix **#69967** and JNI
  standard UTF-8 conversion fix **#69889**, among the 115 commits after API27.
  Presence of the latter is not yet Duckbridge JDBC/Quack Unicode qualification.
- API28 adds connector partition-pruning opt-in; API29 typed table-absence errors;
  API30 optional cooperating sources/readiness. No automatic capability enablement.
- `#70057` corrected Thrift schema-table/routine-load wire IDs. Use a matching
  FE/BE and rebuild against the new SDK; never mix an API27 bundle with API30 FE.

## Reusable source/build inputs

Pristine donor: `/home/jayson/DEV/OSS/doris-ducklake-api30-9fb3e3a2` (`W`).
Independent metadata: `/home/jayson/DEV/OSS/doris-ducklake-api30-metadata.git`.
Builder:
`apache/doris@sha256:cbe85f9fbca617c1884e79d7ec0939c4e0e4179443ab93b955537a2f26ddbed8`.
The builder includes genuine PME-enabled Arrow/Parquet and MongoDB native inputs;
old pre-encryption thirdparty archives are not interchangeable. Full native input
hashes/tool versions are in `$W/output/api30-preflight.log`.

The SDK export is `$W/output/doris-api30-m2/`; archive
`$W/output/doris-api30-m2.tar.gz`, SHA-256:
`7317d2665f140d28336ee15e9b264e77c24d23e20d37458c535f26a8273e5c80`.
Eight-module and FE-host byte identities are recorded in
`api30-m2-manifest.json`, `api30-sdk-export-provenance.json` and
`api30-sdk-host-identity.json`. All five compile JARs match the FE host; 7,115
runtime classfiles are within Java17. Select actual FE-host JAR bytes rather than
the preserved initial flattened-POM export.

Exact compile SDK JAR SHA-256s:

```text
820a3fc7dc24839175d00d6a0b66fbae3f9d9d5e86006e72f2d621967768df9b  fe-foundation-1.2-SNAPSHOT.jar
071f3f612b50b50f9e083fea62f67ee1b63a4a445594822a3b69c7b6346d6326  fe-filesystem-api-1.2-SNAPSHOT.jar
7d08bd90a82d64bc477d5b1bb2f70c20eb3b48bd45564796362abebb662cf274  fe-extension-spi-1.2-SNAPSHOT.jar
dab697f354ab1292378c9d2e0500ba48c0af92cc65655fa18b30f3da41c16c8f  fe-connector-spi-1.2-SNAPSHOT.jar
2833530a9cbb745548f82ee3c35d5af60757386336e392580a8d2da238a0d97b  fe-thrift-1.2-SNAPSHOT.jar
```

The SDK, full FE and full ASAN BE/JNI builds have passed. Complete pristine output
is fingerprinted in `$W/output/api30-engine-manifest.json` (**3,628 artifacts**).
All nine JNI plugins pass all six static layout checks; **821 JNI JARs** are
fingerprinted by `api30-complete-JNI-SHA256SUMS`, SHA-256
`c4f7f187a832405baddf4b2daf54c7ec1eccf452af084f2ea41fb38f9be9fba8`.

Native BE SHA-256:
`fa3c2d8a568cdabfa440c47ff94ef67500dfcaab53fed2b82eff696f2104e8ab`.
FE main JAR SHA-256:
`e9d9a652ae5e26418fa53ed5fb49c8e1dfd74a1760e5f72eef918260abd2850f`.
Shipped native ELF undefined-GLIBC audit passes at maximum **2.10**, within 2.17.
Qualified pristine immutable images:

- FE `doris-fe:ducklake-9fb3e3a2-api30`:
  `sha256:abbc36e3dc4f3c528b6c6fbd2c162311075d26a0d21f58cb29bf35184686b9cd`.
- BE `doris-be:ducklake-9fb3e3a2-api30-asan`:
  `sha256:1f73d01a59e86a768e80bf035b6d49987ed3819103a7923e60ced398bf6d9bda`.

Native/FE/five SDK JAR bytes inside these images match their build manifests.
DuckLake's actual source/provider-attested runtime qualification passes: **28/28
full-smoke checks**, **3/3 live tests per Java25/Java17 JVM**, measured replay,
A/B replacement, persisted restart, verified exact A rollback and two distinct
refusal controls. The BOOLEAN blocker reproduces on old API27 in lazy off/on;
all **56 new native-S3/DuckLake BOOLEAN comparisons** pass in the matching API30
runtime. NULL alignment, predicates and aggregates are checked, not just counts.

The original long-lived ASAN fixture hits its internal memory guard only at the
last stale-provider control. That original run remains marked failed. Only the
blocked control was rerun successfully on a fresh owned stack with unchanged
artifacts/caps; no full suite or smoke repeat. The verified aggregate receipt is
`$W/output/api30-runtime-results-verified.json`, binding both original and focused
evidence without erasing the failed attempt. Both stacks clean their owned
resources and preserve unrelated state. This is not JDBC/Quack qualification.

DuckLake's new-SDK headless tests pass **335 on Java25 / 292 on Java17**, with
the same one pre-existing disabled inline-delete test per JVM. Build-logic 8/8,
Java17 runtime-floor scan and independent bundle/dependency identity checks pass.
An enforced nesting finding in the added, excluded live test was corrected and
only compilation/Detekt rerun; the full passing suites were reused. The retained
first command exits 1 for that lint finding, not a test failure. No Duckbridge
test is claimed by these results.

The exact eight-module SDK is now installed locally for DuckLake only, with full
API27 displaced-directory and independent backup retention. The normal no-override
DuckLake ZIP/identity receipt matches the live-tested A bytes. Duckbridge should
still use its own project-local SDK rather than inheriting the global install.

## Canonical Duckbridge patch: read-only applicability only

Canonical file:
`/home/jayson/DEV/brikk/duckbridge/doris-patches/be/0001-duckdb-type-handler.patch`.
SHA-256: `a512411db5d236e864df56871a72c14da3bf343511375181b1390a3e1e181df4`.
`git apply --check` succeeds against the pristine exact API30 donor. This checks
applicability, not semantics or runtime behavior. It changes four Java-only
production/test files in JDBC scanner; it was **not applied** in the donor.

Duckbridge must preserve its own old project-local SDK, bundle, patched JNI tree,
images and volumes; use a dedicated exact-pin worktree and its own local Maven
repository. Do not change the donor, global Maven state, shared staged source or
DuckLake's active artifacts.

After applying the canonical patch in that new worktree, prove native-input
identity before reuse, then rebuild/package the **complete** shared Java/JNI/plugin
tree through upstream `OUTPUT_BE_BINARY=1 bash build.sh --be-java-extensions -j2`.
Do not splice only `jdbc-scanner.jar`. Verify static plugin layout, Java17 floor,
actual patched classes, complete closure and native/FE artifact identities.

The donor's operational entry points are retained beside its output:

```sh
# Reproducibility reference, not commands to rerun in or overwrite the retained donor.
python3 output/api30-run.py spi
python3 output/api30-run.py fe
python3 output/api30-export.py
python3 output/api30-run.py be
python3 output/api30-audit-engine.py
bash output/api30-image.sh fe
bash output/api30-image.sh be
```

The scripts refuse existing phase logs/exports/images. In a dedicated receiver
worktree, adapt owned paths and resource names, never remove donor evidence to
force reuse. Verify full source and build inputs, then compare reused native/FE
hashes with this handoff. The Java-only patch does not authorize changing native
code or treating a native rebuild from another SHA as equivalent.

Retain independent Duckbridge checks: module tests, upstream JNI/JDBC tests,
actual JDBC/Quack types/Unicode/temporal reads, provider identity, persisted restart,
plugin isolation/failure/recovery and runtime hashes. DuckLake's native-S3 tests
cannot qualify the patched JDBC/Quack path or every JNI plugin.

## Resource limits and rollback

Run heavy phases serially. Donor builds use two native compile workers, three CPUs,
13 GiB native cap/no added swap, one link job/one LLD thread; Java phases use
8 GiB/3 GiB heap. Keep C++ output, Maven cache and image staging on disk, not tmpfs.
Memory peaks/OOM counters are saved per phase. Do not run another build or live
cluster alongside the native build merely because its current RSS is small.
Observed peaks were SPI 2.27 GiB, FE 7.97 GiB and BE 13 GiB, with zero OOMs or
safety stops. Keep runtime verification in fresh owned fixtures: ASAN allocator
retention can hit Doris's internal memory guard in a long combined campaign even
without a cgroup OOM. Preserve that failure and rerun only the blocked control;
do not relax guards or relabel already-failed evidence.

The old API27 donor, Duckbridge canonical patch/project-local SDK, images and
volumes remain unchanged. Roll back by selecting the complete old matched set,
not by mixing SDK/FE/BE or replacing JARs under a running classloader.

See [the completed qualification report](archive/VALIDATION-DORIS-API30-2026-11-10.md).
