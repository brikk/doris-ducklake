package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakePartitionSpec
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.handle.ConnectorWriteHandle
import org.apache.doris.connector.spi.write.ConnectorSinkPlan
import org.apache.doris.connector.spi.write.ConnectorWritePlanProvider
import org.apache.doris.thrift.TDataSink
import org.apache.doris.thrift.TDataSinkType
import org.apache.doris.thrift.TFileCompressType
import org.apache.doris.thrift.TFileFormatType
import org.apache.doris.thrift.TFileType
import org.apache.doris.thrift.TIcebergTableSink
import org.apache.iceberg.PartitionSpecParser
import org.apache.iceberg.SchemaParser

/**
 * Builds the BE sink for a DuckLake INSERT. A `TIcebergTableSink` is used because the BE's Iceberg
 * writer reports per-field-id column stats in `TIcebergCommitData`, which the Hive sink doesn't;
 * [DuckLakeConnectorTransaction] commits those. `schema_json` comes from the DuckLake columns with
 * `field_id == column_id`, and a partitioned table also carries the active spec as an Iceberg spec.
 *
 * `output_path`, `hadoop_config` and `file_type` have no headless oracle; the compose smoke against a
 * real BE is the validation.
 */
internal class DuckLakeWritePlanProvider(
    private val catalog: DucklakeCatalog,
    private val pathResolver: DuckLakePathResolver,
    private val properties: Map<String, String>,
) : ConnectorWritePlanProvider {

    // The Iceberg writer binds by position, so the FE must reorder and fill omitted columns.
    override fun requiresFullSchemaWriteOrder(): Boolean = true

    override fun planWrite(session: ConnectorSession, handle: ConnectorWriteHandle): ConnectorSinkPlan {
        val tableHandle = handle.tableHandle.asDuckLakeHandle<DuckLakeTableHandle>()
        val outputPath = resolveOutputPath(tableHandle)
        // At most one spec is active at a snapshot.
        val partitionSpec = catalog.getPartitionSpecs(tableHandle.tableId, tableHandle.snapshotId).firstOrNull()
        bindTransaction(session, tableHandle, outputPath, partitionSpec?.partitionId)
        val sink = buildSink(tableHandle, outputPath, handle.isOverwrite, partitionSpec)
        return ConnectorSinkPlan(TDataSink(TDataSinkType.ICEBERG_TABLE_SINK).setIcebergTableSink(sink))
    }

    /** The data dir relativizes the BE's absolute paths at commit; the partition id is stamped on each fragment. */
    private fun bindTransaction(
        session: ConnectorSession,
        handle: DuckLakeTableHandle,
        tableDataDir: String,
        partitionId: Long?,
    ) {
        val transaction = session.currentTransaction.orElseThrow {
            DorisConnectorException("DuckLake INSERT requires an active connector transaction on the session")
        }
        val duckLakeTransaction = transaction as? DuckLakeConnectorTransaction
            ?: throw DorisConnectorException("unexpected transaction type: ${transaction.javaClass.name}")
        duckLakeTransaction.bindTarget(handle.tableId, handle.snapshotId, tableDataDir, partitionId)
    }

    private fun buildSink(
        handle: DuckLakeTableHandle,
        outputPath: String,
        overwrite: Boolean,
        partitionSpec: DucklakePartitionSpec?,
    ): TIcebergTableSink {
        val columns = catalog.getTableColumns(handle.tableId, handle.snapshotId)
        val schema = DuckLakeIcebergSchema.of(columns)
        return TIcebergTableSink().apply {
            setDbName(handle.database)
            setTbName(handle.table)
            setSchemaJson(SchemaParser.toJson(schema))
            setFileFormat(TFileFormatType.FORMAT_PARQUET)
            // The BE rejects a sink without a compression type.
            setCompressionType(TFileCompressType.ZSTD)
            setOutputPath(outputPath)
            setOriginalOutputPath(outputPath)
            setFileType(fileTypeFor(outputPath))
            setHadoopConfig(storageConfig())
            setOverwrite(overwrite)
            // Defaults to false, and without stats `SELECT COUNT(<nullable col>)` returns 0: the BE serves
            // COUNT(col) as rowcount − null_count from `ducklake_file_column_stats`.
            setCollectColumnStats(true)
            if (partitionSpec != null) {
                val nameById = columns.associate { it.columnId to it.columnName }
                val icebergSpec = DuckLakeIcebergPartitionSpec.of(partitionSpec, schema, nameById)
                setPartitionSpecsJson(mapOf(DuckLakeIcebergPartitionSpec.SINK_SPEC_ID to PartitionSpecParser.toJson(icebergSpec)))
                setPartitionSpecId(DuckLakeIcebergPartitionSpec.SINK_SPEC_ID)
            }
        }
    }

    private fun resolveOutputPath(handle: DuckLakeTableHandle): String {
        val schema = catalog.getSchema(handle.database, handle.snapshotId)
            ?: throw DorisConnectorException("schema not found: ${handle.database}")
        val table = catalog.getTable(handle.database, handle.table, handle.snapshotId)
            ?: throw DorisConnectorException("table not found: ${handle.database}.${handle.table}")
        return pathResolver.resolveTableDataPath(schema, table)
    }

    /** Same keys and `AWS_*` aliases the read path emits. */
    private fun storageConfig(): Map<String, String> {
        val config = LinkedHashMap<String, String>()
        for ((key, value) in properties) {
            if (DuckLakeScanPlanProvider.isStorageProperty(key)) {
                config[key] = value
                DuckLakeScanPlanProvider.canonicalAwsAlias(key)?.let { alias -> config[alias] = value }
            }
        }
        return config
    }

    private fun fileTypeFor(path: String): TFileType = when {
        path.startsWith("s3://") || path.startsWith("s3a://") || path.startsWith("s3n://") -> TFileType.FILE_S3
        path.startsWith("hdfs://") -> TFileType.FILE_HDFS
        else -> TFileType.FILE_LOCAL
    }
}
