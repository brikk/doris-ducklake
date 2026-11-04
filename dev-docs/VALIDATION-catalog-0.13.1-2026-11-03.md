# Catalog 0.13.1 adoption and full corpus — 2026-11-03

## Scope and fix

The default catalog, test fixtures, replay and SLT modules now resolve **0.13.1**.
JDBC remains **2.0.0.1**. Only the replay publication's code changed from 0.13.0;
the catalog, fixture and SLT JAR bytes are identical under the new coordinates.
Doris SDK/API27, engine images, Quack absence and experimental feature gates are unchanged.

The released corpus runner uses `ReplaySetupFormat.DUCKLAKE_1_0` through a
release-only `dorisCorpusRunner` factory. It configures the native oracle's
`ducklake_default_version`, not corpus SQL or goldens. Explicit versions and
SET/RESET keep their native meaning; attaching an existing newer catalog fails
instead of rewriting its stored version. Three consumer regressions exercise the
actual campaign factory: implicit 1.0, explicit 1.1, and refused downgrade.

The historical checksum-attested reader candidate remains on its old API/dependencies
through a separate test factory. It is not the lane selected for the full campaign.

## Maven Central verification

Independently downloaded and checked against Central's SHA1 sidecars; SHA256:

```text
01ffed5abb0b970b307dd4de1b7d7ab42320e33016447896204e5a36506a3041 ducklake-catalog-0.13.1.jar
ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f ducklake-catalog-0.13.1-test-fixtures.jar
403970a1c3fa6b6cc0cff8325f2a427eed630ae5f4f03bcf6fe0af8cddb502c5 ducklake-test-corpus-replay-0.13.1.jar
ee37d13d863437eaaf36fe4c34f8d66cfa1cb6d1349ef661ae4f50d2e91120be slt-format-0.13.1.jar
```

Downloads/source verification: `/tmp/opencode/doris-catalog-0.13.1-central/`.
All four resolved Gradle artifacts match those independently verified bytes.

Default bundle identity:

```text
build_id doris-ducklake-v1-cfb6a373510958a4becd15b8f79c9309a82ccb2ea0545dcc9bb80b7206249904
provider_jar_sha256 90fc446da9f8575336e167f20e4f03c3d0e4686c48394341e5276be0cdf5d1ec
plugin_zip_sha256 c6841213020a9eae9db095965077669b1f22292615febc34fb12c8afb72daa05
```

## Validation status

Fresh nonincremental default checks pass:

- Java 25: **280 passed, 1 existing disabled test**.
- Java 17 plugin tests: **249 passed, the same 1 disabled test**.
- The three consumer setup-format regressions pass on **both** JVMs.
- **8 build-logic tests**, Detekt, Java 17 runtime scan, package identity/assembly
  and dependency qualification pass. Log: `/tmp/opencode/doris-catalog-0.13.1-default.log`.

**Paused at the user's request to free resources for Trino. Full corpus is not complete.**
No automatic restart or further build/test work is authorized until the user resumes it.

- Changed Python release guards: 3/3 passed, refusing old/mixed bundles before infrastructure work.
- Full campaign uses the existing curated exclusions and **40-mirrors-per-file** cap; it does
  not add expected failures/skips or claim that every discovered query was mirrored.
- The disposable controller is `/tmp/opencode/run-doris-full-corpus-0.13.py`.
  It forwards private endpoints explicitly, binds host/FE/BE corpus paths identically,
  verifies actual test execution and positive mirror coverage, and owns only fresh
  UUID infrastructure. A complete failure summary is persisted after each chunk.
- Discovery selects **475 files** at this consumer's corpus commit
  `c23aca43b6033acfac2b8cc8d80b15ae7fa729b2`.
- The first setup attempt (`/tmp/opencode/f24-e-1gc3_hgn`) stopped before test
  execution: the private Gradle init script also configured the included build-logic
  project. Scoping that hook to the consumer root fixes the controller error. That
  attempt has no corpus coverage; its owned resources were cleaned and its protected
  state comparison passed. The campaign was relaunched on fresh infrastructure.
- The retry (`/tmp/opencode/f24-e-ixnw6pv5`) was interrupted to correct read-only
  same-path repository fixture mounts; it is not a successful complete campaign.
  Its owned containers/volumes/networks are gone and its protected-state comparison
  passed. The mount/process-lifecycle corrections are prepared in the one-off
  controller but have **not** been launched again.
- At the user's resource pause, all Doris campaign/build jobs had stopped. The
  remaining Doris-only Gradle daemon and two Kotlin compiler daemons were explicitly
  terminated after verifying ownership from their logs. Trino, catalog-producer and
  shared processes/containers were left alone. Shutdown receipt:
  `/tmp/opencode/doris-catalog-0.13.1-resource-shutdown.json`.

The corpus checked is this consumer's pinned submodule, not an implicit update to
the producer's newer corpus pin. Retained/shared stacks, lakes, credentials and
source checkouts are not deployment or cleanup targets.

The earlier [0.13.0 adoption evidence](VALIDATION-catalog-0.13.0-2026-11-03.md)
remains tied to its original artifacts and bounded live probes.
