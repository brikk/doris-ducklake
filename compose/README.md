# DuckLake-on-Doris — Live FE+BE smoke

The slow, real-cluster counterpart to `:doris-ducklake:test`. `smoke.sh` brings up
a full Doris cluster (FE hosting our plugin + matching master BE) against the DuckLake
substrate (Postgres + MinIO), seeds a fresh run-owned TPC-H lake, and exercises the plugin end-to-end:

- **read** — `CREATE CATALOG`, `SHOW/DESC`, `SELECT * FROM dl.tpch.orders`
- **Step 7** — latest-snapshot position deletes through OPTIONAL Parquet columns;
  validated end to end on matching master FE/BE `62e3d81a42b`
- **W1 DDL** — live Doris `CREATE DATABASE` + `CREATE TABLE` (unpartitioned and
  `PARTITION BY LIST (bucket(4, name)) ()`) routed FE→connector→DuckLake, cross-verified
  via DuckDB+DuckLake and the catalog tables, then `INSERT` + `DROP`. ✅ GREEN 2026-06-10;
  re-verified patch-free 2026-07-27 (no `ENGINE=`, engine displays `ducklake`).
- **W2 INSERT** — `INSERT INTO dl.tpch.doris_w VALUES (…)` via Doris (target now created
  by Doris `CREATE TABLE`, not DuckDB), then reads it back through **both Doris and
  DuckDB+DuckLake** (cross-engine). ✅ GREEN 2026-06-09.
- **W2c BUCKET INSERT** — `INSERT … INTO dl.tpch.doris_wb` (a `bucket(4, name)` table)
  via Doris, then asserts the bucket each file was tagged with in the catalog is exactly
  `{1,2,3}` — i.e. the BE's Iceberg murmur3 matches DuckLake's (alice→1, bob→2,
  charlie→3). ✅ GREEN 2026-06-09.

## Quick start

```bash
cd compose                    # from this repository's root
./smoke.sh                 # build plugin → up substrate+cluster → drive read/delete/INSERT
./smoke.sh --no-build      # skip the gradle plugin rebuild (zip already current)
./smoke.sh --down          # tear down the Doris cluster (substrate left running)
```

One-time `~20 min` cluster build (FE image, image pulls); `~2–3 min` per re-run.

### Local administration and remote access (F23)

The development FE still uses passwordless `root` for container-local automation.
It is **not a public database service**: HTTP `8030` and MySQL `9030` publish only
on `127.0.0.1` of the **Docker/Podman daemon host**, not necessarily the machine
running the client. FE RPC `9020`/edit-log `9010` and all BE ports stay unpublished;
FE/BE communication uses the existing container networks unchanged.

Remote access is an explicit **authenticated SSH tunnel**, not a wildcard bind or
an environment switch that exposes passwordless SQL. On your workstation:

```bash
ssh -N -T -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:18030:127.0.0.1:8030 \
  -L 127.0.0.1:19030:127.0.0.1:9030 user@daemon-host
# In another terminal (while the authenticated SSH connection stays open):
mysql --protocol=TCP -h127.0.0.1 -P19030 -uroot
# Browser: http://127.0.0.1:18030
```

Use an SSH account authorized for **development administration**, with host-key
verification and your normal key authentication. This tunnel gives local root-level
SQL access; it is not per-user Doris authorization. Do not use `ssh -g`, a wildcard
local forward, or unauthenticated public TCP proxies. Direct remote SQL/HTTP is not
a supported option in this harness: it needs a separately reviewed Doris account,
TLS, authorization and ingress policy, including changes to internal automation.

Preflight checks both rendered port policy and actual requested/effective FE/BE
bindings, even for stopped containers, and rechecks them during deployment.
Public/unspecified binds, published internal ports, host/shared-container network
mode, publish-all and missing inspection metadata refuse before installation.
This matters because `docker start` **does not update old port bindings**. An old
public container is refused, never silently recreated, stopped or migrated. It
may remain publicly bound until its owner explicitly contains/migrates it; changing
this file alone does not secure it. Do not use `down -v` as a migration shortcut.

