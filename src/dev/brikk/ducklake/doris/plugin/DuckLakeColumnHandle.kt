package dev.brikk.ducklake.doris.plugin

import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.handle.ConnectorColumnHandle

/** [columnId] is DuckLake's stable id (survives renames, equals the Parquet field id). */
@JvmRecord
internal data class DuckLakeColumnHandle(
    val columnId: Long,
    val columnName: String,
    val columnType: ConnectorType,
    val ordinalPosition: Int,
) : ConnectorColumnHandle
