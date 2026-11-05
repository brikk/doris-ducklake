# Catalog 0.15.1 adoption — 2026-11-05

User-directed normal adoption of released **0.15.1**, superseding the failed,
uncommitted [0.15.0 attempt](VALIDATION-catalog-0.15.0.md). Catalog, test fixtures,
replay and SLT use the shared 0.15.1 version pin from Maven Central. JDBC 2.0.0.1,
Doris API27 SDK/images, corpus pin, stored-format 1.0 admission and feature flags
remain unchanged.

## Exactly what was rerun

Only the previous failure:

```text
DuckLakeExpiryMaintenanceTest.cleanupFailureLeavesExpiryAndFirstPageCommittedAndResumesThroughTheNewApi
```

It is selected once in `test` (Java25) and once in `java17PluginTest`. No other test
class, full suite, corpus, copy probe, live SQL or FE/BE deployment is rerun. Gradle
implicitly compiles the changed dependency and packages the normal distribution;
`qualificationDependencies` records the actual resolved coordinates.

The assertion is unchanged: the new exception must report **1,000 committed queued
files**, not 1,002 including the rolled-back page. The test also verifies committed
snapshot deletion, page rollback, cleanup resumption scheduling exactly the last
two files, idempotence, latest snapshot 4 and protection of live files.

The useful snapshot trial tests have been folded into `test/released`; the unused
snapshot-only init script/source lane is removed. The released procedure adapter
preserves `DucklakeExpiryCleanupIncompleteException` and its counts/cause and says
**do not retry expiry; resume metadata cleanup**. The historical candidate's old
API lane is preserved but is not re-qualified here.

## Published bytes

Independently downloaded from `https://repo.maven.apache.org/maven2/`. Each JAR's
SHA256 matches Central's Gradle module metadata and its SHA1 sidecar. The last three
hashes also match the unchanged 0.15.0/snapshot artifacts:

```text
5a5f9c70486f4a7cb30f75f862bfe61056a03ff308d417290a056b0896dd933d ducklake-catalog-0.15.1.jar
ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f ducklake-catalog-0.15.1-test-fixtures.jar
3b3ddd334e9eb9cd971062cc2b19b1b2d7ee535538c46f005b5db01277300c11 ducklake-test-corpus-replay-0.15.1.jar
ee37d13d863437eaaf36fe4c34f8d66cfa1cb6d1349ef661ae4f50d2e91120be slt-format-0.15.1.jar
```

The producer's `docs/releases/RELEASE-0.15.1.md` identifies this exact counter fix:
page counters are added to the total only after a page commits. It does not claim
the old release lost files; recovery already worked.

## Results

**PASS: 1/1 on Java25 and 1/1 on Java17; zero failures, errors or skips.** The
unchanged regression now records on both JVMs:

```text
EXPIRY_PROGRESS reported=1000 committed=1000 resumed=2 latest=4
```

Compilation and assembly succeeded. All four normal resolved JAR hashes match the
verified Central downloads, and the default ZIP contains the exact 0.15.1 catalog
JAR. Tested sources remained unchanged during execution. The private scope is
unloaded and no containers remain running. There were no other test invocations,
full suites, corpus runs or FE/BE deployments in the 0.15.1 follow-up.

```text
build_id doris-ducklake-v1-185b4b98356a428ff835e914bf0be14f49118d6a38725eee3c93f5f980d987fd
plugin_zip_sha256 509ae19be1cae94d96a20946e1cb57c543e2a38f2dac0a13296b5b05d13362b7
```

Final JUnit results (`test.xml`, `java17PluginTest.xml`), `summary.json`, the
corrected marker, resolved/package hashes, source fingerprints and scope receipt
are retained under `build/validation/catalog-0.15.1/`.

Prior evidence is retained distinctly: 0.15.0 had **41 passes / 1 failure on each
JVM**, with the hard-budget, copy/default-bridge, other maintenance and smoke checks
passing. The earlier snapshot had its own full-suite trial. Those are not counted
as fresh 0.15.1 tests. No full-corpus result is changed by this adoption.
