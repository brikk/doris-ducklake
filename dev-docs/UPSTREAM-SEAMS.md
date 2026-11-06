# Upstream seams

Where the connector depends on Doris engine behaviour it does not control, and what
would have to change upstream to close a gap. Finding IDs refer to [FINDINGS.md](FINDINGS.md).

## Reader

- **No per-position snapshot filter** (F08). The Iceberg reader applies every position in
  a delete file. DuckLake tags deletes per snapshot; historical reads need the BE to
  honour that tag. Until then historical scans over delete files are refused.
- **No hidden-column filter** for partially compacted files. Rows carry
  `_ducklake_internal_snapshot_id`; the BE has no hook to drop rows newer than the pin.
- **No statement-wide metadata lease** (F07). A snapshot id pins logical state, but
  flush/compaction can rewrite files between the FE's metadata calls. Each SPI call opens
  its own catalog session; `ConnectorStatementScope` could carry one view if the engine
  threaded it through planning.
- **Field identity is table-wide** (F11/F12). The schema dictionary is one per scan, but
  `add_files` tables carry a name mapping per file. The scan-node `name_mapping` union
  cannot express every per-file mapping, so those shapes are refused for nested columns.
- **Cast erasure before pushdown** (F26). The FE can hand the connector a predicate whose
  cast was folded away; pruning on it can be unsafe even though the BE re-applies the
  residual.
- **`columns_from_path` cannot carry NULL.** Hive-layout partition values are
  constant-filled as strings; `__HIVE_DEFAULT_PARTITION__` is refused.

## Transport

The connector only emits file ranges. Rows that live in the catalog (inlined data and
deletes, DuckLake's own metadata tables) have no FE→BE channel other than writing a file
the BE can open. The dev-only inlined path does exactly that on a shared filesystem; a
real deployment needs an object-store write or an SPI payload seam.

`table_format_type = "iceberg"` is a dispatch alias. A `"ducklake"` discriminator needs
the BE's `file_scanner.cpp` to accept it (N01). Credentials are emitted both as `s3.*`
and as the BE's `AWS_*` aliases because the FE-side normaliser does not run on the
Parquet reader path.

## Writes

- `TIcebergCommitData` carries no Parquet footer length; the catalog stores NULL.
- The BE's Iceberg writer binds columns by position, so the FE must supply the full
  schema in table order (`requiresFullSchemaWriteOrder`).
- Narrow-int writes (O05): Iceberg's Arrow target and Doris's source serde can disagree
  for TINYINT/SMALLINT. Use INT/BIGINT.
- Column statistics are only collected when the sink sets `collect_column_stats`; without
  them `COUNT(nullable_col)` returns 0.

## Deployment

- Plugin root is `${DORIS_HOME}/plugins/connector/<name>` (singular). The plural
  `plugins/connectors` belongs to a different mechanism; never clear it.
- FE readiness: `SHOW FRONTENDS`. `SELECT 1` needs a live BE and can deadlock a BE whose
  startup waits on FE health.
- JVM flags come from `fe.conf`, not container environment.
- Doris injects engine-level properties (`enable.mapping.varbinary`, …) into every
  `CREATE CATALOG`, so the connector validates required keys without rejecting unknown ones.
- `connector_plugin_root` is not discoverable from SQL; an ergonomics ask, not a blocker.
