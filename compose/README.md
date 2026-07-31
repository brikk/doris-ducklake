# DuckLake-on-Doris — Live FE+BE smoke

The slow, real-cluster counterpart to `:doris-ducklake:test`. `smoke.sh` brings up
a full Doris cluster (FE hosting our plugin + a stock BE) against the DuckLake
substrate (Postgres + MinIO + seeded TPC-H) and exercises the plugin end-to-end:

- **read** — `CREATE CATALOG`, `SHOW/DESC`, `SELECT * FROM dl.tpch.orders`
- **Step 7** — position-delete plumbing (FE-side; BE-side gated on a known
  parquet-nullability gap)
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
cd jvm/doris-ducklake/compose
./smoke.sh                 # build plugin → up substrate+cluster → drive read/delete/INSERT
./smoke.sh --no-build      # skip the gradle plugin rebuild (zip already current)
./smoke.sh --down          # tear down the Doris cluster (substrate left running)
```

One-time `~20 min` cluster build (FE image, image pulls); `~2–3 min` per re-run.

### Prereqs
- The **FE image** `doris-fe:pr62767-local` must exist locally (see *Building the FE
  image* below). Built from a P-series FE (`branch-catalog-spi`) pinned at
  `0da96f1ad3e` (2026-07-29) — builds **PATCH-FREE** since upstream #66135 (a registered
  `ConnectorProvider` claiming `type=ducklake` is enough; no `SPI_READY_TYPES` whitelist).
  Since #66211 the plugin jar must carry the `Doris-Connector-Plugin-Api-Version` manifest
  attribute (stamped by our `jar` task) or the FE refuses to load it.
- The stock BE image `apache/doris:be-4.1.0` (pulled automatically).
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
   podman pull --platform linux/amd64 apache/doris:be-4.1.0   # ensure the local tag is amd64
   ```
