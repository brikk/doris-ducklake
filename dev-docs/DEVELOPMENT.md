# Building and validating the connector

Run commands from this standalone repository's root, not the former umbrella
project. Use JDK 25 for Gradle/build logic and a Java 17 toolchain for FE-runtime
tests. `mise.toml` describes local tooling. Docker-compatible access is needed for
PostgreSQL/Testcontainers tests. Live engine tests are separate, explicit tasks.

## Default lane

Install the exact [Doris SDK](DORIS-RUNTIME.md), or scope its repository explicitly:

```sh
./gradlew test java17PluginTest check assemble qualificationDependencies
# Isolated SDK selection, without changing mavenLocal():
./gradlew check assemble -PdorisRepository=/absolute/path/to/pinned-sdk
```

Default catalog/fixtures/replay/SLT use released 0.12.0 and test JDBC 1.5.6.0.
`test` runs the ordinary Java-25 suite; `java17PluginTest` selects plugin tests on
Java 17 and excludes newer-JVM corpus tooling. `check` includes static analysis,
runtime/package checks and build-logic tests. Inspect test execution/XML instead
of counting up-to-date tasks as fresh evidence.

The ZIP and `.identity.json` are in `build/distributions/`. Preserve both: the
receipt records effective content, compile SDK and complete bundle hashes, not
just Git HEAD/API/version. Never hand-stamp an identity or promote a candidate
merely because Gradle resolves its coordinates.

For a cold rerun after missing generated classes or a suspect incremental cache:

```sh
./gradlew test java17PluginTest check assemble --rerun-tasks \
  -Pkotlin.incremental=false --max-workers=1
```

This changes execution strategy, not assertions, timeouts or expected outcomes.
Use owned disk-backed temporary directories and lower concurrency under pressure;
do not stop other sessions' Gradle daemons or relax tests to get green.

## Source map

- `src/`: shared connector lifecycle, metadata, scan/commit planning and field-ID encoding.
- `read-tiers/released/`: default 0.12 type mapper/reader configuration.
- `read-tiers/r07/`: separately selected published-candidate parser/reader configuration.
- `test/src/`: ordinary plugin tests, replay adapter and default native live probe.
- `test/r07/`: extra candidate tests and bounded live probes.
- `build-logic/`: packaging identity/manifest and shared build conventions.
- `compose/`: deployment/provenance/correctness supervisor, fixture controllers and tests.
- `ducklake/`: pinned upstream corpus submodule, not vendored connector implementation.

## Validation levels

1. **Headless:** plugin tests, Testcontainers PostgreSQL/native fixtures, lint,
   runtime ABI/package checks. These do not execute an FE+BE query.
2. **Transport/orchestration:** Python controller tests. Most use fake Docker or
   local volume workers; opt-in Docker tests are not real Doris acceptance.
3. **Owned live acceptance:** exact SDK/bundle/FE/BE identities, loaded provider,
   SQL tuples/metadata/storage effects, replacement/restart/rollback and refusal controls.
4. **Replay:** explicit, bounded mirrored queries against oracle-built state,
   with measured accepted/executed/skipped coverage. Not implied by `test` or `check`.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s compose -p 'test_*.py'
bash -n compose/smoke.sh compose/smoke_run.sh
```

Python test discovery includes opt-in real-Docker tests that skip unless explicitly
enabled. Report skips honestly; the qualification's frozen-source 130-case run is
a separate recorded result, not a promised count for every invocation.

See [COMPOSE.md](COMPOSE.md) for A/B/full-smoke acceptance and
[CATALOG-CANDIDATE.md](CATALOG-CANDIDATE.md) for separate native read harnesses.
The API27 qualification's extended 450-DEFAULT/TopN/timezone/restart controller
and exact build scripts are retained at the evidence paths in the
[qualification report](VALIDATION-DORIS-API27-2026-11-02.md); they are local evidence,
not tracked scripts available in every clone.

## Interpretation and promotion

One parked inline-delete future-feature test remains disabled. Do not add new
skips/expected failures, modify semantic goldens or label an all-skipped run as
coverage. A full smoke proves its 28 required checks, not all types/features or
the full corpus. Source tracing and upstream PR tests are not local live proof.

Only promote exact qualified bytes after checking both lanes, refusal controls,
rollback and preservation of shared/retained state. Keep release performance,
all-JNI workloads, Duckbridge JDBC/Quack and production/fleet claims separate.
Current results are in [API27 validation](VALIDATION-DORIS-API27-2026-11-02.md).
