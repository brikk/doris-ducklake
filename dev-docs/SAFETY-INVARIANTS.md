# Safety invariants

Contracts the connector relies on to never return wrong rows. Each exists because the
alternative was observed to be silently wrong. Don't loosen one without a replacement
proof; the related finding ID points at [FINDINGS.md](FINDINGS.md).

## How ranges reach the BE

- Ranges are emitted as `table_format_type = "iceberg"` with `iceberg_params`, so the BE
  dispatches its Iceberg Parquet reader. That reader keys on Parquet field ids, which
  DuckLake writes as `field_id == column_id`. The alias is a transport choice, not a claim
  that DuckLake semantics equal Iceberg's (N01).
- `file_format_type=parquet` must be set at scan-node level; the per-range format string
  is not consulted for dispatch (N03).
- Never set a data range's top-level `TIcebergFileDesc.content` to 1 or 3; the BE routes
  those to a delete-file system-table reader. Delete descriptors go in `delete_files`.
- Scan-wide properties (schema dictionary, credentials) and per-range fields (path, deletes,
  `columns_from_path`) have different owners. Test with engine-prepopulated thrift, not
  only empty descriptors (N05).
- Nested columns need the complete parent-aware field-id tree in the schema dictionary.
  A scalar-only dictionary makes the BE fill populated nested roots with NULL, and a
  container field without its full subtree can crash the native reader (F12).

## Refusals

Each of these throws rather than returning an approximate answer:

- **Inlined DELETEs** at the pinned snapshot: the BE cannot apply catalog-resident positions.
- **Inlined DATA** unless `experimental.inlined.reads=true` on a local-filesystem warehouse
  (dev/compose only; the FE writes a temp Parquet the BE must be able to open by path).
- **Historical reads over delete files** (F08): a delete file may hold positions from
  several snapshots and the reader applies all of them. A null `partial_max` is not
  proof of a single snapshot.
- **Partially compacted files** newer than the pin: rows from later snapshots are tagged
  by a hidden column the BE cannot filter on.
- **Nested columns** on time-travel reads or tables with `add_files` name mappings (F11/F12).
- **Mixed hive-layout and native partitioned files** in one table: `path_partition_keys`
  is scan-node-wide (F13).
- **NULL hive partition values**: the BE's path fill cannot represent SQL NULL.
- **Temporal values outside Doris's domain** in stats, defaults or inlined rows.
- **DuckLake 1.1 catalogs**, at connector creation and on any later session via the
  library's format admission.

## Metadata shortcuts

COUNT(*) is served from `record_count` and LIMIT trims the file list only when the
count is provably exact: latest snapshot, no filter of any kind, no delete files, no
partial files, no inlined rows or deletes. Any doubt falls through to a normal scan and
the `-1` row-count sentinel stays on every range so the BE counts by reading.

Table statistics are estimates: they do not subtract position deletes or add inlined rows.

## Pruning

File-level pruning only ever keeps a superset of matching files; the BE re-evaluates the
full predicate. Strict bounds are widened to inclusive; membership sets collapse to a
span; `LIKE 'abc%'` becomes `[abc, abd)`. Functions, REGEXP, NOT, IS NULL and
mixed-column ORs are not pruned on. TIMESTAMP_NS columns are not pruned on at all (F09).
Erased FE casts can still make a pruned range unsafe (F26); string ordering assumes
codepoint collation (F06).

## Writes and maintenance

- `expire_snapshots` is catalog-wide regardless of the named table, and says so in its
  result row. ALTER on one table is not catalog-wide authority (F02).
- `expire_snapshots` commits before cleanup runs; a cleanup failure must not be retried as
  another expiry. The error says to resume with `cleanUpExpiredObjects`.
- `remove_orphan_files` deletes only `ducklake-*.{parquet,puffin,db}` objects that no
  catalog row references, are not inside a referenced directory, and are older than the
  retention floor. Known paths are compared by scheme-independent identity with no key
  normalization; repeated-slash and dot-segment keys are refused because the S3 SDK
  rewrites them. Retention is a grace period, not a reader lease (N10).
- The Postgres JDBC driver is registered once on the shared plugin classloader and never
  deregistered on catalog close; doing so breaks every other DuckLake catalog (N06).
- The INSERT commit is never retried and its files are never deleted on failure: the
  catalog may already own them.

## Tests and operations

- `parquet-format-structures` must precede the fat `fe-thrift` jar on test classpaths.
- A green smoke proves its enumerated assertions and receipts, not all types or the corpus.
- Loopback publication plus SSH is development containment, not tenant isolation.
- There is no automatic migration, hot reload or cleanup sweep in the stack; those are
  deliberate manual steps.