Loopback binding is containment, **not tenant isolation**. Trusted host accounts,
Docker administrators, and peers with access to the FE's container networks can
still administer it. Direct routing/firewall policy, SSH server hardening and
sibling PostgreSQL/MinIO exposure are outside this change. Use Docker Engine
**28+** (the live test uses 29.6.0); older Docker releases have a documented
[same-L2 loopback-publication caveat](https://docs.docker.com/engine/network/port-publishing/).
Podman's effective bindings are checked too, but this task does not certify every
Podman/rootless/remote-daemon network implementation.

Opt-in acceptance uses the existing F24 private sandbox, deploys real pinned A
in `--up-only` mode, checks host-loopback HTTP/MySQL and BE registration, probes
non-loopback host ports from a separate bridge client, then tests a disposable
key-only SSH server (unapproved key refused, approved HTTP/SQL forwards work):

```bash
python3 compose/network_acceptance.py \
  --bundle-a build/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/f24-e-reproduced-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /absolute/path/to/duckdb-1.5.6
```

Run this from the repository root; prepare resource-only B using F24's recipe
below. The shared fixture validates B/producer inputs, but F23 deploys only A and
does not seed a lake or claim fresh F25 correctness. Requires local Docker and
`ssh`, `sshd`, `ssh-keygen`; no host SSH/account/firewall configuration is modified.
Only acknowledged UUID-owned infrastructure is removed; disposable SSH private
keys are deleted. See [F23 evidence/limits](../dev-docs/VALIDATION-F23-2026-10-31.md).

### Smoke isolation (F21)

The driver reuses only the sibling Trino stack's PostgreSQL/MinIO services. It
does not start the shared-lake bootstrap or mutate the `ducklake` metadata
database / `s3://ducklake/data/` warehouse. After cluster health checks, each full
run generates a new ID and creates:

- PostgreSQL database `doris_smoke_<32-hex-ID>` from `template0`.
- Warehouse `s3://ducklake/doris-smoke/<ID>/`, outside the shared warehouse.
- FE catalog `doris_smoke_<ID>`; an existing `dl` catalog is left alone. Step labels
  still use `dl` as shorthand, while actual SQL uses the printed run-owned name.

The run fails rather than adopting an existing database or nonempty prefix. The
sibling TPC-H generator runs once with explicit database/prefix overrides and
DuckDB 1.5.6 (JDBC 1.5.6.0); the stored DuckLake `data_path` must match before the driver proceeds.

Current qualification uses released catalog **0.12.0**, unchanged by the Doris bump.
Java-25/Java-17 checks, two full isolated smokes, persisted-metadata restart and
targeted TopN/default/type/write probes passed on API 24. See
[October 28 validation](../dev-docs/VALIDATION-DORIS-API24-2026-10-28.md).
The [catalog-0.11.0/API-13 result](../dev-docs/VALIDATION-catalog-0.11.0-2026-10-10.md)
remains historical evidence for its own artifacts.
Every helper, metadata query, maintenance call, and GC fixture uses that same
identity. Standalone Python helpers require matching `SMOKE_RUN_ID`, `PG_DB`, and
`DATA_PATH`, checked by `smoke_lake.py` before opening DuckDB.

`--up-only` brings up infrastructure/FE/BE without allocating or seeding any smoke
lake. Full-run resources are retained on both success and failure for inspection;
the log prints their exact names. `--down` removes only the Doris stack/volumes,
not these PostgreSQL databases or S3 prefixes. Reclaim only an explicitly selected
run's resources after inspection, never a prefix-wide database or warehouse sweep.

This is accidental-target protection, not credential-level tenant isolation.
The FE/BE containers and plugin volume are still shared development resources:
cooperating smoke/deployment/teardown operations now refuse overlapping claims;
manual Docker operations must not race them. F24's provenance scope is verified.
F25 now requires the full supported-scope assertion proof described below; a
deployment-only run or old unqualified `Smoke complete` log is not that proof.
F24's current-runtime feasibility and ordered implementation sub-items are in
[`TODO-F24-PLUGIN-PROVENANCE.md`](../dev-docs/TODO-F24-PLUGIN-PROVENANCE.md).
The separate corpus F22 startup sweep is removed; aborted corpus databases are
retained for explicit operator inspection, not automatically reclaimed by prefix.

### Artifact/target preflight (F24-A)

`./gradlew assemble` now creates a content-identified provider JAR and an adjacent
`build/distributions/doris-ducklake-0.0.1-plugin.identity.json` containing final
ZIP/JAR/file-set and compile-SDK hashes. API 24, the Java-17 floor and dependency
pins are unchanged. Identity follows effective class/resource/dependency bytes,
including dirty builds, not Git HEAD or the archive's mtime.

```bash
./gradlew assemble
compose/smoke.sh --preflight-only
compose/smoke.sh --preflight-only --plugin-zip '/path with spaces/chosen-plugin.zip'
```

Host Python **3.9+**, standard library only, is required for preflight. It reads
rendered compose configuration and Docker ownership metadata but does not start,
stop, install, seed or alter live config. Without `--plugin-zip` exactly one matching
ZIP is required; an explicit ZIP implies no build and needs its adjacent identity
JSON. The selected two-file input is frozen locally for the invocation and verified.
Preflight-only removes those private copies on exit; deployment additionally
retains its own recovery artifacts, as described below.

Foreign/wrongly mounted containers or volumes are rejected, not silently migrated.
In particular, a retained API-13 container using the old plugin mount cannot be
adopted just because its container name matches. Image overrides are reported from
the rendered configuration. Actual FE/volume names are resolved there, not guessed
independently by the driver.

F24-C adds the load-time SQL provider gate to the cooperating claim and
confirmed stop/install/start path. Historical validation still refers to its
original artifact bytes, not a newly stamped bundle. F24-D/E now pass stateful
failure/concurrency coverage and bounded real Doris A/B acceptance. See
[F24-A implementation/evidence](../dev-docs/VALIDATION-F24-A-2026-10-29.md).

### Serialized replacement and recovery (F24-B)

The normal path now claims the actual Docker-daemon/plugin-volume target, verifies
and stages the candidate, preserves the old complete plugin set, stops only the
owned FE and confirms it stopped, installs/verifies the exact file set, then
starts a fresh FE process with FE-local `SHOW FRONTENDS` readiness (without
waiting for a missing BE). Existing FE container,
metadata mounts and runtime config are retained; no metadata/storage/lake reset or
BE restart is used to reload the provider. BE startup cannot implicitly recreate FE.
The claim stays held through engine guards and the whole smoke invocation.

On a first/never-started container only, the pinned image may append one exact
duplicate of the already staged `priority_networks` value. That initialization is
recorded with before/after hashes; the initialized bytes are then frozen. Any
other config edit, repeat append or change on a previously started container fails.

Have **`python:3.12-slim` available locally** before deployment (explicitly pull it
ahead of time if needed). Helpers resolve it once to a local image ID, use
`--pull=never --network none`, and use only Python's standard library. There is no
`apk`, pip, opportunistic download, or host bind-mounted candidate. ZIPs/scripts/
backups stream through `docker cp`, preserving the remote Docker/Podman design.
The selected Doris images must also be local. An existing FE with a different
image or plugin mount is refused, not migrated. `DORIS_FE_STARTUP_TIMEOUT` defaults
to 180 seconds and must be a positive integer.

The printed `build/smoke-deployments/deploy-<UUID>/` directory retains candidate
ZIP/identity JSON, verified `previous.tar`/`previous.json` and incremental
`lifecycle.json`, plus completed prepare/install/verification reports. A deployment
failure keeps FE stopped when installation/startup is uncertain. No automatic
rollback or cleanup of those recovery artifacts occurs. These are lifecycle
records. The lifecycle file alone is not a loaded-provider or correctness receipt;
C's additional records are described below.

An occupied claim refuses even if its owner died. Do not steal it based on age.
Inspect its exact ID/owner label and lifecycle evidence, establish that the client,
smoke continuation and any installer are no longer active, then perform deliberate recovery. If
worker/FE stopping cannot be confirmed, the code deliberately retains the claim.
Restore only a verified complete backup while holding the same claim and with FE
confirmed stopped; a saved prior ZIP+identity receipt can use the normal path.
Legacy tar-only restoration needs reviewed offline recovery, not a fabricated
identity or `--down`. See [B evidence and recovery boundaries](../dev-docs/VALIDATION-F24-B-2026-10-29.md).

`--down` remains an explicit destructive teardown, now serialized and ownership-
checked too. It is not a plugin-replacement or claim-recovery command. The retained
legacy-volume mismatch still refuses; this work does not delete or migrate it.

### Loaded-provider gate and receipts (F24-C)

Both full and `--up-only` runs require exactly one load-time
`information_schema.extensions` row for `ducklake / CONNECTOR / EXTERNAL` with
`EXTENSION_VERSION` equal to the selected bundle's build ID. Null, missing, stale,
duplicate/conflicting or built-in identity fails before any lake allocation.
The intended fresh FE is queried directly by container ID; disk hashes alone are
never called loaded-code proof. Because API-24's inventory scan needs an available
BE, it runs after BE readiness and exact runtime source checks, not before BE startup.

The parent rechecks installed files and stable process/config identity, writes
`deployment-attested.json`, and only then enters the smoke continuation. The
receipt records exact artifacts/SDK, target, image/source/process identities,
SQL inventory and required gate outcomes. Stock BE remains an explicitly labelled
compatibility experiment and cannot skip FE/provider provenance.

`run-result.json` separately records invocation completion or failure after claim
cleanup. A failed later smoke/cleanup retires the observation to
`deployment-attested-before-error.json`, never a current passing receipt. Require
matching owners and a successful run result; pending or historical observations
are not successful runs. The earlier deployment observation leaves correctness
unclaimed; F25's separate full-run proof is required before command completion
can become a supported-scope correctness pass. Old run evidence is retained.

The earlier C-only read-only check was blocked by the retained BE's unavailability.
That stack remains untouched. Subsequent **F24-E** acceptance on new owned
infrastructure observes real loaded A and B, preserves cluster/catalog identity
and exact reads, and rejects an injected stale response before allocation. See
[C history](../dev-docs/VALIDATION-F24-C-2026-10-29.md) and
[D/E closeout evidence](../dev-docs/VALIDATION-F24-DE-2026-10-30.md).

Cluster-free isolation regressions (Python standard library only):

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s compose -p 'test_*.py'
bash -n compose/smoke.sh compose/smoke_run.sh
```

Run those from the repository root. The command-recording driver test uses a
temporary fake Docker executable; it never contacts the Docker daemon. It checks
all target routing and fail-closed allocation paths, not actual engine/GC behavior.
The volume worker also runs against isolated local files. An opt-in real Docker
test uses disposable UUID-owned resources and a **simulated**, not Doris, FE:

```bash
F24_DOCKER_TEST=1 PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s compose -p 'test_plugin_deploy_docker.py'
```

### Opt-in real A/B acceptance (F24-E)

This is separate from the simulated Docker test. It requires the exact local
qualified Linux/amd64 FE/full ASAN BE images, Python helper, PostgreSQL and MinIO
images, and the released **DuckDB 1.5.6** CLI. No image pulls/rebuilds, catalog
upgrades or capability opt-outs are introduced by the fixture.

From the repository root, build the ordinary A and an isolated resource-only B:

```bash
./gradlew assemble verifyJava17Runtime
F24_ACCEPTANCE_PROJECT_ROOT="$PWD" \
F24_ACCEPTANCE_BUILD_DIR="$PWD/build/f24-acceptance-B" \
  ./gradlew -I compose/acceptance-build-B.init.gradle assemble verifyJava17Runtime
python3 compose/plugin_acceptance.py \
  --bundle-a build/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/f24-acceptance-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /absolute/path/to/duckdb-1.5.6
```

The init script permits only a private subdirectory of `build/`, never the default
output. B changes only an acceptance resource/manifest identity, not existing
provider classes, SDK, API, release or bundled dependency bytes. It is not promoted
over the normal artifact.

The runner checks all new UUID-owned names absent, creates its own isolated
FE/BE/PostgreSQL/MinIO/volumes/networks and copies the unmodified public driver into
a private sibling-layout sandbox. It runs real `--up-only` A/B, three-row exact
reads against one native-file DuckLake catalog, verified rollback backup and
metadata/container preservation. A full-run negative phase verifies the actual
SQL row is B before injecting only an older A version into that response; it must
fail before any allocation attempt. It never swaps JARs under a running classloader.

Private `/tmp/opencode/f24-e-*/` evidence retains inputs, copied code, all receipts,
raw inventory at injection, source/image identities and exact read results. Only
its acknowledged disposable Docker resources are removed after evidence capture;
an uncertain claim instead leaves resources for deliberate recovery. Retained
default/qualification/R07/DuckBridge stacks are not deployment targets.

Rollback of a normal owned target uses a previously retained **verified ZIP plus
adjacent identity JSON**, after checking that target with `--preflight-only`, then
the same normal `--plugin-zip ... --up-only` stopped-install path. Select the
correct compose directory first: choosing an artifact does not choose/migrate a
target. No age-based claim stealing, metadata reset or automatic legacy tar-only
restoration is provided. The default legacy-volume refusal remains intentional.

F24's provenance/replacement scope is complete. Production/performance, F23
exposure and broader R07 qualification remain separate from this acceptance.

### Required smoke correctness (F25)

Full mode now resolves the selected substrate's owned PostgreSQL/MinIO containers,
common network and image identities rather than guessing global names. Secrets
reach helpers through their environment and are absent from correctness/deployment
JSON. Fresh database/prefix ownership and loaded-provider gates remain mandatory.

`smoke-assertions.json` records every required typed assertion. No soft `CHECK`,
generic historical `KNOWN-BLOCKED`, automatic write fallback or unconditional
LIVE-GREEN summary counts as success. Current-pin expected failures are empty;
any future reviewed entry must match exact runtime/case/observation and is still
non-green. The coverage is the existing supported API-24 INT32/STRING workload,
not unsafe narrow-int writes or arbitrary engine features.

Checks include exact pinned TPC-H counts/reference rows and filtered results,
nullable counts/rows, real position-delete rows, DDL metadata/order/schema, exact
native+Doris write/CTAS/default tuples, bucket arity/values/pruning and metadata/
storage maintenance effects. GC reads actual referenced fields in both engines;
metadata-only COUNT cannot conceal lost files. Object absence must be a structured
missing-key result, or the pinned code-free missing-object shape confirmed by a
successful empty exact-path listing, never an auth/network error. Failure stops follow-on work and
retains the run-owned fixtures for inspection.

Only complete required policy produces `smoke-correctness.json`. The supervisor
independently validates owner/build/run/runtime/target, required cases, predicates,
policy/ledger hashes and stable FE/BE processes. Only after cleanup/finalization
does `run-result.json` say `smoke_correctness_passed=true` and print final success.
A zero child exit with missing/incomplete checks fails. `--up-only` never claims
correctness. Later errors retire any current observation to before-error history.

The opt-in full S3 acceptance reuses the private F24 sandbox and exact default
classes (resource-only B), copies the sibling TPC-H generator without editing it,
then tests both a real complete run and one same-count corrupted tuple response:

```bash
python3 compose/smoke_acceptance.py \
  --bundle-a build/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/f24-acceptance-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /absolute/path/to/duckdb-1.5.6
```

Prepare B with the recipe above. The local `minio/mc:latest` helper is also
required; actual helper image IDs are resolved and passed immutably. All test
containers, databases, prefixes and networks belong to the new fixture, not a
retained stack. See [F25 verification and limits](../dev-docs/VALIDATION-F25-2026-10-30.md)
for the actual outcome; no pending or failed live run is relabelled as green.

### Prereqs
- The **FE image** `doris-fe:ducklake-62e3d81a-api24` must exist locally (see *Building the FE
  image* below), at source/SPI pin `62e3d81a42b`, API **24.0**. Connector checks
  and matching FE/BE isolated smoke passed. The historical API-9 FE cannot load
  today's plugin ZIP.
  Stamping the manifest does not upgrade the host FE.
- The Compose BE default is **`doris-be:ducklake-62e3d81a-api24-asan`**, built from
  the same exact pin as the FE, with the complete JNI-6 plugin tree. #68837 also
  changed Thrift fields after API 13. `smoke.sh` rejects both differing FE/BE versions
  and a matching-but-wrong source pair before seeding. `DORIS_EXPECTED_SOURCE` can
  explicitly select another exact qualification pin; it is not an automatic bypass.
- `DORIS_FE_IMAGE` / `DORIS_BE_IMAGE` override the source-qualified image defaults.
  The old `pr62767-local` / `master-local` tags are retained API-13 rollback images,
  not today's default. Do not install the API-24 ZIP into them.
- Default FE metadata, plugin, BE storage and logs use explicit
  `doris-ducklake-api24-62e3d81a-*` volume names. They do not overwrite the retained
  API-13 volumes. This qualification did not test an in-place API-13 metadata upgrade.
- Current BE is an **ASAN validation build**. Smoke correctness and restart are
  qualified; release performance and all JNI-plugin workloads are not.
- `DORIS_BE_IMAGE=apache/doris:be-4.1.4` explicitly selects the historical stock
  compatibility lane. Its September API-9 smoke does not qualify the API-24 FE.
- Docker **or** podman (see *Running under podman* ).

## Running under podman (x86_64 remote box)

The compose was authored for an **Apple-Silicon (arm64) + Docker** dev box. On an
**x86_64 + podman** host, three things differ:

1. **`docker`→`podman` shim** — `smoke.sh` calls `docker …`. Make a shim and prepend it:
   ```bash
   mkdir -p /tmp/dockershim
   printf '#!/usr/bin/env bash\nexec /opt/podman/bin/podman "$@"\n' > /tmp/dockershim/docker
   chmod +x /tmp/dockershim/docker
   export PATH="/tmp/dockershim:/opt/podman/bin:$PATH"
   export DOCKER_HOST=unix:///var/run/docker.sock
   ```
   `podman compose` works (it delegates to `docker-compose`).
2. **BE architecture** — the `doris-be` service pins `platform: ${DORIS_BE_PLATFORM:-linux/arm64}`.
   On x86_64 you **must** select amd64, else the arm64 BE runs under emulation and
   crashes on startup (`F … elf.cpp:76] The ELF '/proc/self/exe' is truncated`):
   ```bash
   export DORIS_BE_PLATFORM=linux/amd64
   podman pull --platform linux/amd64 apache/doris:be-4.1.4   # ensure the local tag is amd64
   ```
3. **FE image arch** — build the overlay on the amd64 box so `doris-fe:ducklake-62e3d81a-api24`
   is amd64 (the overlay's `FROM apache/doris:fe-4.1.4` resolves to the host arch).

Then: `DORIS_BE_PLATFORM=linux/amd64 ./smoke.sh`. (Full remote recipe also in the
`doris-compose-smoke-remote` project memory.)

## Building the FE image (`doris-fe:ducklake-62e3d81a-api24`)

The compose uses a locally-built pinned-master FE image overlaying `output/fe` onto a
stock Doris base, via the in-repo [`fe-overlay/Dockerfile`](./fe-overlay/Dockerfile)
(`FROM apache/doris:fe-4.1.4`, wipes + COPYs `output/fe/{bin,lib,conf,plugins,webroot}`).
(Originally a local-only file in the Doris checkout; now tracked here so it can't be lost.)

### Where the FE source lives (this Linux box)

The upstream Doris checkout and its worktrees live under `~/DEV/OSS/`:

- **`~/DEV/OSS/doris-ducklake-api24-62e3d81a`** — detached build worktree at
  `62e3d81a42b`. Current FE/BE output is built here, independently of the main
  checkout's staged Duckbridge handler changes.
- **`~/DEV/OSS/doris-ducklake-api13`** — retained API-13 build/rollback evidence
  at `dd456ab7a89`; do not replace its outputs or volumes.
- **`~/DEV/OSS/doris`** — the main checkout remains at `8fc58e929b2` with staged
  Duckbridge work. Do not switch or reset it to perform this re-vendor.
- **`~/DEV/OSS/doris-catalog-spi`** — a **linked git worktree** of that same repo,
  parked (detached HEAD) at the **pinned** connector-SPI commit we build the FE
  from (`0da96f1ad3e`, `branch-catalog-spi`). **Historical, not the current build
  source.** Its output must not be used for the API-24 plugin.

(Both are the same `.git`; `git worktree list` from either shows the pair. If a path
here ever looks wrong, `git worktree list` is the source of truth for where each
checkout actually is.)

### FE build artifacts & where they live

One `./build.sh --fe` yields **three** artifacts, each kept in a different place for a
different consumer. **None are committed to git** — they're all local-machine state, so
another machine / agent / CI won't have them until reproduced or copied:

| Artifact | Location | Consumer | Get it elsewhere |
|---|---|---|---|
| **Raw FE build** (`output/fe`; incl. `lib/doris-fe.jar`) | `~/DEV/OSS/doris-ducklake-api24-62e3d81a/output/fe` | staging source for the image | SHA-256 `7c5f86551790edbdbd8f56b38a5435d81391dbdec7a65624f58539d9284662a5`; matching-runtime smoke qualified |
| **Overlay image** `doris-fe:ducklake-62e3d81a-api24` | local Docker/podman image store (**not** in any registry) | default compose FE | image ID `sha256:f41c6a1710c66f0d0ab0334d5431af85c2002e8399de09ae10b8559bccb8c22a` |
| **SPI compile jars** (`fe-connector-spi`, `fe-thrift`, `1.2-SNAPSHOT`) | `~/.m2/repository/org/apache/doris/…` (installed via `mvn install -P flatten`) | the gradle plugin build (`build.gradle.kts` → `mavenLocal()`, `org.apache.doris` only) | re-run the flatten install (below) on that box, or copy the `~/.m2/.../1.2-SNAPSHOT` trees over |
| **Master BE image** `doris-be:ducklake-62e3d81a-api24-asan` | local Docker image store; exact pin `62e3d81a42b`, ASAN | default compose BE | image ID `sha256:ccca761cae5bc1e6122b4449c21d01117d74a095ce203cf9f4d27094ede8a2de`; complete JNI API-6 distribution |

Verify the runtime image actually carries the build you think it does:
`sha256sum ~/DEV/OSS/doris-ducklake-api24-62e3d81a/output/fe/lib/doris-fe.jar` should equal
`docker run --rm --entrypoint sha256sum doris-fe:ducklake-62e3d81a-api24 /opt/apache-doris/fe/lib/doris-fe.jar`.

# ⚠️ SOURCE = apache/doris `master` (the SPI is upstream now — the brikk fork is retired).
#   The connector SPI landed in apache master (#64304 + the fe/fe-connector tree), so build the FE
#   from the REAL apache/doris, not the old fork branch `branch-catalog-spi`.
#   Source/SPI pin (runtime-qualified 2026-10-28): 62e3d81a42b; plugin API 24.0.
#   SPI + Thrift rebuilt with JDK 17 + Thrift 0.24 in the pinned October 27 build-env.
#   Connector tests: Java 25 254 passed/1 skipped; Java 17 228 passed/1 skipped.
#   Matching FE/BE at this exact pin passed two isolated smokes and persisted-metadata FE restart.
#   BE is ASAN; no performance or all-JNI-plugin qualification is implied. See FE-PATCHES.
#   New capabilities and generic DML/distribution remain off; INSERT-only behavior is unchanged.
#   DuckLake retains read-default backfill and reusable file ranges. Own type mappings are unchanged.
#   The API-24 ZIP cannot load on the historical API-13 FE. Upgrade FE and BE together:
#   #68837 changed external Thrift fields; API-major equality alone is not wire compatibility.
#   branch-4.2 still has no connector SPI as of October 28; follow master/future 5.x.
#   The default is matching master BE; stock 4.1.4 is now an explicit experiment.
#   The new BE distribution carries JNI API 6 and its complete isolated plugins tree.
#   Master's <revision> is still 1.2-SNAPSHOT → ~/.m2 coordinates unchanged.
#   Builds PATCH-FREE since upstream #66135 removed both former FE-patch anchors — NO patch to apply.
#   The current pin is recorded in ../fe-patches/FE-PATCHES.md → "Re-vendor log" (keep both in sync).

```bash
# 1. Build the FE (JDK 17) from apache/doris master — PATCH-FREE (no patch step).
#    DORIS_THIRDPARTY can point at any doris thirdparty that has thrift+protoc installed.
git -C ~/DEV/OSS/doris worktree add --detach ~/DEV/OSS/doris-ducklake-api24-62e3d81a 62e3d81a42b2b74ee2a5a577969df62edee06b78
cd ~/DEV/OSS/doris-ducklake-api24-62e3d81a
JAVA_HOME=<jdk17> DORIS_THIRDPARTY=<doris thirdparty> DISABLE_BUILD_UI=ON ./build.sh --fe   # → output/fe
# Also reinstall the SPI compile jars our gradle build needs (see the artifacts table above):
cd fe && <mvn> install -P flatten -pl fe-connector/fe-connector-spi,fe-thrift -am \
  -Dmaven.test.skip=true -Dmaven.build.cache.enabled=false \
  -Ddoris.thrift.executable=<thrift-0.24-bin>

