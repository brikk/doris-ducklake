# Development stack, replacement and acceptance

Commands below run from the repository root. This is a development administration
stack, not a production deployment recipe. [Runtime/SDK pins](DORIS-RUNTIME.md)
and [qualification limits](VALIDATION-DORIS-API27-2026-11-02.md) are authoritative.

## Prerequisites and everyday commands

- Exact local linux/amd64 FE/ASAN-BE images from the runtime guide; the public
  smoke can use Docker or a `docker`→Podman shim. New daemon implementations need
  their own effective-network/transport checks.
- JDK 25/build tooling, host Python 3.9+ and a Docker-compatible daemon.
- Local `python:3.12-slim` for stopped-install helpers. Pull it deliberately before
  deployment; replacement helpers use its resolved image ID with no network/pulls.
- Sibling `../trino-ducklake/compose/` substrate/project network
  `trino-ducklake-dev_default`, with PostgreSQL and MinIO. The driver starts only
  those services, not its shared-lake bootstrap.
- Full smoke seeds using DuckDB 1.5.6 and S3 helpers. Dedicated acceptance additionally
  requires the exact local PostgreSQL/MinIO/helper images, `minio/mc:latest` resolved
  immutably, a released DuckDB 1.5.6 CLI and fixture dependency-install connectivity.

```sh
./gradlew assemble
compose/smoke.sh --preflight-only
DORIS_BE_PLATFORM=linux/amd64 compose/smoke.sh --up-only
DORIS_BE_PLATFORM=linux/amd64 compose/smoke.sh --no-build
```

The qualified images are amd64; the smoke wrapper detects platform, but explicit
amd64 is required when invoking compose directly because its YAML fallback is arm64.
Do not run the qualified image under accidental architecture emulation.

`--preflight-only` reads artifacts/rendered configuration/daemon ownership without
service mutation. `--up-only` performs verified replacement/start/provider attestation
without allocating a lake; it is not SQL correctness acceptance. Full mode runs the
28 required supported-scope assertions, including real read/write/default/delete/
bucket/DDL and maintenance effects. It does not certify every type or all corpus SQL.

## Artifact and target identity

Keep the chosen ZIP and adjacent `.identity.json` together. Without `--plugin-zip`,
exactly one matching ZIP is required; no newest-mtime selection. Explicit selection
implies no build:

```sh
compose/smoke.sh --preflight-only --plugin-zip /absolute/path/chosen-plugin.zip
DORIS_BE_PLATFORM=linux/amd64 compose/smoke.sh \
  --plugin-zip /absolute/path/chosen-plugin.zip --up-only
```

Artifact selection does not choose a different deployment target. Inspect the
rendered project/container/volume/image and network bindings first. Foreign,
wrong-image/mount, legacy-volume or ownership collisions refuse, not auto-migrate.
Default volumes use `doris-ducklake-api27-4bc7f426-*`. API-major equality alone
does not establish FE/BE wire compatibility; exact source checks run before allocation.
Image/source overrides are explicit qualification inputs, not bypasses.

The cooperating deployment path claims the actual daemon/plugin-volume target,
stages the verified complete candidate, backs up all prior plugin files, confirms
only its owned FE is stopped, installs/verifies exact contents, then starts a fresh
FE process without resetting metadata, BE storage or the lake. The claim stays held
through attestation and the invocation. Manual Docker writes must not race it.

Required SQL observation:

```sql
SELECT EXTENSION_NAME, EXTENSION_TYPE, EXTENSION_VERSION, SOURCE, DESCRIPTION
FROM information_schema.extensions
WHERE EXTENSION_NAME = 'ducklake';
```

Exactly one `ducklake / CONNECTOR / EXTERNAL` row must carry the selected build ID
as `EXTENSION_VERSION`. Disk hashes, FE health or API equality do not prove which
provider is loaded. `REFRESH CATALOG`/DROP+CREATE do not reload the classloader.

## Recovery and receipts

`build/smoke-deployments/deploy-<UUID>/` retains staged candidate/identity,
`previous.tar`/`previous.json`, lifecycle and verification records. Preserve them.
An occupied claim refuses even after interruption; do not steal it based on age.
Inspect its exact ID/owner and confirm the client/installer/smoke worker is dead
before deliberate recovery. An uncertain install/start leaves FE stopped or the
claim retained; there is no automatic rollback or recovery-artifact cleanup.

Rollback uses a **verified complete old ZIP plus adjacent receipt**, the correct
target's preflight and the same claimed/stopped-install/start path. Legacy tar-only
recovery needs reviewed offline restoration, not a fabricated receipt or `--down`.
Retain election address/name, cluster identity and FE runtime config. One exact
first-boot priority-network append is recorded and bounded; arbitrary config drift refuses.

- `lifecycle.json`: incremental process/install facts, not loaded-code/correctness proof.
- `deployment-attested.json`: installed files, fresh process/config, exact engine
  and load-time provider identities.
- `smoke-assertions.json` / `smoke-correctness.json`: required typed checks and
  owner/build/run/runtime/policy-bound full-smoke proof.
