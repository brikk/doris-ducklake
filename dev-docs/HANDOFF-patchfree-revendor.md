# HANDOFF: patch-free re-vendor to branch-catalog-spi a0c10f0672b (2026-07-27)

Self-contained execution plan. Any agent/session can run this without chat context.
Delete this file when the re-vendor is done and logged.

## Why

Upstream #66135 (`fce5af4e041`) removed both anchors our FE patch existed for:
- `CatalogFactory.SPI_READY_TYPES` whitelist GONE — a registered `ConnectorProvider`
  claiming the type is sufficient ("installing a plugin is all it takes").
- `CreateTableInfo.pluginCatalogTypeToEngine` GONE — `ENGINE=` optional and
  connector-owned (`ConnectorProvider.acceptedCreateTableEngineNames()`, default empty;
  omitting ENGINE always legal). PARTITION BY / DISTRIBUTED BY validation is the
  connector's job (ours already does this in DuckLakeCreatePartitionMapper).
- `displayEngineName()` defaults to `getType()` → tables display engine `ducklake`.

So: build the branch **WITHOUT applying fe-patches/ducklake-fe.patch**. First patch-free
FE. The patch file stays in-repo as history until docs are updated.

## Target

- Repo: `~/DEV/OSS/doris-catalog-spi` (worktree currently at old pin `d56c8f356c3` with
  the patch applied — discard working-tree changes before checkout).
- New pin: `a0c10f0672b` subject
  "[chore](handoff) record the 2026-07-27c rebase onto e7b7f1d1359 (upstream #66004 storage facade)".
  SHAs churn on rebase — if GC'd, match the subject on origin/branch-catalog-spi.
- Every import our plugin uses was verified present on this tip; ConnectorMetadata's
  parent chain unchanged. Compile expected clean, but see Phase 2 contingency.

## Phase 1 — FE build + SPI jars (in ~/DEV/OSS/doris-catalog-spi)

```bash
cd ~/DEV/OSS/doris-catalog-spi
git checkout -- . && git checkout a0c10f0672b        # pristine tip, NO PATCH
export DORIS_HOME=$(pwd) JAVA_HOME=/home/jayson/.local/share/mise/installs/java/17.0.2 DISABLE_BUILD_UI=ON
./build.sh --fe                                       # ~2-25 min; success marker "Successfully build Doris"
cd fe && /home/jayson/.local/share/mise/installs/maven/latest/apache-maven-3.9.16/bin/mvn \
  install -P flatten -pl fe-connector/fe-connector-api,fe-connector/fe-connector-spi,fe-thrift \
  -DskipTests -Dcheckstyle.skip=true                  # refresh ~/.m2 SPI jars
```

## Phase 2 — plugin compile + tests (in ~/DEV/brikk/doris-ducklake)

```bash
cd ~/DEV/brikk/doris-ducklake && eval "$(mise env -s bash)"   # Java 25 for gradle
./gradlew check --refresh-dependencies                        # tests + detekt
./gradlew assemble                                            # plugin zip
```

Contingency: if compile breaks on renamed/deleted SPI surface, fix minimally (imports /
signatures only), keep behavior identical, note every change for the commit message.
Known-good facts: 5-arg ConnectorColumn ctor exists; ConnectorTableOps/SchemaOps/
PushdownOps/StatisticsOps/WriteOps all exist; new ConnectorTableDdlOps may have appeared
in the hierarchy — harmless unless a method we override moved signatures.

## Phase 3 — overlay image (docker, NOT podman; podman cluster belongs to another project)

