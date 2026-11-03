# Research backlog

Unscheduled questions, not implementation commitments. Current correctness work
is in [TODO-read.md](TODO-read.md) and [TODO-write.md](TODO-write.md).

- [ ] **Doris as a catalog metadata backend:** establish OLTP/transaction/constraint
  semantics and a migration dialect before considering a PostgreSQL replacement.
- [ ] **DuckLake metadata/system tables:** queryable snapshots/history/files need a
  supported FE-to-BE row transport or a qualified scanner. Preserve projection order;
  don't expose an Iceberg-only JNI dispatch as generic support.
- [ ] **Metastore events/CDC:** evaluate snapshot-log-driven invalidation/MV refresh,
  cancellation, ordering and recovery. No current CDC qualification is claimed.
- [ ] **Storage abstraction:** re-evaluate owned filesystem SPI handles for FE-side
  maintenance rather than cross-calling Hive/HMS internals. Separate access to files
  from authorization or permission to stage/delete lake objects. Retained
  [Hive survey](archive/RESEARCH-hive-cross-call.md) is evidence, not a current API promise.
- [ ] **Other file formats:** only after read correctness/write hardening, re-check
  actual BE readers and SPI/JNI seams. This connector admits Parquet only; no Lance,
  Vortex or DuckDB-native execution adoption follows from upstream feature news.
- [ ] **Shared helper ownership:** measure value before deduplicating bucket hashing
  or catalog/planner helpers. Keep source-type/hash semantics explicit.

Old branch watches and proposed catalog Java-to-Kotlin migration are retained in
[the research log](archive/TODO-research-through-2026-11-02.md) and
[read log](archive/TODO-read-through-2026-11-02.md). They are not current Doris
integration tasks. Review current master and release branches using
[DORIS-RUNTIME.md](DORIS-RUNTIME.md), not obsolete fork branches.