# 2. Image it with the TRACKED overlay Dockerfile (jvm/doris-ducklake/compose/fe-overlay/Dockerfile),
#    staging a minimal context so podman/docker isn't sent the multi-GB repo:
S=~/DEV/OSS/doris-ducklake-api24-62e3d81a/output/fe-image-stage; mkdir -p $S/output
cp -r ~/DEV/OSS/doris-ducklake-api24-62e3d81a/output/fe $S/output/fe
# run -f relative to THIS integrations repo root (adjust the path if your CWD differs):
podman build -f compose/fe-overlay/Dockerfile \
  -t doris-fe:ducklake-62e3d81a-api24 \
  --build-arg BASE_IMAGE=apache/doris:fe-4.1.4 --build-arg OUTPUT_PATH=./output  $S
```

Rebuild the image only when the **FE itself** changes. Plugin-only changes don't need
it — `smoke.sh` rebuilds the plugin zip and reinstalls it into the FE plugin volume.

For the matching runtime smoke:

```bash
DORIS_BE_PLATFORM=linux/amd64 ./smoke.sh --no-build
```

The source-qualified images above are locally built **linux/amd64** artifacts.
The exact pinned build environment, commands, SDK export and rollback are in the
[validation report](../dev-docs/VALIDATION-DORIS-API24-2026-10-28.md). Qualify a new
SDK without changing `~/.m2` using `./gradlew check assemble -PdorisRepository=<repo>`;
only `org.apache.doris` resolves there. The API-13 R07 compose remains explicitly
historical and needs its own matching old plugin, not the default API-24 ZIP.

The master BE overlay must copy both `output/be/lib/jni/spi` and
`output/be/plugins`; #66729 moved Java extensions into isolated plugin directories.

## Shutdown / cleanup

```bash
./smoke.sh --down                                                   # Doris FE+BE + volumes
docker compose -f ../../trino-ducklake/compose/docker-compose.yml down -v   # the substrate
podman ps -a | grep -E 'doris|ducklake'                             # confirm nothing lingering
```

`down -v` wipes the FE metadata + plugin volumes — exactly what you want between FE
or plugin changes so the fresh FE reloads everything.

## Troubleshooting (things we actually hit)

| Symptom | Cause / fix |
|---|---|
| `FE never came up` but FE log shows SQL | The FE *is* up; the health-check `SELECT 1` needs a **live BE** (Nereids assigns even constant queries to a backend). Check `SHOW BACKENDS\G` → `Alive`. |
| BE `Exited (0)`, `ErrMsg: NoRouteToHost`, FE `No backend available as scan node` | BE crashed. On x86_64 this is the **arm64-under-emulation** crash — set `DORIS_BE_PLATFORM=linux/amd64` + re-pull the amd64 BE. |
| FE exits 255; `fe.log` shows BDB JE `NoClassDefFoundError: …JVMSystemUtils` → `NullPointerException … CgroupV2Subsystem.getInstance … anyController is null` | The base FE image's bundled **JDK 17 is too old for this host's cgroup v2** (NPEs during container-resource detection). Native-Linux/cgroup-v2 boxes hit this; the old Docker-Desktop VM didn't. Fix: `fe.conf` `JAVA_OPTS_FOR_JDK_17` carries `-XX:-UseContainerSupport` (safe — heap is pinned `-Xmx2g`). |
| `Unknown catalog type: ducklake` on `CREATE CATALOG` | FE built from a pin older than #66135, or a stale image. Rebuild the FE from pin `0da96f1ad3e`+ (patch-free) and re-image. |
| `Current catalog does not support create table: dl` on `CREATE TABLE` | FE built from a pin older than #66135 (pre-`acceptedCreateTableEngineNames`). Rebuild from pin `0da96f1ad3e`+ and re-image. |
| Plugin load `failureCount>0` / `STAGE_API_VERSION` refusal | The plugin manifest is missing or its major differs from the FE. Current ZIPs declare `24.0`; rebuild the matching FE/BE pair rather than reinstalling the ZIP into an old FE. |
| `FE/BE source mismatch` or `Unexpected FE/BE source` before lake allocation | Rebuild/deploy both from `62e3d81a42b`; a matched old pair is also rejected. Replacing only the plugin does not fix wire skew. |
| INSERT: `Unsupported compress type UNKNOWN with parquet` | (fixed) sink must set a compression — we use `ZSTD`. |
| Read-back path doubled (`…/doris_w/s3%3A//…`) | (fixed) the BE returns an absolute path; the connector relativizes it against the table data dir. |

