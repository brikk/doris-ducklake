package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog

/** Internal, completely materialized statement plan; not a SQL connection or a file lease. */
internal interface DuckLakeQualifiedReadCatalog : DucklakeCatalog {
    fun atSnapshot(snapshotId: Long): DuckLakeQualifiedReadCatalog
    fun qualifiedDataPath(dataFileId: Long): String
    fun qualifiedPositionDeletes(dataFileId: Long): List<DuckLakePositionDelete>
}
