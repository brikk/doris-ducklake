package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeFileKind
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.apache.doris.connector.spi.scan.ConnectorScanRequest
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.io.LocalInputFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.function.Supplier
import java.util.zip.CRC32

/** Native disposable producers, real catalog + Doris planner; NOT a claim of BE/CDC support. */
internal class DuckLakeR07QualificationTest {
    @TempDir lateinit var root: Path

    private val database get() = root.resolve("metadata.db")
    private val data get() = root.resolve("data")

    private fun native(sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:duckdb:").use { c ->
        c.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake")
            s.executeQuery("PRAGMA version").use { r ->
                check(r.next())
                assertThat(r.getString(1)).isEqualTo("v2.0.0")
                assertThat(r.getString(2)).isEqualTo("d54cbbe3df")
            }
            s.executeQuery("SELECT extension_version,current_setting('allow_unsigned_extensions') " +
                "FROM duckdb_extensions() WHERE extension_name='ducklake'").use { r ->
                check(r.next())
                assertThat(r.getString(1)).isEqualTo("417a9117f5")
                assertThat(r.getBoolean(2)).isFalse()
            }
            s.execute("ATTACH 'ducklake:$database' AS lake (DATA_PATH '$data', " +
                "DUCKLAKE_VERSION '1.1', AUTOMATIC_MIGRATION FALSE)")
            if (s.execute(sql)) s.resultSet.use { r -> buildList {
                while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
            } } else emptyList()
        }
    }

    private fun reader() = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = "jdbc:duckdb:$database"
        dataPath = data.toString()
        maxCatalogConnections = 1
        enableFinal11Reads = true
    })

    private fun <T> JdbcDucklakeCatalog.read(action: () -> T): T = readSession(Supplier { action() })

    private fun handle(c: JdbcDucklakeCatalog, snapshot: Long = c.currentSnapshotId): DuckLakeTableHandle {
        val table = c.getTable("main", "t", snapshot)!!
        val schema = c.getSchema("main", snapshot)!!
        return DuckLakeTableHandle("main", "t", schema.schemaId, table.tableId, snapshot)
    }

    private fun planner(c: JdbcDucklakeCatalog, experimental: Boolean = false) = DuckLakeScanPlanProvider(
        c, DuckLakePathResolver(c, data.toString()),
        mapOf("storage.warehouse" to data.toString(), "experimental.inlined.reads" to experimental.toString()),
    )

    private fun snapshot(): Long = native("SELECT max(snapshot_id) FROM ducklake_snapshots('lake')")
        .single().single()!!.toLong()

    @Test
    fun publicConnectorStillRejects11RatherThanSilentlyOptingIn() {
        native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
        DuckLakeConnectorProvider().create(mapOf(
            "metadata.url" to "jdbc:duckdb:$database", "metadata.user" to "unused",
            "storage.warehouse" to data.toString(),
        ), FakeConnectorContext("r07", 701L)).use { connector ->
            assertThatThrownBy { connector.getMetadata(null).getTableHandle(null, "main", "t") }
                .hasMessageContaining("1.1")
            assertThatThrownBy { connector.getWritePlanProvider() }.hasMessageContaining("1.1 writes")
            assertThatThrownBy { connector.getProcedureOps() }.hasMessageContaining("1.1 writes")
        }
    }

    @Test
    fun decodedTemporalNullLiteralAndBytesHaveIndependentExpectedValues() {
        native("CREATE TABLE lake.t(i INTEGER,d DATE,ts TIMESTAMP,s VARCHAR,b BLOB); " +
            "INSERT INTO lake.t VALUES(1,DATE '2024-02-29',TIMESTAMP '2024-02-29 12:34:56.123456',NULL,from_hex('005CFF'))," +
            "(2,NULL,NULL,'NULL',from_hex('5C783431'))")
        reader().use { c -> c.read {
            val h = handle(c)
            val cols = c.getTableColumns(h.tableId, h.snapshotId)
            val info = c.getInlinedDataInfos(h.tableId, h.snapshotId).single()
            val raw = c.readInlinedData(h.tableId, info.schemaVersion, h.snapshotId, cols)
            val decoded = c.readInlinedDataDecoded(h.tableId, info.schemaVersion, h.snapshotId, cols)
            assertThat(raw[0][1]).isEqualTo("2024-02-29")
            assertThat(raw[0][2]).isEqualTo("2024-02-29 12:34:56.123456")
            assertThat(decoded[0].take(4)).containsExactly(1, LocalDate.of(2024, 2, 29),
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123456000), null)
            assertThat(decoded[1].take(4)).containsExactly(2, null, null, "NULL")
            assertThat(decoded[0][4] as ByteArray).containsExactly(0, 92, -1)
            assertThat(decoded[1][4] as ByteArray).containsExactly(92, 120, 52, 49)
            assertThatThrownBy { planner(c, true).planScan(null, ConnectorScanRequest.builder(h, emptyList()).build()) }
                .hasMessageContaining("no distributed FE-to-BE payload")
        } }
    }

    @Test
    fun stableIdsInitialDefaultsAndStoredNullRemainDistinct() {
        native("CREATE TABLE lake.t(i INTEGER,s VARCHAR); INSERT INTO lake.t VALUES(1,NULL),(2,'NULL')")
        reader().use { c -> c.read {
            val h = handle(c)
            assertThat(c.getTableColumns(h.tableId, h.snapshotId).map { it.columnId }).containsExactly(1, 2)
        } }
        native("ALTER TABLE lake.t RENAME COLUMN s TO renamed; ALTER TABLE lake.t ADD COLUMN d INTEGER DEFAULT 42")
        reader().use { c -> c.read {
            val h = handle(c)
            val cols = c.getTableColumns(h.tableId, h.snapshotId)
            assertThat(cols.map { it.columnId }).containsExactly(1, 2, 3)
            assertThat(cols.map { it.columnName }).containsExactly("i", "renamed", "d")
            val rows = c.getInlinedDataInfos(h.tableId, h.snapshotId).flatMap {
                c.readInlinedDataDecoded(h.tableId, it.schemaVersion, h.snapshotId, cols)
            }
            assertThat(rows).containsExactly(listOf(1, null, 42), listOf(2, "NULL", 42))
        } }
    }

    @Test
    fun unsupportedVariantValuesRejectRawAndDecoded() {
        native("CREATE TABLE lake.t(i INTEGER,v VARIANT); INSERT INTO lake.t VALUES(1,NULL)")
        reader().use { c -> c.read {
            val h = handle(c)
            val cols = c.getTableColumns(h.tableId, h.snapshotId)
            val info = c.getInlinedDataInfos(h.tableId, h.snapshotId).single()
            assertThatThrownBy { c.readInlinedData(h.tableId, info.schemaVersion, h.snapshotId, cols) }
                .hasMessageContaining("variant")
            assertThatThrownBy { c.readInlinedDataDecoded(h.tableId, info.schemaVersion, h.snapshotId, cols) }
                .hasMessageContaining("variant")
        } }
    }

    @Test
    fun time24DoesNotSilentlyBecomeMidnight() {
        native("CREATE TABLE lake.t(v TIME); INSERT INTO lake.t VALUES(TIME '24:00:00')")
        reader().use { c -> c.read {
            val h = handle(c)
            val cols = c.getTableColumns(h.tableId, h.snapshotId)
            val info = c.getInlinedDataInfos(h.tableId, h.snapshotId).single()
            assertThatThrownBy { c.readInlinedData(h.tableId, info.schemaVersion, h.snapshotId, cols) }
                .hasMessageContaining("24:00")
            assertThatThrownBy { c.readInlinedDataDecoded(h.tableId, info.schemaVersion, h.snapshotId, cols) }
                .hasMessageContaining("24:00")
        } }
    }

    @Test
    fun unknownDeleteCountIsNotZeroAndDorisOmitsTheEstimate() {
        native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',true); " +
            "CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t FROM range(20); DELETE FROM lake.t WHERE i<3")
        DriverManager.getConnection("jdbc:duckdb:$database").use { c -> c.createStatement().use {
            it.execute("UPDATE ducklake_delete_file SET delete_count=NULL")
        } }
        reader().use { c -> c.read {
            val h = handle(c)
            assertThat(c.readMetadata().getFileReadMetadata(h.tableId, h.snapshotId).single().deleteCount).isNull()
            assertThatThrownBy { c.getLiveRowCount(h.tableId, h.snapshotId) }.hasMessageContaining("cannot assume zero")
            assertThat(DuckLakeConnectorMetadata(c).getTableStatistics(null, h)).isEmpty()
        } }
    }

    @Test
    fun geometryUsesRealNativeParquetButDoesNotEstablishDorisPayloadSupport() {
        native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER,g GEOMETRY); " +
            "INSERT INTO lake.t VALUES(1,'POINT(1 2)'::GEOMETRY),(2,NULL)")
        reader().use { c -> c.read {
            val h = handle(c)
            assertThat(c.getTableColumns(h.tableId, h.snapshotId).map { it.columnType })
                .containsExactly("int32", "geometry")
            val path = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 2).single().resolvedPath
            ParquetFileReader.open(LocalInputFile(Path.of(path))).use { p ->
                assertThat(p.footer.blocks.sumOf { it.rowCount }).isEqualTo(2)
                assertThat(p.footer.fileMetaData.schema.getType("g").id.intValue()).isEqualTo(2)
                println("NATIVE GEOMETRY FOOTER ${p.footer.fileMetaData.schema}")
            }
        } }
    }

    @Test
    fun mixedFileInlineNativeFixtureRejectsBeforeReturningAnIncompletePlan() {
        native("CALL lake.set_option('data_inlining_row_limit',2); CREATE TABLE lake.t(i INTEGER); BEGIN; " +
            "INSERT INTO lake.t VALUES(1),(2); INSERT INTO lake.t VALUES(3),(4),(5),(6),(7); COMMIT")
        assertThat(native("SELECT i FROM lake.t ORDER BY i")).isEqualTo((1..7).map { listOf(it.toString()) })
        reader().use { c -> c.read {
            val h = handle(c)
            assertThat(c.getDataFiles(h.tableId, h.snapshotId).sumOf { it.recordCount }).isEqualTo(5)
            assertThat(c.getLiveRowCount(h.tableId, h.snapshotId)).isEqualTo(7)
            assertThatThrownBy { planner(c, true).planScan(null, ConnectorScanRequest.builder(h, emptyList()).build()) }
                .hasMessageContaining("1.1 DATA scans are not supported")
        } }
    }

    @Test
    fun nativeMergeNullableStartAndEmbeddedIdsDoNotEnableHistoricalScanOrGrossEstimate() {
        native("CALL lake.set_option('data_inlining_row_limit',2); CREATE TABLE lake.t(i INTEGER); " +
            "INSERT INTO lake.t VALUES(1),(2),(3)")
        val first = snapshot()
        native("INSERT INTO lake.t VALUES(4)")
        native("INSERT INTO lake.t VALUES(5),(6),(7)")
        native("CALL ducklake_merge_adjacent_files('lake','t')")
        reader().use { c -> c.read {
            val h = handle(c, first)
            val file = c.getDataFiles(h.tableId, first).single()
            assertThat(file.rowIdStart).isNull()
            val path = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 10)
                .single { it.fileId == file.dataFileId }.resolvedPath
            ParquetFileReader.open(LocalInputFile(Path.of(path))).use { parquet ->
                assertThat(parquet.footer.fileMetaData.schema.fields.map { it.name })
                    .contains("_ducklake_internal_row_id", "_ducklake_internal_snapshot_id")
                assertThat(parquet.footer.blocks.sumOf { it.rowCount }).isEqualTo(6)
            }
            assertThatThrownBy { planner(c).planScan(null, ConnectorScanRequest.builder(h, emptyList()).build()) }
                .hasMessageContaining("historical file scans are not supported")
            assertThatThrownBy { c.getLiveRowCount(h.tableId, first) }.hasMessageContaining("require a scan")
            assertThat(DuckLakeConnectorMetadata(c).getTableStatistics(null, h)).isEmpty()
        } }
    }

    @Test
    fun taggedParquetDeletesWithNullPartialMaxAndEqualPathsReject() {
        native("CALL lake.set_option('write_deletion_vectors',false); CREATE TABLE lake.t(i INTEGER); " +
            "INSERT INTO lake.t VALUES(1),(2),(3),(4),(5)")
        native("DELETE FROM lake.t WHERE i=2")
        val earlier = snapshot()
        native("DELETE FROM lake.t WHERE i=4")
        val later = snapshot()
        native("CALL ducklake_flush_inlined_data('lake',table_name=>'t')")
        reader().use { c -> c.read {
            val h = handle(c)
            val file = c.getDataFiles(h.tableId, h.snapshotId).single()
            assertThat(file.deleteFileFormat).isEqualTo("parquet")
            assertThat(file.deleteFilePartialMax).isNull()
            val change = c.getDeletionsBetween(h.tableId, later, later).single { !it.fullFileDelete }
            assertThat(change.currentDeletePath).isEqualTo(change.previousDeletePath)
            val path = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DELETE, null, 10).single().resolvedPath
            assertThat(parquetSql("SELECT _ducklake_internal_snapshot_id FROM read_parquet('$path') ORDER BY pos"))
                .containsExactly(listOf(earlier.toString()), listOf(later.toString()))
            assertThatThrownBy { planner(c).planScan(null, ConnectorScanRequest.builder(h, emptyList()).build()) }
                .hasMessageContaining("1.1 delete-file scans are not supported")
        } }
    }

    @Test
    fun barePuffinVectorHasRealCrcButIsNotMisroutedAsParquet() {
        native("CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',true); " +
            "CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t FROM range(20); DELETE FROM lake.t WHERE i<3")
        reader().use { c -> c.read {
            val h = handle(c)
            val file = c.getDataFiles(h.tableId, h.snapshotId).single()
            assertThat(file.deleteFileFooterSize).isEqualTo(0)
            val path = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DELETE, null, 10).single().resolvedPath
            val bytes = Files.readAllBytes(Path.of(path))
            val length = ByteBuffer.wrap(bytes).int
            assertThat(bytes.size).isEqualTo(length + 8)
            assertThat(bytes.copyOfRange(4, 8)).containsExactly(-47, -45, 57, 100)
            assertThat(CRC32().apply { update(bytes, 4, length) }.value)
                .isEqualTo(Integer.toUnsignedLong(ByteBuffer.wrap(bytes, length + 4, 4).int))
            assertThatThrownBy { planner(c).planScan(null, ConnectorScanRequest.builder(h, emptyList()).build()) }
                .hasMessageContaining("Puffin vectors require a qualified decoder")
        } }
    }

    @Test
    fun cumulativePuffinEqualPathsStillHaveTwoSnapshotTaggedBlobsAndReject() {
        native("CALL lake.set_option('write_deletion_vectors',true); CREATE TABLE lake.t(i INTEGER); " +
            "INSERT INTO lake.t VALUES(1),(2),(3),(4)")
        native("DELETE FROM lake.t WHERE i=2")
        val earlier = snapshot()
        native("DELETE FROM lake.t WHERE i=4")
        val later = snapshot()
        native("CALL ducklake_flush_inlined_data('lake',table_name=>'t')")
        reader().use { c -> c.read {
            val h = handle(c)
            val path = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DELETE, null, 10).single().resolvedPath
            val bytes = Files.readAllBytes(Path.of(path))
            assertThat(String(bytes.copyOfRange(0, 4))).isEqualTo("PFA1")
            val size = ByteBuffer.wrap(bytes, bytes.size - 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val footer = com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes.copyOfRange(bytes.size - 12 - size, bytes.size - 12))
            assertThat(footer["blobs"].map { it["properties"]["ducklake-snapshot-id"].asText() })
                .containsExactly(earlier.toString(), later.toString())
            assertThat(footer["blobs"].map { it["properties"]["cardinality"].asText() }).containsExactly("1", "2")
            val change = c.getDeletionsBetween(h.tableId, later, later).single { !it.fullFileDelete }
            assertThat(change.currentDeletePath).isEqualTo(change.previousDeletePath)
            assertThatThrownBy { planner(c).planScan(null, ConnectorScanRequest.builder(h, emptyList()).build()) }
                .hasMessageContaining("Equal paths do not imply an empty deletion window")
        } }
    }

    @Test
    fun currentNativeFileFooterDoesNotEnableScansOrExactCount() {
        native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER,s VARCHAR,b BLOB); " +
            "INSERT INTO lake.t VALUES(1,NULL,from_hex('005CFF')),(2,'NULL',NULL)")
        reader().use { c ->
            val h = c.read { handle(c) }
            // Provider, not this test, owns the complete metadata session.
            assertThatThrownBy { planner(c).planScan(null,
                ConnectorScanRequest.builder(h, emptyList()).countPushdown(true).limit(1).build()) }
                .hasMessageContaining("1.1 DATA scans are not supported")
            val path = c.read { c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 2).single().resolvedPath }
            ParquetFileReader.open(LocalInputFile(Path.of(path))).use { p ->
                assertThat(p.footer.blocks.sumOf { it.rowCount }).isEqualTo(2)
                assertThat(p.footer.fileMetaData.schema.fields.map { it.id.intValue() }).containsExactly(1, 2, 3)
            }
        }
    }

    @Test
    fun ownedSessionBudgetsAbortAndQueryFailureRecoverWithoutPartialSuccess() {
        native("CALL lake.set_option('data_inlining_row_limit',0); CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)")
        reader().use { c ->
            val failure = IllegalStateException("caller page/path budget exhausted")
            assertThatThrownBy { c.read {
                c.currentSnapshotId
                val page = c.readHierarchy().listRetainedFiles(DucklakeFileKind.DATA, null, 1)
                assertThat(page).hasSize(1)
                throw failure // One total page/path allowed; cannot claim the traversal completed.
            } }.isSameAs(failure)
            val h = c.read { handle(c) }
            assertThatThrownBy { planner(c).planScan(null, ConnectorScanRequest.builder(h, emptyList()).build()) }
                .hasMessageContaining("1.1 DATA scans are not supported")
            assertThatThrownBy { c.currentSnapshotId }.hasMessageContaining("readSession")
        }
        DriverManager.getConnection("jdbc:duckdb:$database").use { c -> c.createStatement().use {
            it.execute("ALTER TABLE ducklake_data_file RENAME COLUMN file_format TO held_format")
        } }
        reader().use { c ->
            val h = c.read { handle(c) }
            assertThatThrownBy { planner(c).planScan(null, ConnectorScanRequest.builder(h, emptyList()).build()) }
                .hasMessageContaining("file_format")
            assertThat(c.read { c.currentSnapshotId }).isPositive()
        }
    }

    private fun parquetSql(sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:duckdb:").use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> buildList {
            while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
        } } }
    }
}
