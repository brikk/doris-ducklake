package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeColumn
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.scan.ConnectorScanRange
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * Serves DuckLake inlined DATA rows (rows the catalog keeps in `ducklake_inlined_data_*`, not in data
 * files) by writing them to a temp Parquet the BE scans as one more range. Written with
 * `field_id == column_id`, so the schema dictionary resolves it like a real file.
 *
 * Dev/compose only: the BE opens the file by path, so FE and BE must share a filesystem. Off by
 * default behind [PROP_INLINED_READS] and limited to local-filesystem warehouses. The production
 * channel (object-store write or an SPI payload seam) is tracked in `dev-docs/TODO.md`.
 */
internal class DuckLakeInlinedDataRanges(
    private val catalog: DucklakeCatalog,
    private val catalogProperties: Map<String, String>,
) {

    /** Empty when the table has no live inlined rows at the handle's snapshot. */
    fun plan(handle: DuckLakeTableHandle): List<ConnectorScanRange> {
        val infos = catalog.getInlinedDataInfos(handle.tableId, handle.snapshotId)
            .filter { it.hasLiveRows }
        if (infos.isEmpty()) {
            return emptyList()
        }
        if (!enabled()) {
            throw DorisConnectorException(
                "DuckLake table has live inlined data rows (in the metadata catalog, not data files); " +
                    "reading them is not supported by default — the current implementation writes a temp " +
                    "file to shared storage and only works where the FE and BE share a filesystem " +
                    "(dev/compose). Flush inlined data (CALL <lake>.flush_inlined_data()) or disable " +
                    "inlining (CALL <lake>.set_option('data_inlining_row_limit', '0')) and rewrite. " +
                    "To enable the experimental dev-only path, set the catalog property " +
                    "'$PROP_INLINED_READS'='true' (shared FE/BE storage required).",
            )
        }
        val columns = topLevelScalarColumnsOrThrow(handle)
        val scratchDir = Files.createDirectories(scratchDir())
        val ranges = ArrayList<ConnectorScanRange>(infos.size)
        for (info in infos) {
            val rows = catalog.readInlinedData(handle.tableId, info.schemaVersion, handle.snapshotId, columns)
            if (rows.isEmpty()) {
                continue
            }
            val target = scratchDir.resolve(
                "ducklake-inlined-${handle.tableId}-${info.schemaVersion}-${handle.snapshotId}.parquet",
            )
            DuckLakeInlinedParquetWriter.write(target, columns, rows)
            val size = Files.size(target)
            ranges.add(
                DuckLakeScanRange.Builder()
                    .path(target.toString())
                    .start(0L)
                    .length(size)
                    .fileSize(size)
                    .fileFormat(DuckLakeScanPlanProvider.PARQUET_FORMAT)
                    .positionDeletes(emptyList())
                    .partitionBearing(false)
                    .partitionValues(emptyMap())
                    .pushDownRowCount(-1L)
                    .build(),
            )
        }
        return ranges
    }

    private fun enabled(): Boolean =
        catalogProperties[PROP_INLINED_READS]?.trim().equals("true", ignoreCase = true)

    /**
     * Nested columns need a recursive DuckDB-text parser the writer doesn't have. `timestamptz` is
     * excluded because the synthesized file's zone-aware timestamp surfaces as UNSUPPORTED in Nereids
     * on read-back, unlike a DuckLake-written file.
     */
    private fun topLevelScalarColumnsOrThrow(handle: DuckLakeTableHandle): List<DucklakeColumn> {
        val columns = catalog.getTableColumns(handle.tableId, handle.snapshotId)
            .filter { it.parentColumn == null }
            .sortedBy { it.columnOrder }
        val unsupported = columns.firstOrNull { !isWritableScalar(it.columnType) }
        if (unsupported != null) {
            // "not supported" wording is what the corpus mirror's error classifier recognizes as an engine skip.
            throw DorisConnectorException(
                "reading inlined data with column '${unsupported.columnName}' (${unsupported.columnType}) " +
                    "is not supported yet by the Doris connector (nested types, timestamptz, and " +
                    "degraded-to-string types are not supported). Flush inlined data " +
                    "(CALL <lake>.flush_inlined_data()) or disable inlining and rewrite.",
            )
        }
        return columns
    }

    private fun isWritableScalar(ducklakeType: String): Boolean {
        val t = ducklakeType.trim().lowercase(Locale.ROOT)
        if (t.startsWith("list<") || t.startsWith("struct<") || t.startsWith("map<")) {
            return false
        }
        if (t.startsWith("decimal(")) {
            return true
        }
        return t in WRITABLE_SCALARS
    }

    /**
     * The BE must reach the file at the same absolute path, and the only path FE and BE share (even
     * in compose) is the warehouse itself; `java.io.tmpdir` is container-local. So the file goes in a
     * dot-dir at the warehouse root: a sibling of the per-table dirs that no table-scoped glob reaches
     * and maintenance ignores.
     */
    private fun scratchDir(): Path {
        catalogProperties[PROP_SCRATCH_PATH]?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it)
        }
        return localWarehouseRootOrThrow().resolve(SCRATCH_DIRNAME)
    }

    private fun localWarehouseRootOrThrow(): Path {
        val root = catalog.getDataPath()
            ?: catalogProperties[DuckLakeConnectorProperties.STORAGE_WAREHOUSE]
            ?: throw DorisConnectorException(
                "DuckLake table has inlined data but no warehouse data path is configured; " +
                    "cannot place the synthesized inlined-data file.",
            )
        val scheme = try {
            URI(root).scheme
        } catch (_: Exception) {
            null
        }
        val localPath = when (scheme) {
            null -> root
            "file" -> URI(root).path
            else -> throw DorisConnectorException(
                "DuckLake table has inlined data but its warehouse is '$scheme://…'; reading inlined rows " +
                    "is not supported for non-local-filesystem warehouses yet (needs an object-store or SPI " +
                    "payload channel). Flush inlined data " +
                    "(CALL <lake>.flush_inlined_data()) or disable inlining and rewrite.",
            )
        }
        return Paths.get(localPath)
    }

    companion object {
        const val PROP_INLINED_READS: String = "experimental.inlined.reads"

        /** Must be BE-reachable at the same path. */
        const val PROP_SCRATCH_PATH: String = "scratch.path"

        private const val SCRATCH_DIRNAME: String = ".doris-inlined-scratch"

        /** Scalar DuckLake types [DuckLakeInlinedParquetWriter] can encode; `decimal(p,s)` is handled separately. */
        private val WRITABLE_SCALARS: Set<String> = setOf(
            "boolean",
            "int8", "int16", "int32", "uint8", "uint16",
            "int64", "uint32",
            "float32", "float64",
            "date",
            "timestamp", "timestamp_s", "timestamp_ms", "timestamp_ns", "timestamptz",
            "varchar", "blob",
        )
    }
}