- `run-result.json`: final invocation and claim cleanup outcome. Require this to
  match the observation; later failures retire passing-looking observations to
  `*-before-error.json`. `--up-only` never claims correctness.

Do not accept pending/partial ledgers, zero child exits with missing checks, old
green logs or a same-count wrong tuple as success. Current expected failures are
empty; future reviewed expected failures remain non-green.

## Lake ownership and teardown

Full runs allocate new `doris_smoke_<32-hex-ID>` PostgreSQL databases and FE
catalogs, and `s3://ducklake/doris-smoke/<ID>/` prefixes. They refuse an existing
database/nonempty or unlistable prefix. Every helper checks matching `SMOKE_RUN_ID`,
`PG_DB`, `DATA_PATH` and persisted data-path identity. The shared `ducklake`
database/`s3://ducklake/data/` lake is not a smoke target.

Normal full-run fixtures remain on success/failure for inspection. Reclaim only
explicitly selected acknowledged resources, never all databases/objects matching a
prefix. Corpus startup also does not sweep abandoned runs. This is accidental-target
protection, not tenant isolation: shared credentials and container networks remain.

```sh
compose/smoke.sh --down
```

**Destructive:** `--down` destroys the selected owned Doris metadata/plugin/storage
volumes, not PostgreSQL/S3 run fixtures or substrate. It is not plugin reload,
claim recovery or migration. Never use broad substrate `down -v` as an upgrade.
Retained legacy stacks/data require a separate ownership/lifecycle decision.

## Network access

Passwordless `root` is only for trusted development administration. FE HTTP 8030
and MySQL 9030 publish on **daemon-host loopback**, not necessarily client-machine
loopback. FE RPC/edit-log and BE ports remain unpublished. For remote use:

```sh
ssh -N -T -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:18030:127.0.0.1:8030 \
  -L 127.0.0.1:19030:127.0.0.1:9030 user@daemon-host
mysql --protocol=TCP -h127.0.0.1 -P19030 -uroot
# Browser: http://127.0.0.1:18030
```

Use host-key verification and approved key authentication; no `ssh -g`, wildcard
forward or unauthenticated proxy. Preflight/revalidation inspect actual requested
and effective mappings, including stopped containers, and reject nonloopback binds,
published internal ports, host/shared network and publish-all. `docker start` does
not update legacy port bindings; the driver refuses rather than recreates/migrates.

Wildcard binding is **not proof of internet reachability**; host firewall/routing
still govern access. Loopback is containment, not tenant authorization: trusted
host/Docker administrators and container-network peers remain privileged. Use Docker
28+ to avoid its older same-L2 loopback-publication caveat. Substrate exposure,
host firewall, TLS and production ingress/roles are outside this harness's certification.

## Opt-in isolated acceptance

Prepare ordinary A and distinguishable resource-only B (same code/dependencies/API,
different content identity); B stays in private build output:

```sh
./gradlew assemble verifyJava17Runtime
F24_ACCEPTANCE_PROJECT_ROOT="$PWD" \
F24_ACCEPTANCE_BUILD_DIR="$PWD/build/f24-acceptance-B" \
  ./gradlew -I compose/acceptance-build-B.init.gradle assemble verifyJava17Runtime

python3 compose/plugin_acceptance.py \
  --bundle-a build/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/f24-acceptance-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /absolute/path/to/duckdb-1.5.6
```

This creates new UUID-owned FE/BE/PostgreSQL/MinIO infrastructure, uses the unmodified
public driver, proves loaded A/B/exact reads/metadata preservation/verified rollback
and stale-provider refusal before allocation, then removes only acknowledged resources.
It never installs into retained stacks. Private evidence is printed by the runner.

With the same inputs, `compose/smoke_acceptance.py` exercises full supported
correctness plus same-count tuple-corruption refusal. `compose/network_acceptance.py`
exercises actual loopback and approved/unapproved SSH-key access (requires local
`ssh`, `sshd`, `ssh-keygen`); it deploys A only and does not claim full-smoke SQL proof.
Native nested/replay checks: `python3 compose/read_correctness_acceptance.py --bundle-a … --bundle-b … --duckdb … --evidence-root …`
runs the `readCorrectnessLiveTest` / `readCorrectnessLiveJava17Test` probes on an owned API27 stack.

Cluster-free regressions and opt-in simulated-Docker transport commands are in
[DEVELOPMENT.md](DEVELOPMENT.md). Mock/simulated FE tests are never loaded-Doris proof.

## Practical diagnostics

- No backend for `SELECT 1`: use FE-local `SHOW FRONTENDS`, then check BE heartbeat.
- Wrong source/API/plugin volume: reproduce the exact matched runtime; don't hand-edit
  manifests or delete old volumes to bypass preflight.
- cgroup-v2 JVM startup failure: `fe.conf` carries bounded heap and
  `-XX:-UseContainerSupport`; preserve those actual file-based flags.
- Plugin invisible: use singular `plugins/connector/ducklake`, inspect load logs and
  exact `information_schema.extensions` identity, not directory presence alone.
- Tests skipped/empty mirrors: inspect prerequisites/coverage, not only command exit.
