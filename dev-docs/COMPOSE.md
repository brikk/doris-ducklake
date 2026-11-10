# Development stack

A local Doris FE + ASAN BE running the plugin against the shared PostgreSQL/MinIO
substrate from `../trino-ducklake/compose/`. This is a development stack, not a
deployment recipe. Commands run from the repository root.

## Prerequisites

- The pinned linux/amd64 FE/BE images from [DORIS-RUNTIME.md](DORIS-RUNTIME.md).
- JDK 25, Python 3.9+, a Docker-compatible daemon (Docker 28+ avoids an older same-L2
  loopback-publication caveat). `python:3.12-slim` pulled locally; the deploy helpers run
  with `--pull=never`.
- The sibling `trino-ducklake-dev_default` network with PostgreSQL and MinIO up. The smoke
  driver starts only those services, not the shared-lake bootstrap.
- For the full smoke: DuckDB 1.5.6 for seeding. For the acceptance controllers additionally
  the exact PostgreSQL/MinIO/helper images, `minio/mc`, and a DuckDB 1.5.6 CLI.

## Smoke

```sh
./gradlew assemble
compose/smoke.sh --preflight-only                              # inspect artifacts, config, daemon ownership; no mutation
DORIS_BE_PLATFORM=linux/amd64 compose/smoke.sh --up-only       # deploy, start, attest the loaded provider; no lake
DORIS_BE_PLATFORM=linux/amd64 compose/smoke.sh --no-build      # full: 28 read/write/default/delete/bucket/DDL/maintenance checks
```

The images are amd64; the wrapper detects the platform, but when invoking compose directly
set `DORIS_BE_PLATFORM=linux/amd64` because the YAML fallback is arm64.

Without `--plugin-zip` exactly one zip must exist in `build/distributions/`; there is no
newest-wins. Explicit selection skips the build:

```sh
compose/smoke.sh --preflight-only --plugin-zip /absolute/path/chosen-plugin.zip
```

The zip's adjacent `.identity.json` receipt must be present; the driver compares it against
what the FE actually loaded.

## How deployment works

The driver claims the FE's plugin volume, stages the verified zip, backs up the previous
plugin files, stops only its own FE, installs, verifies the installed bytes, and starts a
fresh FE process. Metadata, BE storage and the lake are untouched. The claim is held until
the run finishes; don't write to the volume manually meanwhile.

Preflight refuses foreign containers, wrong images or mounts, legacy volumes and ownership
collisions rather than migrating them. Default volumes are `doris-ducklake-api30-9fb3e3a2-*`.
API-major equality is not enough; the exact source commit is checked before allocation.

The only proof of which plugin is loaded:

```sql
SELECT EXTENSION_NAME, EXTENSION_TYPE, EXTENSION_VERSION, SOURCE, DESCRIPTION
FROM information_schema.extensions
WHERE EXTENSION_NAME = 'ducklake';
```

Exactly one `ducklake / CONNECTOR / EXTERNAL` row, with the selected build id as
`EXTENSION_VERSION`. `REFRESH CATALOG` and DROP/CREATE CATALOG do not reload the classloader.

## Receipts and rollback

Each run writes `build/smoke-deployments/deploy-<UUID>/`:

- `lifecycle.json` — process/install facts.
- `deployment-attested.json` — installed files, fresh process config, engine and loaded-provider identity.
- `smoke-assertions.json` / `smoke-correctness.json` — the typed checks of a full run.
- `run-result.json` — final outcome and claim release. If a later step fails, earlier
  passing observations are renamed `*-before-error.json`.
- `previous.tar` / `previous.json` — the plugin files that were replaced.

Rollback is a normal deployment of the previous zip plus its receipt through the same
path. An interrupted run leaves the claim held; inspect its owner and confirm the worker is
dead before recovering. There is no automatic rollback.

```sh
compose/smoke.sh --down   # destroys the owned Doris metadata/plugin/storage volumes only
```

`--down` does not touch PostgreSQL/S3 fixtures or the substrate, and is not a reload or
migration path.

## Lake ownership

Full runs allocate `doris_smoke_<32-hex>` PostgreSQL databases and FE catalogs and
`s3://ducklake/doris-smoke/<id>/` prefixes, and refuse to reuse an existing one. Every
helper checks `SMOKE_RUN_ID`, `PG_DB` and `DATA_PATH`. The shared `ducklake` database and
`s3://ducklake/data/` are never a smoke target. Fixtures are left in place for inspection;
cleanup removes only explicitly acknowledged resources, never by prefix.

## Network access

FE HTTP 8030 and MySQL 9030 are published on the daemon host's loopback only; RPC and BE
ports are not published. From another machine:

```sh
ssh -N -T -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:18030:127.0.0.1:8030 \
  -L 127.0.0.1:19030:127.0.0.1:9030 user@daemon-host
mysql --protocol=TCP -h127.0.0.1 -P19030 -uroot
```

Passwordless `root` is for trusted development only. Preflight rejects non-loopback binds,
published internal ports, host/shared network mode and publish-all, and checks stopped
containers too. `docker start` keeps old port bindings; the driver refuses rather than
recreates. Loopback is containment, not tenant authorization.

## Acceptance controllers

Build an ordinary bundle A and a resource-only variant B (same code, different content identity):

```sh
./gradlew assemble verifyJava17Runtime
F24_ACCEPTANCE_PROJECT_ROOT="$PWD" F24_ACCEPTANCE_BUILD_DIR="$PWD/build/f24-acceptance-B" \
  ./gradlew -I compose/acceptance-build-B.init.gradle assemble verifyJava17Runtime
```

| Controller | Proves |
|---|---|
| `compose/plugin_acceptance.py` | A/B deployment, exact reads, metadata preservation, rollback, stale-provider refusal, on fresh UUID-owned infrastructure. |
| `compose/smoke_acceptance.py` | Full supported-scope correctness plus refusal of a same-count wrong tuple. |
| `compose/network_acceptance.py` | Loopback publication and approved/unapproved SSH keys (needs local `ssh`, `sshd`, `ssh-keygen`). |
| `compose/read_correctness_acceptance.py` | `readCorrectnessLiveTest` on both JVMs against an owned stack. |
| `compose/corpus_acceptance.py` | A bounded full-directory or selected-file corpus campaign; see [CORPUS-REPLAY.md](CORPUS-REPLAY.md). |

All take `--bundle-a`, `--bundle-b`, `--duckdb` (and `--evidence-root` where relevant), never
install into retained stacks, and remove only what they created.

Read-correctness acceptance includes the upstream dictionary-encoded BOOLEAN
fixture through both the DuckLake connector and native S3 TVF, with lazy reads
off/on. Owned acceptance services have hard/no-extra-swap caps (FE 3 GiB, BE
7 GiB, PostgreSQL/MinIO 512 MiB each); serialize build and live phases. A prolonged
ASAN fixture can hit Doris's own memory guard without a kernel OOM. Keep that
failed evidence and rerun only the blocked control on fresh owned infrastructure,
not the already-passing suites or smokes.

## Diagnostics

- `SELECT 1` fails with "no backend": use `SHOW FRONTENDS`, then check BE heartbeat.
- Preflight rejects the target: reproduce the exact pinned runtime; don't hand-edit manifests
  or delete old volumes to get past it.
- JVM won't start under cgroup v2: `fe.conf` carries a bounded heap and
  `-XX:-UseContainerSupport`; keep them.
- Plugin not visible: the root is `plugins/connector/ducklake` (singular); check FE load
  logs and the `information_schema.extensions` row.
- Tests skipped or zero mirrors: check prerequisites and counts, not just the exit code.