3. **FE image arch** — build the overlay on the amd64 box so `doris-fe:pr62767-local`
   is amd64 (the overlay's `FROM apache/doris:fe-4.1.0` resolves to the host arch).

Then: `DORIS_BE_PLATFORM=linux/amd64 ./smoke.sh`. (Full remote recipe also in the
`doris-compose-smoke-remote` project memory.)

## Building the FE image (`doris-fe:pr62767-local`)

The compose uses a locally-built FE image overlaying our P-series `output/fe` onto a
stock Doris base, via the in-repo [`fe-overlay/Dockerfile`](./fe-overlay/Dockerfile)
(`FROM apache/doris:fe-4.1.0`, wipes + COPYs `output/fe/{bin,lib,conf,plugins,webroot}`).
(Originally a local-only file in the Doris checkout; now tracked here so it can't be lost.)

### Where the FE source lives (this Linux box)

The upstream Doris checkout and its worktrees live under `~/DEV/OSS/`:

- **`~/DEV/OSS/doris`** — the main clone (currently on `master`). The general
  Doris repo; look here for BE source, base build scripts, upstream history.
- **`~/DEV/OSS/doris-catalog-spi`** — a **linked git worktree** of that same repo,
  parked (detached HEAD) at the **pinned** connector-SPI commit we build the FE
  from (`0da96f1ad3e`, `branch-catalog-spi`). **This is the one we build the plugin
  FE against.** Its `output/fe/` is the FE build below.

(Both are the same `.git`; `git worktree list` from either shows the pair. If a path
here ever looks wrong, `git worktree list` is the source of truth for where each
checkout actually is.)

### FE build artifacts & where they live

One `./build.sh --fe` yields **three** artifacts, each kept in a different place for a
different consumer. **None are committed to git** — they're all local-machine state, so
another machine / agent / CI won't have them until reproduced or copied:

| Artifact | Location | Consumer | Get it elsewhere |
|---|---|---|---|
| **Raw FE build** (`output/fe`, ~2 GB; incl. `lib/doris-fe.jar`) | `~/DEV/OSS/doris-catalog-spi/output/fe` | staging source for the image | regenerate with `build.sh --fe` (patch-free; not shipped — too big/machine-specific) |
| **Overlay image** `doris-fe:pr62767-local` | local Docker/podman image store (**not** in any registry) | the compose FE (runtime) — `docker-compose.yml` | `docker save doris-fe:pr62767-local \| ...` or push to a registry the other side can pull |
| **SPI compile jars** (`fe-connector-api`, `fe-connector-spi`, `fe-thrift`, `1.2-SNAPSHOT`) | `~/.m2/repository/org/apache/doris/…` (installed via `mvn install -P flatten`) | the gradle plugin build (`build.gradle.kts` → `mavenLocal()`, `org.apache.doris` only) | re-run the flatten install (below) on that box, or copy the `~/.m2/.../1.2-SNAPSHOT` trees over |

Verify the runtime image actually carries the build you think it does:
`sha256sum ~/DEV/OSS/doris/output/fe/lib/doris-fe.jar` should equal
`docker run --rm --entrypoint sha256sum doris-fe:pr62767-local /opt/apache-doris/fe/lib/doris-fe.jar`.

# ⚠️ SOURCE = apache/doris `master` (the SPI is upstream now — the brikk fork is retired).
#   The connector SPI landed in apache master (#64304 + the fe/fe-connector tree), so build the FE
#   from the REAL apache/doris, not the old fork branch `branch-catalog-spi`.
#   Pin (2026-07-31): ded91fb9fb3  ([fix](ci) Skip usage-limited Codex review accounts (#66319))
#   Master's <revision> is still 1.2-SNAPSHOT → ~/.m2 coordinates unchanged.
#   Builds PATCH-FREE since upstream #66135 removed both former FE-patch anchors — NO patch to apply.
#   The current pin is recorded in ../fe-patches/FE-PATCHES.md → "Re-vendor log" (keep both in sync).

```bash
# 1. Build the FE (JDK 17) from apache/doris master — PATCH-FREE (no patch step).
#    DORIS_THIRDPARTY can point at any doris thirdparty that has thrift+protoc installed.
cd ~/DEV/OSS/doris && git checkout master && git merge --ff-only origin/master   # apache master tip
JAVA_HOME=<jdk17> DORIS_THIRDPARTY=<doris thirdparty> DISABLE_BUILD_UI=ON ./build.sh --fe   # → output/fe
# Also reinstall the SPI compile jars our gradle build needs (see the artifacts table above):
cd fe && <mvn> install -P flatten -pl fe-connector/fe-connector-api,fe-connector/fe-connector-spi,fe-thrift -am -DskipTests

# 2. Image it with the TRACKED overlay Dockerfile (jvm/doris-ducklake/compose/fe-overlay/Dockerfile),
#    staging a minimal context so podman/docker isn't sent the multi-GB repo:
S=/tmp/feimg; rm -rf $S; mkdir -p $S/output
cp -r ~/DEV/OSS/doris/output/fe $S/output/fe
# run -f relative to THIS integrations repo root (adjust the path if your CWD differs):
podman build -f jvm/doris-ducklake/compose/fe-overlay/Dockerfile \
  -t doris-fe:pr62767-local \
  --build-arg BASE_IMAGE=apache/doris:fe-4.1.0 --build-arg OUTPUT_PATH=./output  $S
```

Rebuild the image only when the **FE itself** changes. Plugin-only changes don't need
it — `smoke.sh` rebuilds the plugin zip and reinstalls it into the FE plugin volume.

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
| Plugin load `failureCount>0` / `STAGE_API_VERSION` refusal | FE built at/after #66211 but the plugin jar lacks the `Doris-Connector-Plugin-Api-Version` manifest attribute. Our `jar` task stamps `1.0`; rebuild the plugin zip. |
| INSERT: `Unsupported compress type UNKNOWN with parquet` | (fixed) sink must set a compression — we use `ZSTD`. |
| Read-back path doubled (`…/doris_w/s3%3A//…`) | (fixed) the BE returns an absolute path; the connector relativizes it against the table data dir. |

## Reference / gotchas

- **Plugin dir is `${DORIS_HOME}/plugins/connector/<name>/` (singular `connector`).**
  There's also a plural `plugins/connectors/` for the legacy Trino-JNI bridge — **don't**
  drop our zip there or `rm -rf` it. `smoke.sh` installs into a named volume mounted at
  `…/plugins/connector/ducklake` (podman-on-macOS bind mounts don't propagate reliably,
  hence the volume + `docker cp` helper).
- **FE↔BE wire-compat**: the P-series FE pairs cleanly with stock `apache/doris:be-4.1.0`
  for our scope (Thrift drops unknown fields). Avoid binlog/CCR table options on this
  hybrid cluster. `fe.conf` here drops the heap to 2g and is mounted over the image's.
- **Network**: the substrate compose project is `trino-ducklake-dev`, so its bridge
  network is `trino-ducklake-dev_default` — the one-shot DuckDB helper containers join it
  by that name. Keep the two `name:` fields in sync if you rename projects.
- **The FE builds PATCH-FREE** since upstream #66135 (2026-07-27) removed both former
  FE-patch anchors (`SPI_READY_TYPES` whitelist + `pluginCatalogTypeToEngine`). The old
  `ducklake-fe.patch` is kept only as history — see [`../fe-patches/FE-PATCHES.md`](../fe-patches/FE-PATCHES.md).
  Build from pin `0da96f1ad3e`+, then re-image the FE.
