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
do not run concurrent smoke/deployment operations against them. F24 (plugin
reload/provenance) and F25 (nonfatal assertions) remain separate open items, so
`Smoke complete` alone is not proof of end-to-end correctness.

Cluster-free isolation regressions (Python standard library only):

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s compose -p 'test_smoke_*.py'
bash -n compose/smoke.sh
```

Run those from the repository root. The command-recording driver test uses a
temporary fake Docker executable; it never contacts the Docker daemon. It checks
all target routing and fail-closed allocation paths, not actual engine/GC behavior.

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