```bash
S=/tmp/feimg; rm -rf $S; mkdir -p $S/output && cp -r ~/DEV/OSS/doris-catalog-spi/output/fe $S/output/fe
cd ~/DEV/brikk/doris-ducklake
docker build -f compose/fe-overlay/Dockerfile -t doris-fe:pr62767-local \
  --build-arg BASE_IMAGE=apache/doris:fe-4.1.0 --build-arg OUTPUT_PATH=./output $S
# verify: sha256sum ~/DEV/OSS/doris-catalog-spi/output/fe/lib/doris-fe.jar ==
#   docker run --rm --entrypoint sha256sum doris-fe:pr62767-local /opt/apache-doris/fe/lib/doris-fe.jar
rm -rf $S
```
(Image name is legacy; keep it — compose expects it.)

## Phase 4 — smoke (the real validation; FE image changed => MUST wipe metadata)

```bash
cd ~/DEV/brikk/doris-ducklake/compose && eval "$(mise env -s bash)"
./smoke.sh --down && ./smoke.sh          # full run, log to a file, poll in background
```

Expected GREEN (as on the d56c8 baseline): reads, §8b row counts + EXPLAIN, W1 DDL
(CREATE/DROP DATABASE+TABLE incl. bucket-partitioned), W2/W2c/W3 writes, §12b DEFAULT
backfill, §13 GC. Expected KNOWN-BLOCKED (pre-existing, do NOT chase): §8b-count bare
COUNT(nullable col) non-determinism (upstream colUniqueId=-1); Step-7 delete read
`[CORRUPTION]Not nullable column has null values`.

Patch-free-specific checks (the whole point — verify in the smoke log / manually):
1. CREATE CATALOG type=ducklake succeeds with the UNPATCHED FE (provider claims type).
2. W1's `CREATE TABLE ... PARTITION BY LIST (bucket(4, name)) ()` (no ENGINE=) succeeds
   — TRANSFORM-style partition parse must work on the generic path now. If it FAILS:
   that's the one semantic our old ENGINE_ICEBERG padding provided; capture the exact
   error, this becomes a decision point (minimal patch #2 revival vs upstream ask).
3. INSERT/CTAS still route (TIcebergTableSink; BE dispatch on "iceberg" format string is
   a BE matter, unchanged).
4. S3/MinIO reads still work (storage-facade reconcile e29884df07f didn't break our
   s3.*/AWS_* forwarding).
5. Nice-to-have: `SHOW TABLE STATUS` / information_schema shows engine `ducklake`.

Manual fallbacks if smoke script fails on infra (FE stuck at UNKNOWN = stale meta →
re-run --down; first UDF-less FE boot takes ~90s).

## Phase 5 — docs + commit (in ~/DEV/brikk/doris-ducklake)

1. `fe-patches/FE-PATCHES.md`: new re-vendor log entry at top — 2026-07-27, pin
   a0c10f0672b, PATCH-FREE (both patches obsolete via #66135; file kept as history).
   Update the warning box + "Apply + rebuild" section: no patch application step.
2. `compose/README.md`: 3 pin references -> a0c10f0672b; remove patch-apply steps.
3. `dev-docs/TODO-read.md` "Upstream coordination": mark SPI_READY_TYPES ask DONE
   (upstream #66135) and the pluginCatalogTypeToEngine note resolved. Option-B BE
   dispatch ask REMAINS open (BE still keys on "iceberg").
4. `dev-docs/ducklake-doris-friction.md`: append FIXED-UPSTREAM notes to the
   2026-05-19 "SPI_READY_TYPES whitelist" and 2026-06-10 "pluginCatalogTypeToEngine"
   entries (one line each, reference #66135).
5. Delete this handoff file. Commit all + any Phase-2 source fixes; push.

## Session state (for a fresh session)

- doris-ducklake main @ 094cefb, clean, all pushed. Plugin already bundles the iceberg
  SDK + sets collect_column_stats (required since #65893/#65782 — keep).
- Old pin d56c8f356c3; FE-PATCHES.md documents it.
- compose cluster may be running (docker; containers doris-ducklake-fe/be) — smoke
  --down handles it. NEVER touch podman containers.
- COUNT(col) + delete-nullability blockers: open upstream, tracked in TODO-read.
