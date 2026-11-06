package dev.brikk.ducklake.doris.plugin

import org.apache.doris.connector.spi.handle.ConnectorTableHandle
import org.apache.doris.connector.spi.pushdown.ConnectorExpression

/**
 * [snapshotId] is captured when the handle is created, so every call in a query sees one consistent
 * view; time travel re-stamps it. The pushdown fields are null until the corresponding `apply*` has
 * run, which is how the engine's fixed-point loop knows to stop.
 */
@JvmRecord
internal data class DuckLakeTableHandle(
    val database: String,
    val table: String,
    val schemaId: Long,
    val tableId: Long,
    val snapshotId: Long,
    val projectedColumnIds: List<Long>? = null,
    val pushedFilter: ConnectorExpression? = null,
    /** Data-file ids that survived pruning; null means scan every file. */
    val prunedFileIds: Set<Long>? = null,
) : ConnectorTableHandle
