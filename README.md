# DuckLake connector for Apache Doris

**Pre-alpha. Do not use in production.** This plugin lets Doris query
[DuckLake](https://ducklake.select/) tables backed by **PostgreSQL metadata and
Parquet files**. Basic INSERT, CTAS and schema/table creation are also available.

The connector plans scans in the Doris frontend (FE); the backend (BE) reads
Parquet directly. It does not run DuckDB queries in the read path and cannot read
DuckDB-native `.db` data files.

## Required runtime

**Targets a future compatible Doris 5.x release. Doris 4.x is unsupported.** Until
that release is available, the connector requires the exact pinned master FE/BE
builds listed below.

- **Doris source:** `4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146`, with matching FE and BE.
- **Connector API:** 27.0; filesystem API 7.0; complete JNI API 6.0 distribution.
- **Default catalog library:** 0.16.0; native test JDBC: 2.0.0.1.
  Live interoperability fixtures also exercise DuckDB 1.5.6 / DuckLake 1.0.
- **Plugin runtime:** Java 17. Building the plugin requires JDK 25.

Stock Doris releases, including the checked 4.2 release candidate, lack the
connector SPI required by this plugin. An API number or a changed manifest alone
does not establish runtime compatibility. The qualified BE is an **ASAN validation
build**, not a performance-qualified release build. The FE needs no local patch.

## Build and install

Install the matching Doris SDK first; see [runtime setup](dev-docs/DORIS-RUNTIME.md).
From this repository's root:

```sh
./gradlew assemble
```

The bundle is `build/distributions/doris-ducklake-0.0.1-plugin.zip`, with an adjacent
`.identity.json` receipt. Keep them together. Unpack the complete bundle into
`${DORIS_HOME}/plugins/connector/ducklake/` **while FE is stopped**, preserving its
`lib/` layout, then start FE. Do not replace JARs beneath a running classloader.
`REFRESH CATALOG` refreshes metadata; it does not reload plugin code.

For the local development cluster, use the verified install/start path:

```sh
DORIS_BE_PLATFORM=linux/amd64 compose/smoke.sh --up-only
```

This requires the locally built matching images and the sibling PostgreSQL/MinIO
substrate. See [the development stack guide](dev-docs/COMPOSE.md) for prerequisites,
loaded-build verification, recovery and teardown.

## Connect to a lake

The PostgreSQL database must contain an initialized DuckLake catalog. FE needs
access to PostgreSQL; BE needs access to its data files.

For new lakes created by DuckDB 2.0, explicitly select `DUCKLAKE_VERSION '1.0'`
and `AUTOMATIC_MIGRATION FALSE` in the native ATTACH options. This connector
upgrade does not migrate existing lakes or enable DuckLake 1.1 writes.

S3/MinIO example:

```sql
CREATE CATALOG dl PROPERTIES (
    'type'              = 'ducklake',
    'metadata.url'      = 'jdbc:postgresql://<host>:5432/ducklake',
    'metadata.user'     = 'ducklake',
    'metadata.password' = '<password>',
    'storage.warehouse' = 's3://<bucket>/<prefix>/',
    's3.endpoint'       = 'http://<host>:9000',
    's3.region'         = 'us-east-1',
    's3.access_key'     = '<key>',
    's3.secret_key'     = '<secret>',
    'use_path_style'    = 'true'
);

SHOW DATABASES FROM dl;
SELECT * FROM dl.sales.orders LIMIT 10;
REFRESH CATALOG dl;  -- refresh after another engine changes the lake
```

`metadata.url`, `metadata.user` and `storage.warehouse` are required.
`metadata.password` is optional for databases configured for trust authentication.
Storage credentials are forwarded to the native reader; do not commit real secrets.

Metadata pools can be tuned with `catalog.max-connections`,
`catalog.min-idle-connections`, `catalog.connection-idle-timeout`,
`catalog.connection-max-lifetime` and `catalog.connection-timeout`. Defaults are
unchanged; the complete `metadata.url` query string is passed to JDBC, including
`prepareThreshold=0`. See [pool configuration and PgBouncer](dev-docs/PGBOUNCER.md).
The normal 0.16.0 dependency includes the transaction-pooling read-session fix;
the documented prepared-statement requirement still applies.

## What works

- Parquet scans, column projection, file-statistics pruning and bucket pruning.
- Identity, year/month/day/hour and `bucket(N, column)` partitions. DuckLake
  bucketing is not Doris `DISTRIBUTED BY` hashing.
- Latest-snapshot file-based position deletes.
- Snapshot pinning and scalar time travel by snapshot ID or timestamp, subject
  to the historical-read restrictions below.
- Current native STRUCT/LIST/MAP reads using the shared catalog parser, including
  quoted field names and nested DECIMAL; empty/case-colliding field names refuse.
- Scalar added-column DEFAULT backfill for the tested types.
- Basic scalar INSERT/CTAS, CREATE/DROP SCHEMA and CREATE/DROP TABLE, including
  partitioned writes. Doris-written files are checked through DuckDB read-back.
- Snapshot expiry, scheduled-file cleanup and managed-orphan cleanup, with the
  administrative scope restrictions below.

These are bounded capabilities, not a claim that all combinations or the full
upstream corpus pass. See [type mappings](dev-docs/TYPES.md) and
[catalog adoption checks](dev-docs/VALIDATION-catalog-0.16.0.md).

## Important limits

- **Small/inline data:** DuckLake can store small writes and deletes in metadata
  instead of Parquet. Inline data is rejected by default; inline deletes are
  unsupported. The `experimental.inlined.reads=true` data path is shared-filesystem,
  development-only—not a distributed solution.
- **Historical reads:** historical scans with delete files are conservatively
  refused until per-position snapshot filtering is available. Latest deletes and
  delete-free scalar history remain available. Partial-file compaction history
  and nested history also refuse.
- **Imported files:** scalar name mapping has limits, including conflicting
  embedded field IDs and DROP/re-ADD name reuse. Nested mapped files refuse.
- **Nested types:** nested evolution/defaults and final-1.1 nested scans are not
  qualified. Nested mapped files and historical scans remain refused.
- **Types and writes:** use INT/BIGINT instead of TINYINT/SMALLINT writes; CTAS
  literals can infer narrow integers. Nanosecond timestamps map to microseconds.
  Unsigned and other degraded mappings are not a general write guarantee.
- **Temporal domain:** known infinities/out-of-range date or timestamp bounds and
  unsafe bounded inline temporal values refuse before query execution. Unknown
  file statistics are not proof of representability; see the
  [read-safety limits](dev-docs/VALIDATION-DORIS-READ-SAFETY-2026-11-04.md).
- **Not available:** DELETE/UPDATE/MERGE, INSERT OVERWRITE, ALTER-column DDL,
  sorted writes, compaction procedures and DuckLake views. General DuckLake 1.1
  support is not enabled; the developer candidate is separate and default-off.
- **Correctness gaps remain:** pruning/type conversions, concurrent rewrites and
  cross-engine write metadata still have open findings. The
  [active findings register](dev-docs/REVIEW-FINDINGS.md) records their boundaries.

If a write reports an **unknown COMMIT outcome** or a **post-commit finalization
failure**, do not blindly retry or delete its output. The write may already be
published; reconcile catalog registrations using a fresh connection first.

`enable.mapping.timestamp_tz` defaults to `false`: `timestamptz` is exposed as
UTC-valued, zone-naive `DATETIMEV2(6)`. Setting it to `true` requests zone-aware
`TIMESTAMPTZ`; this is an opt-in mapping, not blanket timezone qualification.

## Maintenance requires lake-wide administrative control

Doris exposes these as `ALTER TABLE <table> EXECUTE ...`, but
**`expire_snapshots` and `cleanup_old_files` operate catalog-wide**, ignoring the
named table for scope. `remove_orphan_files` defaults to the table directory;
`scope='schema'` or `'catalog'` widens it.

The connector does **not** enforce authorization matching those wider scopes.
Do not grant these operations to untrusted table-scoped users. Shared storage
credentials, concurrent writers and retained readers need administrative coordination.

```sql
-- Preview only; inspect scope and selected objects before any destructive call.
ALTER TABLE dl.sales.orders EXECUTE expire_snapshots(
    retention_threshold = '30d', dry_run = 'true');
ALTER TABLE dl.sales.orders EXECUTE remove_orphan_files(dry_run = 'true');
```

`maintenance.min-retention` defaults to `7d` and floors age-based thresholds.
Explicit `snapshot_ids` bypass that floor. Expiry schedules files; cleanup
physically deletes them. Orphan cleanup selects only old, unreferenced managed
residue, not arbitrary Parquet files. A grace period is not a concurrency lock.
Maintenance currently uses the S3 client and has a narrower credential surface
than native reads, including a session-token limitation.

Catalog 0.15.x expiry commits snapshot deletion before paged metadata cleanup. If
cleanup then fails, the procedure explicitly reports **expiry committed**: do not
retry the expiry. Resume with the catalog API `cleanUpExpiredObjects`; this SDK
operation is not yet exposed as a Doris SQL procedure. `cleanup_old_files` only
deletes already-scheduled objects and does not resume metadata cleanup.

## Development

Build/test instructions, candidate-lane details and the open backlog live in
[dev-docs/](dev-docs/README.md). Superseded plans, completed checklists and dated
evidence live in [dev-docs/archive/](dev-docs/archive/README.md).
