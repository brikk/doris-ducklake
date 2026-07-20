# DuckLake-on-Doris — RESEARCH TODO

Open questions, feasibility studies, and "remember this" ideas — **not scheduled
work**. Implementation tracks live in the sibling todos:
- 📖 [`TODO-read.md`](./TODO-read.md) — READ path
- ✍️ [`TODO-write.md`](./TODO-write.md) — WRITE path

Production-blocker coordination with the Doris team (SPI_READY_TYPES removal,
BE delete-file nullability, etc.) stays in the READ todo's *Upstream coordination*
section — those are tracked asks, not open research.

---

## Backlog

- [ ] **Doris as a DuckLake catalog backend (speculative).** DuckLake's catalog
  metadata today lives in Postgres / SQLite / DuckDB. Could **Doris itself** also
  serve as that catalog backend — host the `ducklake_*` metadata tables — so a
  Doris-centric deployment needs no separate OLTP store? Two things to settle
  before this is more than an idea:
  1. **Migration dialect.** DuckLake's catalog schema + migrations are written in
     (largely) ANSI/standard SQL for those engines. Doris's DDL/DML is MySQL-ish
     with OLAP semantics, **not ANSI** — the migration scripts won't run as-is, so
     it'd need a Doris-dialect port of the DuckLake catalog DDL/migrations.
  2. **Semantics validity for the role.** Doris is a columnar OLAP engine; a
     catalog backend needs OLTP-shaped guarantees — atomic single-row
     INSERT/UPDATE/DELETE on small metadata tables, unique/primary-key enforcement,
     and serializable/consistent commit semantics for snapshot creation under
     concurrency. Open question whether Doris's transaction + UPDATE/DELETE +
     constraint model is strong enough to be a *valid* catalog store (vs. merely
     syntactically loadable).
  Captured 2026-06-08 as a "remember this" idea — feasibility research only, well
  after the read-path v1 lands.

- [ ] **DuckLake system / metadata tables (`$snapshots` / `$history` / `$files`…) (speculative).**
  DuckLake carries rich snapshot + file metadata (`ducklake_snapshot`,
  `ducklake_snapshot_changes`, `ducklake_data_file`, …) that maps naturally onto
  Iceberg-style system tables — `db.table$snapshots`, `$history`, `$files`,
  `$manifests`-equivalents — for snapshot discovery, time-travel target lookup,
  and file-level introspection. The catalog layer already surfaces most of it
  (`getSnapshot`, `listSnapshots`, `listSnapshotChanges`, `getDataFiles`), so the
  data is on hand; the missing piece is presenting it as a **queryable** table.

  *Reference pattern (upstream, current).* Iceberg exposes its system tables via a
  dedicated BE JNI scanner — `IcebergSysTableJniScanner`
  (`be-java-extensions/iceberg-metadata-scanner/…`) — plus SPI hooks on
  `ConnectorScanPlanProvider` (`classifyColumn`, `supportsSystemTableTimeTravel`),
  with the FE planning a metadata scan (`doPlanSystemTableScan`) that projects the
  requested columns. See `branch-catalog-spi` commits `61a8b380` (add sys-table
  projection) and `5f009592` (fix the positional JNI read + order-preserving
  projection); design detail + the gotcha are in the friction log
  (2026-07-19 entry).

  *Blocker to reuse.* The sys-table JNI path is **hardwired to iceberg** — a
  per-format be-java-extension module + a hardcoded `table_format_type` dispatch in
  `file_scanner.cpp` (the same closed dispatch documented in the 2026-07-06
  friction entry "No SPI path for a connector to hand FE-computed rows to the BE").
  There is no generic `plugin_driven` case, so a DuckLake plugin can't reach it
  without a BE patch + its own scanner. Two routes if/when we want this:
  1. Land the "smallest fix" from that friction entry — a generic
     `table_format_type == "plugin_driven"` `FORMAT_JNI` dispatch — then ship a
     DuckLake metadata JNI scanner. Also unlocks arbitrary FE-computed rows
     (subsumes the inlined-data temp-Parquet crutch).
  2. FE-synthesize the metadata rows into a temp Parquet and emit a normal
     `FILE_SCAN` (the same shared-storage crutch we use for inlined data — same
     dev/compose-only limitation).
  Contract to honor if we build the JNI scanner: the FE must project columns in
  **scan-slot order** and the BE reads rows **positionally** (see friction
  2026-07-19 for why, and a proposed field-id-bound alternative). Captured
  2026-07-19 as a "remember this" — well after read-path v1.
