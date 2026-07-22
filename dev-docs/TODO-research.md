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

## Upstream branch watch (surveyed 2026-07-22)

Independent apache/doris branches we scanned for relevance beyond our tracked
`branch-catalog-spi` baseline. None require action now; recorded so we don't
re-derive "what is this branch" next time. (SHAs churn — match by subject.)

- 🟡 **`data_lake_reader_refactoring` — WATCH (could move our BE read-path blockers).**
  A BE-internals overhaul of the data-lake readers (25 commits off ~#62k master):
  introduces `TableFormatReader` with **auto column-filling**, applies an NVI
  (non-virtual-interface) template to `init_reader`/`get_next_block`, unifies the
  standalone Parquet/ORC readers, moves column-fill `GenericReader → TableFormatReader`,
  **removes `_fill_columns_from_path`**, refactors **count-agg pushdown**, "load query
  decoupling", and "unify FE default value". This reshapes the exact BE machinery our
  read path rides — `columns_from_path` hive-partition fill, count pushdown, and the
  column-DEFAULT backfill fill path — and could plausibly touch our **position-delete
  nullability blocker**. Staging/WIP branch, not merged. If it lands in our baseline,
  re-verify: hive-layout partition fill, COUNT pushdown, DEFAULT backfill, delete-file
  nullability.

- 🟢 **`codex/paimon-jni-write` — reference only (not on our path).** Adds Paimon
  **write via a BE JNI writer** (`vpaimon_jni_table_writer.cpp` + `PaimonJniWriter` in
  `be-java-extensions/paimon-scanner` + Nereids `Logical/PhysicalPaimonTableSink` +
  `PaimonInsertExecutor`), fixed-bucket write, unit + Spark-comparison regression tests.
  It's the **write-side analogue of the JNI scanner** (Paimon has no native BE writer).
  We write via `TIcebergTableSink` (BE writes Parquet natively), so we don't need it —
  but it's the concrete pattern for a plugin write-sink + InsertExecutor, and pairs with
  the "generic `plugin_driven` JNI" idea above. Hardcoded for paimon (not SPI-generic),
  on plain master.

- 🟢 **`branch-fs-spi` — parallel SPI track, not consumed yet.** A **FileSystem SPI
  cutover** (~95 commits), the storage-layer analogue of `branch-catalog-spi`: extracts
  FE filesystem access (S3/HDFS/Azure/Vault, presigned URLs) into an SPI'd `fe-filesystem`
  plugin with classloader isolation. We forward `s3.*`/`AWS_*` creds to the BE ourselves
  and don't compile against `fe-filesystem-spi`, so inert today. Long-term: if FE
  filesystem access becomes SPI'd, our credential-forwarding / warehouse-path handling
  could route through it. Watch only.

- ⚪ **`branch-seq_rc_file` / `branch-seq_rc_file_hive` — irrelevant (stale, wrong format).**
  Old branches (off ~2024 master #41062) adding a TVF + Hive support to read **SequenceFile
  / RCFile** — legacy Hadoop formats. DuckLake is Parquet-only. No relevance; noted so we
  don't re-investigate.

### `branch-catalog-spi` learnings from the 2026-07-22 bump (#65893)

The catalog-SPI migration is now **pushing per-source DDL validation OUT of fe-core into
the connectors** (#65893): `CreateTableInfo` dropped the hardcoded iceberg/paimon
`DISTRIBUTE BY` rejection, iceberg sort-order checks, and hive `NOT NULL` rejection —
connectors validate inline in their own `createTable` (throwing `DorisConnectorException`),
with **no new generic `validateCreateTable` SPI hook** (they mirror MaxCompute's
`validateColumns`). Direction of travel for us: our own `DuckLakeConnectorMetadata.createTable`
+ `DuckLakeCreatePartitionMapper` are already the validation authority, so this *helps*
(fe-core stops second-guessing us by engine string). The one behavioral gate to remember:
**`CREATE TABLE ... ORDER BY` is now capability-gated on `ConnectorCapability.SUPPORTS_SORT_ORDER`**
— see the TODO-write sorted-writes item.