## Reference / gotchas

- **Plugin dir is `${DORIS_HOME}/plugins/connector/<name>/` (singular `connector`).**
  There's also a plural `plugins/connectors/` for the legacy Trino-JNI bridge — **don't**
  drop our zip there or `rm -rf` it. `smoke.sh` installs into a named volume mounted at
  `…/plugins/connector/ducklake` (podman-on-macOS bind mounts don't propagate reliably,
  hence the volume + `docker cp` helper).
- **FE↔BE wire-compat**: matching master is the default. The API-9 FE plus stock
  4.1.4 passed its historical smoke on September 19, but later master source changes
  query/sink Thrift fields. That old result is not a qualification of this pin.
  The local-shuffle shim remains only for explicit stock-4.1.4 experiments.
  Avoid binlog/CCR table options on this hybrid cluster. `fe.conf` here drops the
  heap to 2g and is mounted over the image's.
- **Network**: the substrate compose project is `trino-ducklake-dev`, so its bridge
  network is `trino-ducklake-dev_default` — the one-shot DuckDB helper containers join it
  by that name. Keep the two `name:` fields in sync if you rename projects.
- **The FE builds PATCH-FREE** since upstream #66135 (2026-07-27) removed both former
  FE-patch anchors (`SPI_READY_TYPES` whitelist + `pluginCatalogTypeToEngine`). The old
  `ducklake-fe.patch` is kept only as history — see [`../fe-patches/FE-PATCHES.md`](../fe-patches/FE-PATCHES.md).
  Build from pin `0da96f1ad3e`+, then re-image the FE.
