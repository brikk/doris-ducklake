# DuckLake-on-Doris — Friction log

Open items from implementing the DuckLake `fe-connector` plugin against apache/doris `master`.
**Ordered by priority for a 4.2 ship** (top = fix first); each has a pickable upstream fix. Full
detail on the top items, papercuts kept short. Pin / build: [`../fe-patches/FE-PATCHES.md`](../fe-patches/FE-PATCHES.md).

> **Current as of apache/doris `master` @ `1731787677f`** — commit dated **2026-08-25 22:36:06 +0800**
> (`[chore](lance) update lance version to tag 0.1.7 (#67115)`). Findings below built + full-smoke
> validated against that commit (connector plugin API 6.0). No `branch-4.2` yet.
Sister docs: [`ducklake-doris-integration-spi-plan.md`](./ducklake-doris-integration-spi-plan.md),
[`ducklake-doris-sanity-check.md`](./ducklake-doris-sanity-check.md), [`TODO-read.md`](./TODO-read.md).

## Already fixed on master — validated live (context; the connector works end-to-end)
- **`COUNT(<nullable col>)`** pushdown — correct on master (was non-deterministic garbage).
- **Position-delete** OPTIONAL-column `[CORRUPTION]` — Step-7 DELETE round-trips end-to-end.
- **timestamptz-in-parquet** — `enable.mapping.timestamp_tz=true` reads a zone-aware `TIMESTAMPTZ`
  (`Int64ToTimestampTz`, #65446); probed live, instants + session-zone rendering correct.
- **`LOCAL_EXCHANGE_NODE` 38** FE/BE skew and **iceberg sys-table positional read** — moot / landed.

Also green on master: reads, DDL (CREATE/DROP DB+TABLE, bucket-partition), INSERT/CTAS, §13 GC (expire/cleanup/orphan), corpus-replay.

---

## P0 — silent wrong data: schema-evolution column DEFAULT reads 0 instead of the DEFAULT

**A read of an external-table column added with a `DEFAULT` over pre-existing rows returns `0`, not the
DEFAULT** — silent wrong values on a supported operation. (The BE **crash** this path used to throw is
gone again as of `1731787677f`; see timeline. What remains is the correctness miss.)

- **Repro:** `ALTER TABLE t ADD COLUMN b INT DEFAULT 42` over rows written before `b` existed, then
  `SELECT b FROM t` → old rows read `b=0` (DuckLake truth: `42`, stored in `ducklake_column.initial_default`);
  the post-ADD explicit row reads correctly, and there are no spurious NULLs.
- **Root cause.** The FE sends the default on `TFileScanSlotInfo.default_value_expr` and
  `FileScannerV2::_build_default_expr` reads it — but `IcebergTableReader::annotate_projected_column`
  (`format_v2/table/iceberg_reader.cpp`) makes the Iceberg `schema_column` authoritative and clears it
  (a plugin scan supplies no iceberg `initial-default`), so the fill falls to the type zero-value.
- **Fix.** In `annotate_projected_column`, **fall back to the FE-built `default_expr` when the iceberg
  `schema_column` supplies no `initial-default`**, instead of clearing it. (Connector-only can't fix
  this — we tried injecting the iceberg `initial_default_value` via the scan-time schema dictionary; the
  reader ignores it.)
- **Crash timeline (now resolved):** SIGSEGV (`Const(INT)` vs `Nullable(INT)` in
  `_evaluate_constant_filters`, `table_reader.h`) ≤`a82564ced5d` → fixed by `#66589` (`b42e1ab294b`) →
  regressed by `#66413`'s `column_mapper` rewrite (`168d0777833`) → **fixed again at `1731787677f`**
  (only `format_v2` touch in that window was `#66819`). Re-verified: full smoke completes, BE stays
  alive. Note for hardening: the const-vs-nullable guard has now regressed once — worth a permanent
  test so it can't segfault again.

Blast radius is only this path — all other reads/writes stay green.

---

## P1 — SPI gap, production-blocking: no channel to hand FE-computed rows to the BE

**DuckLake keeps small tables' data *and* small deletes inline in the catalog DB** (`ducklake_inlined_*`),
not in files — and `DATA_INLINING_ROW_LIMIT` defaults non-zero, so **any small INSERT/DELETE lands here:
the common case, not an edge case.** The BE reads files only, and the `fe-connector` SPI has no way for
a connector to hand FE-computed rows to it. Three manifestations, one root gap:

- **Inlined DATA read** — plugin refuses at plan time (`…inlined data rows…not supported`).
- **Inlined DELETE** — DuckDB deletes vanish from Doris' view; only file-based deletes route through
  `iceberg_params.delete_files`.
- **Our only workaround** — synthesize a temp Parquet FE-side and emit a `FILE_SCAN` range. **Works only
  when FE and BE share a filesystem** (compose bind-mount), so it's **dev-only, not production-viable**;
  plus temp-file GC is on us (the SPI has no end-of-scan/close hook).

Surveyed: no in-memory/values `ConnectorScanRange` type; `META_SCAN`/`DATA_GEN` are closed enums (lossy
`TCell`, no DECIMAL/nested); the iceberg/paimon JNI sys-table path is a hardcoded `table_format_type`
dispatch with no generic `plugin_driven` case.

**Fix (ranked):**
1. **Smallest:** a generic `FORMAT_JNI` `table_format_type == "plugin_driven"` dispatch routing to a
   connector-provided JNI scanner (mirrors the iceberg sys-table path) — arbitrary FE rows, no temp file.
2. **Cleanest:** an in-memory/values `ConnectorScanRange` that carries literal rows to the BE over the
   fragment RPC (needs full `TCell` type coverage incl. DECIMAL/nested).
3. **Ergonomic add either way:** a scan-scoped `Closeable`/end-of-scan hook so temp-file materializers
   can clean up deterministically.

Until one lands, inlined reads/deletes are gated off outside a shared-FS dev setup.

---

## P2 — BE Iceberg writer: narrow-int (TINYINT/SMALLINT) writes fail

`CREATE TABLE … AS SELECT CAST(1 AS TINYINT)` (or INSERT into a TINYINT/SMALLINT col) fails:
`Bad cast from arrow::NumericBuilder<Int32Type> to arrow::NumericBuilder<Int8Type>`. Iceberg has no
8/16-bit int, so the field is Arrow `Int32`, but the BE picks the serde by the **source** Doris type
(TINYINT) and `assert_cast`s the builder to `Int8`. (On master this is now a clean `[INTERNAL_ERROR]`
— the BE stays alive; on 4.1.x it aborted the process.) Connector type mapping is correct; the bug is
the writer's serde-vs-builder pick. **Fix (BE):** in `VIcebergTableWriter`/`FromBlockConverter`, select
the serde by the *target* Arrow field type (or up-cast int8/int16 → int32) before `write_column_to_arrow`.
**Workaround:** use INT/BIGINT (`CAST(x AS INT)`).

## P2 — SPI gap: schema info is scan-node-level, can't express per-FILE column mapping

DROP-then-re-ADD of a column over id-less `add_files` parquet with reused physical names is unresolvable:
the schema dictionary (`TFileScanRangeParams` current/history schema) is emitted **once per scan node**,
but each DuckLake file has its own `mapping_id → name_mapping`. One table-level dict can't say "file A's
`col2` = field-id 2, file B's `col2` = field-id 4." **Workaround:** conflict-aware name union → BE errors
loudly instead of mis-binding; 2 corpus files skip-listed (rename / simple add_files are fine).
**Fix:** let `ConnectorScanRange.populateRangeParams` attach a **per-file** field-id/name map (parallel
to how `iceberg_params` rides per-range), instead of only the scan-node dictionary.

---

## P3 — papercuts (workarounds in place; small, pickable fixes)

- **BE reader dispatch keyed on the literal `"iceberg"`** (`file_scanner.cpp` parquet/orc branches) — a
  `table_format_type="ducklake"` gets no reader. We emit `"iceberg"` (EXPLAIN then misleadingly shows
  iceberg). *Fix:* add `|| == "ducklake"` to the branch (5 lines).
- **BE S3 creds need `AWS_*` verbatim; `s3.*` silently dropped** (`s3_util.cpp` literal lookups). We emit
  both forms. *Fix:* accept `s3.*` aliases, or normalize engine-side, or document the contract.
- **`ConnectorScanRange.getFileFormat()` is dead on the plugin path** — `PluginDrivenScanNode` reads
  `file_format_type` from `getScanNodeProperties()` and silently defaults missing → `FORMAT_JNI`. We emit
  the property. *Fix:* fall back to `getFileFormat()`, or raise instead of defaulting.
- **External partitioned `CREATE TABLE` arrives as `Style.LIST`/`RANGE`, never `TRANSFORM`** — the FE
  keys `ConnectorPartitionSpec.style` off the grammar keyword; the real transform (`bucket(4,col)`) is in
  the per-field data. We map per-field regardless of style (validated green). *Fix:* prefer
  `Style.TRANSFORM` in `convertPartition` when every expr is a transform/identity.

## P4 — SPI-handbook / deploy notes (zero- or near-zero-code)

- **`populateScanLevelParams` vs `populateRangeParams` scope is unenforced** — per-range thrift
  (`iceberg_params`, per-file cols) must go in `populateRangeParams`; shared state (creds, `serialized_table`)
  in `populateScanLevelParams`. *Fix:* document the scope split in the SPI javadoc.
- **Engine injects keys into `CREATE CATALOG`** (e.g. `enable.mapping.varbinary`) before the plugin sees
  the map → naive strict validation rejects them. *Fix:* document "validate required/known keys; tolerate
  unknowns."
- **`DriverManager` can't see plugin-classloader JDBC drivers** — child-loader `META-INF/services` isn't
  discovered; every JDBC plugin must `Class.forName(driver)` under its own CL. *Fix:* document the pattern.
- **`connector_plugin_root` default is hardcoded** (`Config.java`) and absent from `fe.conf`; wrong dir =
  silent no-op. *Fix:* add a commented line to `conf/fe.conf`.
- **Deploy gotchas (compose):** FE `healthcheck` on `SELECT 1` deadlocks BE startup (use `SHOW FRONTENDS`);
  `start_fe.sh` reads JVM flags from `fe.conf` only, not env-vars. *Fix:* FE-local health probe; prefer
  env-vars when present.

---

## How to add an entry
Slot by 4.2 priority (P0 crash/dataloss → P4 docs). Keep it tight: literal error + `file:line` root
cause + one-line workaround + a pickable (single-PR) fix. Small things get small entries.
