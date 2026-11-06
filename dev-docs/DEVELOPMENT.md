# Building and testing

Run commands from the repository root. The build uses JDK 25 (see `mise.toml`) and emits
Java 17 bytecode because the Doris FE runs on 17. Testcontainers tests need a
Docker-compatible daemon. Live FE/BE tests are separate, explicit tasks.

## Everyday commands

Install the pinned [Doris SDK](DORIS-RUNTIME.md) into `~/.m2`, or point at a copy:

```sh
./gradlew test java17PluginTest check assemble
./gradlew check assemble -PdorisRepository=/absolute/path/to/pinned-sdk   # without touching mavenLocal
```

- `test` — the Java 25 suite: plugin tests against Testcontainers PostgreSQL plus the
  replay harness's own unit tests. Live-cluster tests are excluded.
- `java17PluginTest` — the plugin tests again on a Java 17 toolchain, matching the FE.
- `check` — detekt, the Java 17 classfile scan of every runtime jar, plugin zip and
  identity receipt, and `build-logic`'s own tests.
- `assemble` — `build/distributions/doris-ducklake-<version>-plugin.zip` and its
  `.identity.json`. Keep them together: the receipt records the effective payload,
  compile SDK and bundle hashes, and the smoke driver checks it.

The DuckDB test bootstrap creates DuckLake 1.0 lakes with automatic migration off.
A 1.1 lake is refused by the connector; see `DuckLakeCatalogFormatTest`.

For a cold rerun after a suspect incremental cache:

```sh
./gradlew test java17PluginTest check assemble --rerun-tasks -Pkotlin.incremental=false --max-workers=1
```

When building several output directories concurrently, give each its own
`--project-cache-dir`; shared task history can clean another build's outputs.

## Source layout

- `src/` — the connector: lifecycle, metadata, type mapping, scan and write planning,
  field-id schema dictionary, maintenance procedures.
- `test/src/` — plugin tests, the corpus replay harness, and live probes.
- `build-logic/` — plugin identity/manifest tasks and shared Gradle conventions.
- `compose/` — the development stack, deployment driver, acceptance controllers and their tests.
- `ducklake/` — the upstream DuckLake repository as a submodule, for its SQL test corpus.

## Live tasks

All need a running stack from [COMPOSE.md](COMPOSE.md) and are excluded from `test`:

| Task | What it runs |
|---|---|
| `corpusReplayTest` | Upstream corpus reads mirrored through the FE; see [CORPUS-REPLAY.md](CORPUS-REPLAY.md). |
| `readCorrectnessLiveTest` / `…Java17Test` | Bounded nested/replay probes. |
| `defaultNestedLiveTest` / `…Java17Test` | Native mixed scalar/nested reads. |
| `readSafetyLiveTest` | Historical-delete and temporal refusals, typed MAP replay. |
| `corpusBaselineTest` | Oracle-only corpus diagnostics, no Doris. |
| `pgBouncerSmokeTest` | PostgreSQL transaction-pooling smoke, no FE/BE; see [PGBOUNCER.md](PGBOUNCER.md). |

Python controller tests:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s compose -p 'test_*.py'
bash -n compose/smoke.sh compose/smoke_run.sh
```

Most use a fake Docker daemon; the real-Docker tests skip unless `F24_DOCKER_TEST=1`.

## What each level proves

1. **Headless** (`test`, `check`): connector logic against a real PostgreSQL catalog
   and real Parquet. No FE/BE query runs.
2. **Controller** (Python tests): deployment and acceptance orchestration, mostly against fakes.
3. **Live acceptance** (`compose/smoke.sh`, `compose/*_acceptance.py`): the loaded
   plugin on the exact pinned FE/BE, with SQL results, storage effects, rollback and
   refusal controls.
4. **Replay** (`corpusReplayTest`): upstream corpus queries compared against a DuckDB
   oracle, with counted mirrors and explicit skips.

A passing full smoke proves its enumerated checks, not every type or the whole corpus.
Read the test XML and mirror counts rather than treating a green task as coverage.
Do not add skips or expected failures to get green; one inlined-delete test is
deliberately `@Disabled` until its fixture and implementation exist.
