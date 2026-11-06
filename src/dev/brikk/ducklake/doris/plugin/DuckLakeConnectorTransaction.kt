package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.handle.ConnectorTransaction
import org.apache.doris.thrift.TIcebergCommitData
import org.apache.thrift.TDeserializer
import org.apache.thrift.TException
import org.apache.thrift.protocol.TBinaryProtocol

/**
 * Turns BE write-result fragments into one DuckLake snapshot commit. The engine opens it via
 * `beginTransaction`, the write plan binds the target, the BE feeds `TIcebergCommitData` fragments
 * through [addCommitData], and [commit] records them in the catalog.
 */
internal class DuckLakeConnectorTransaction(
    private val transactionId: Long,
    private val catalog: DucklakeCatalog,
) : ConnectorTransaction {

    private data class Target(
        val tableId: Long,
        val snapshotId: Long,
        val tableDataDir: String?,
        val partitionId: Long?,
    )

    private val commitData = ArrayList<TIcebergCommitData>()

    @Volatile
    private var target: Target? = null

    fun bindTarget(tableId: Long, snapshotId: Long, tableDataDir: String? = null, partitionId: Long? = null) {
        target = Target(tableId, snapshotId, tableDataDir, partitionId)
    }

    override fun getTransactionId(): Long = transactionId

    override fun addCommitData(commitFragment: ByteArray) {
        val data = TIcebergCommitData()
        try {
            TDeserializer(TBinaryProtocol.Factory()).deserialize(data, commitFragment)
        } catch (e: TException) {
            throw DorisConnectorException("failed to deserialize DuckLake iceberg commit fragment", e)
        }
        synchronized(this) { commitData.add(data) }
    }

    override fun getUpdateCnt(): Long = synchronized(this) {
        commitData.sumOf { if (it.isSetRowCount) it.rowCount else 0L }
    }

    override fun commit() {
        val bound = target
            ?: throw DorisConnectorException("DuckLake write committed with no bound target table")
        val typeByColumnId = catalog.getTableColumns(bound.tableId, bound.snapshotId)
            .associate { it.columnId to it.columnType }
        val fragments = synchronized(this) {
            commitData.map {
                DuckLakeIcebergCommitMapper.toWriteFragment(it, typeByColumnId, bound.tableDataDir, bound.partitionId)
            }
        }
        if (fragments.isNotEmpty()) {
            // Not retried and the files are never deleted on failure: the catalog may already own them.
            catalog.commitInsert(bound.tableId, fragments)
        }
    }

    /** Drops only in-memory fragments: the commit may have applied even if `commit()` threw. */
    override fun rollback() {
        synchronized(this) { commitData.clear() }
    }

    override fun close() = Unit
}
