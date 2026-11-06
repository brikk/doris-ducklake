# Catalog 0.16.0 adoption — 2026-11-06

Owner-directed promotion of formal **0.16.0 from Maven Central**, release commit
`7783db5e2c76b00557e64e4d8b9538c2f5ea7f60`, tag `v0.16.0`; the owner reports publish
workflow `42244438485` green. All four JARs are byte-identical to the authorized
[local 0.16 trial](TRIAL-PGBOUNCER-LOCAL-0.16.md).

## Adoption and scope

- The normal version catalog now selects catalog/fixtures/replay/SLT **0.16.0**.
  The default bundle is rebuilt with the released catalog. JDBC 2.0.0.1, Doris
  API27 SDK/FE/BE images, corpus pin, format admission and feature gates stay unchanged.
- All configuration work in local commit `de1cfbd35dca3fdfff3aa77c3554fe8200ea2423`
  is retained: five pool properties, unchanged defaults, URL-parameter pass-through,
  validation, historical-reader API isolation and the PgBouncer deployment requirements.
- The catalog-specific `mavenLocal` trial override is deleted. Normal catalog
  resolution uses Central; the separately scoped local Doris SDK repository is
  intentionally unchanged. No owner-local Maven artifact is deleted or rewritten.
- The proxy fixture/regression is retained in the released test sources as an
  **opt-in `pgBouncerSmokeTest`**, not automatically added to ordinary plugin test
  execution. Its assertions are preserved. Hikari's existing runtime dependency
  becomes test compile-only API for the effective-setting inspection.
- **No tests are rerun.** Per owner direction, compile/package, lint and static
  Java17/package checks are sufficient for the identical library bytes. No full
  suites, corpus, live proxy or FE/BE deployment is performed in this adoption.

## Independently verified Central bytes

Downloaded from `https://repo.maven.apache.org/maven2/` and matched to all supplied
SHA256 values plus published SHA1 sidecars:

```text
4b9659ef1100a8350b1d09b353e65448e26cdab2cc85c85b6f5d800848c6e234 ducklake-catalog-0.16.0.jar
ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f ducklake-catalog-0.16.0-test-fixtures.jar
3b3ddd334e9eb9cd971062cc2b19b1b2d7ee535538c46f005b5db01277300c11 ducklake-test-corpus-replay-0.16.0.jar
ee37d13d863437eaaf36fe4c34f8d66cfa1cb6d1349ef661ae4f50d2e91120be slt-format-0.16.0.jar
```

Normal resolved artifacts and packaged catalog bytes are checked against the same
hashes. Independent downloads, the previous 0.15.1 default ZIP/receipt and all
compile-only evidence belong in `build/validation/catalog-0.16.0/`.

## Compile/quick-check record

The only Gradle targets requested are `compileKotlin`, `compileTestKotlin`,
`detekt`, `assemble`, `verifyJava17Runtime` and `qualificationDependencies`.
`test` and `java17PluginTest` are explicitly excluded. One worker, no retained
daemon, private 4 GiB/no-swap/150% CPU scope; no Docker fixture startup.

**PASS:** main/test compilation, Detekt, assembly, packaged provider identity,
Java17 runtime scanning (**24,771 classes**) and dependency recording succeed.
All four normal resolved release JARs match the independent Central downloads and
the owner's supplied hashes. The normal ZIP contains exactly
`lib/ducklake-catalog-0.16.0.jar`, whose bytes match the verified release/trial hash.
No `0.16.0-SNAPSHOT` component appears in the normal dependency report.

**Zero tests were executed**: the compile-only log contains no test-task execution.
No live proxy or FE/BE was started. The private scope is unloaded and tested/compiled
source fingerprints stayed unchanged. A source comparison against `de1cfbd` shows
no changes to its production configuration code or read-tier pool bindings.

```text
build_id doris-ducklake-v1-65d6ef4c73a9c31dc3e7a9bb52a5817e8440a0b6318a55f6db94b45cfb55760a
plugin_zip_sha256 5c6cb000797b2b5eb39f4b85425fc30f4766ca7b6ad25577d32af077b84446b8
```

`build/validation/catalog-0.16.0/` retains `summary.json`, `scope.json`,
`compile-only.log`, source fingerprints, verified downloads and the previous
default bundle/receipt. Normal resolved coordinates and runtime scan are recorded
in `build/reports/qualification-dependencies.txt` and `java17-runtime.txt`.

The earlier local trial's **8/8 Java25 configuration tests and 9/9 Java17 tests**
(including the real transaction-mode PgBouncer smoke) remain its own evidence,
not freshly run tests on the formal coordinate. The inherited byte hashes are
explicit, but this adoption does not claim new fleet/production, full-corpus,
historical-reader, file-lease or broader read/write qualification.
