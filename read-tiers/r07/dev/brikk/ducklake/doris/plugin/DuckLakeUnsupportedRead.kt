package dev.brikk.ducklake.doris.plugin

/** A deliberate per-table/frame boundary, never a metadata/query/I/O/budget failure. */
internal class DuckLakeUnsupportedRead(message: String) : IllegalStateException(message)
