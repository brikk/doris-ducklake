package dev.brikk.ducklake.doris.plugin

import java.io.Serializable

/**
 * A position-delete file attached to a [DuckLakeScanRange]. `path` is already absolute;
 * `fileFormat` is lowercase to match the BE's case-sensitive dispatch.
 */
internal data class DuckLakePositionDelete(
    @get:JvmName("path") val path: String,
    @get:JvmName("fileFormat") val fileFormat: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
