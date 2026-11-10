# Catalog 0.18.0 adoption — 2026-11-09

Owner-directed adoption of the **final 0.18.0 release from Maven Central**, tag
`v0.18.0`, release commit `e8d39a12cd7fa7ef7f412177e585ecafd89ea03f`, implementation
`a4332aab275f7bbdbfbf3be17a296cb7fa4fbffb`. No snapshot trial, sibling build or
local catalog JAR substitution. No catalog-library verification is repeated.

## Scope and compatibility

- One version pin selects catalog, catalog `test-fixtures`, replay and transitive SLT
  **0.18.0**. Default bundle admission in the read-correctness/corpus controllers
  moves to the same release; old, mixed, duplicate and snapshot bundles still refuse.
- Public APIs and JSON shapes remain unchanged. Internal `Final11` → `Spec11`
  renaming and JDBC extractions require no connector changes. No exception-message
  assertions require adjustment.
- The library now compares/prunes `timestamp_us` temporal statistics as values.
  Doris's existing conservative pruning, temporal-domain checks and feature flags
  are retained; no new planning optimization is enabled.
- Doris already uses exactly `{DUCKLAKE_1_0}` for execution and mirroring, with 1.0
  setup. An unexpected native SQL migration refusal now records one format-scope
  skip and halts the file. The factory regression exercises a native refusing query
  inside nested loops: prior passes, a prior intentional golden mismatch and measured
  control-engine coverage survive; later records/iterations do not run or add counts.
  Another regression proves matching expected-error/maybe refusals continue.
  These use the released replay JAR and Doris's unchanged runner factory, not copied
  producer logic. The control engine is not a live Doris mirror qualification.
- Stored-format admission, goldens, corpus/submodule pin, skip lists, mirror minimums,
  native JDBC **2.0.0.1**, brikk-sql **0.18.1**, Doris API27 SDK/images and runtime
  feature defaults are unchanged. DuckLake 1.1 still refuses; no experimental reader
  is reintroduced. Existing known failures/disabled tests are not reclassified.

## Published artifact checksum receipt

All coordinates are in group `dev.brikk.ducklake` at **0.18.0**; the second JAR uses
classifier `test-fixtures`. Expected release SHA-256s are also preserved in
[`catalog-0.18.0-SHA256SUMS`](catalog-0.18.0-SHA256SUMS):

```text
33a6c410e21d5c54c0d2a7cf24155876b10d97c113f2ead8b1101b4488e47c4b  ducklake-catalog-0.18.0.jar
dd8eea0415dfa9a0c7f02ed270e4aa66d87c329d03469579f91c2d5361f0a9bd  ducklake-catalog-0.18.0-test-fixtures.jar
72146c7e5c5c10e6a4f828d76e6507e92eacb3f1db21c8d8072375fe67a2577f  ducklake-test-corpus-replay-0.18.0.jar
d48d254b5b422e44e64b21e90902e53f5fd372f0df9dc2da8633985776c5a3d1  slt-format-0.18.0.jar
```

Normal Gradle resolution uses Maven Central; only the unrelated Doris SDK resolves
from its exclusive local repository. Resolution is checked against the producer's
final-release receipt and the hashes above, including the catalog inside the ZIP.
The previous default ZIP/identity receipt is preserved, not installed or removed.

## Single adoption verification pass

```sh
JAVA_HOME=/home/jayson/.local/share/mise/installs/java/25.0.2 \
  ./gradlew test java17PluginTest check assemble qualificationDependencies \
  --continue --no-daemon --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s compose -p 'test_*.py'
bash -n compose/smoke.sh compose/smoke_run.sh
```

The single Gradle invocation completed successfully in **3m 20s**. No full suite
was repeated for a snapshot, release or documentation-only edit.

- **Java 25:** 333 tests, 332 passed, zero failures/errors, one pre-existing disabled
  inline-delete test (`DuckLakeScanPlanProviderTest.emitsDeletesForInlineDeletePath`).
- **Java 17:** 290 tests, 289 passed, zero failures/errors, the same disabled test.
- **Released replay factory:** all five tests pass on both JVMs, including the two
  new halt/expected-error accounting regressions. No newly skipped tests.
- **Build logic:** eight tests pass; the unchanged test task is reused as up to date.
- Main/test compilation, Detekt, assembly, artifact identity and dependency recording
  pass. Java 17 runtime scan: **24,820 classes**, all within the supported floor.
- All four normally resolved final artifacts match the expected SHA-256s. Independent
  `plugin_preflight.verify_bundle` validation passes; the ZIP contains exactly
  `lib/ducklake-catalog-0.18.0.jar` with the catalog hash above. Test fixtures,
  replay/SLT and brikk-sql are not bundled. The compile SDK identity and every
  unrelated bundled runtime dependency's bytes are unchanged.
- Tracked sources stayed unchanged during the test run. Shell syntax checks pass.
- **Python controllers:** 154 tests, 152 passed, zero failures/errors, two existing
  opt-in real-Docker tests skipped. This is the original invocation, not a rerun.

The subsequently requested advisory-only Detekt policy was checked with assembly,
repository Detekt and temporary positive/negative lint probes only; no full suite
was rerun. Its assembly preserves this verified ZIP/identity receipt byte-for-byte.
See [the policy validation report](VALIDATION-DETEKT-ADVISORIES-2026-11-10.md).

Generated bundle identity/checksum receipt:

```text
build_id doris-ducklake-v1-f145109797485026ac8431a5e2f4953b3e6ba9fe65da86253cf018bcd5daa9bb
plugin_zip_sha256 adf6de2b4e556d10064b1e0029708044153698454f5b33c0741893df6ed79345
provider_jar_sha256 d6034969935b60c65ccaa4161e8fda166e9fc83eb27649f2a51fe111017071ba
compile_sdk_id d2411c1b146aa1c76f308e6d815ef52a4e8e04a621e1852d0fa143af977e0bff
```

Evidence root: `build/validation/catalog-0.18.0/`. No FE/BE deployment, live corpus
campaign, PgBouncer smoke, push, release, tag or publication is part of this adoption.
Prior corpus results and unrelated known failures remain as documented; a green
headless pass is not broader live/fleet qualification.
