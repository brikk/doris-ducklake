package dev.brikk.ducklake.doris.plugin

import io.minio.MakeBucketArgs
import io.minio.BucketExistsArgs
import io.minio.MinioClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Requires ONLY the isolated R07 compose; never borrows the Trino stack or shared lake. */
internal class DuckLakeR07LiveQualificationTest {
    private val suffix = UUID.randomUUID().toString().replace("-", "")

    private fun fe(): Connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:19387/", "root", "")

    @BeforeEach
    fun waitForRealBackendHeartbeat() {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            try {
                fe().use { c -> c.createStatement().use { s -> s.executeQuery("SHOW BACKENDS").use { r ->
                    if (r.next() && r.getBoolean("Alive") && r.getString("Version").contains("dd456ab7")) return
                } } }
            } catch (_: java.sql.SQLException) {
                // JDBC's initialization SELECT can fail before the first heartbeat.
                // This is bounded service readiness, not a swallowed scan failure.
            }
            Thread.sleep(250)
        }
        error("The isolated matched BE did not report a live heartbeat within 60 seconds")
    }

    private fun rows(c: Connection, sql: String): List<List<String?>> = c.createStatement().use { s ->
        s.executeQuery(sql).use { r -> buildList {
            while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
        } }
    }

    internal fun produce(version: String): Pair<String, String> {
        val database = "r07_${version.replace(".", "")}_$suffix"
        DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15487/r07", "r07", "r07-local-only").use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $database TEMPLATE template0") }
        }
        val warehouse = "s3://r07/$database/"
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres; INSTALL httpfs; LOAD httpfs")
            val extensions = mutableMapOf<String, String?>()
            s.executeQuery("SELECT extension_name,extension_version FROM duckdb_extensions() WHERE loaded ORDER BY 1").use { r ->
                while (r.next()) {
                    extensions[r.getString(1)] = r.getString(2)
                    println("NATIVE ${r.getString(1)} ${r.getString(2)}")
                }
            }
            assertThat(extensions["ducklake"]).isEqualTo("417a9117f5")
            assertThat(extensions["postgres_scanner"]).isEqualTo("c0f5a3c0c4")
            s.execute("CREATE SECRET (TYPE S3, KEY_ID 'r07local', SECRET 'r07-local-only', " +
                "ENDPOINT '127.0.0.1:19087', USE_SSL false, URL_STYLE 'path', REGION 'us-east-1')")
            s.execute("ATTACH 'ducklake:postgres:host=127.0.0.1 port=15487 dbname=$database user=r07 password=r07-local-only' " +
                "AS lake (DATA_PATH '$warehouse', DUCKLAKE_VERSION '$version', AUTOMATIC_MIGRATION FALSE)")
            s.execute("CALL lake.set_option('data_inlining_row_limit',0)")
            s.execute("CREATE TABLE lake.t(i INTEGER,s VARCHAR,b BLOB,d DATE)")
            s.execute("INSERT INTO lake.t VALUES(1,NULL,from_hex('005CFF'),DATE '2024-02-29')," +
                "(2,'NULL',from_hex('5C783431'),NULL),(3,'three',NULL,DATE '2000-01-01')")
            s.execute("ALTER TABLE lake.t RENAME COLUMN s TO renamed")
            s.execute("ALTER TABLE lake.t ADD COLUMN added INTEGER DEFAULT 42")
        } }
        println("RETAINED fixture version=$version database=$database warehouse=$warehouse")
        return database to warehouse
    }

    private fun register(c: Connection, database: String, warehouse: String, extra: String = ""): String {
        val name = "catalog_${UUID.randomUUID().toString().replace("-", "")}"
        c.createStatement().use { s -> s.execute("""
            CREATE CATALOG $name PROPERTIES (
              "type"="ducklake", "metadata.url"="jdbc:postgresql://postgres:5432/$database",
              "metadata.user"="r07", "metadata.password"="r07-local-only", "storage.warehouse"="$warehouse",
              "s3.endpoint"="http://minio:9000", "s3.access_key"="r07local", "s3.secret_key"="r07-local-only",
              "s3.region"="us-east-1", "use_path_style"="true" $extra)
        """.trimIndent()) }
        return name
    }

    @Test
    fun matchedFeBeActuallyScanCandidateLegacyFilesAndRejectFinal11WithoutSharedFilesystem() {
        MinioClient.builder().endpoint("http://127.0.0.1:19087").credentials("r07local", "r07-local-only").build().use {
            if (!it.bucketExists(BucketExistsArgs.builder().bucket("r07").build())) {
                it.makeBucket(MakeBucketArgs.builder().bucket("r07").build())
            }
        }
        fe().use { c ->
            val version = rows(c, "SHOW FRONTENDS")
            assertThat(version.toString()).contains("dd456ab7")
            val backends = rows(c, "SHOW BACKENDS")
            assertThat(backends.toString()).contains("dd456ab7")
            println("MATCHED FE $version BE $backends")
            val (legacyDb, legacyPath) = produce("1.0")
            val legacy = register(c, legacyDb, legacyPath)
            val scanned = rows(c, "SELECT i,renamed,HEX(b),CAST(d AS STRING),added FROM $legacy.main.t ORDER BY i")
            assertThat(scanned).containsExactly(
                listOf("1", null, "005CFF", "2024-02-29", "42"),
                listOf("2", "NULL", "5C783431", null, "42"),
                listOf("3", "three", null, "2000-01-01", "42"),
            )
            println("ACTUAL BE SCAN $scanned")
            assertThat(rows(c, "SELECT COUNT(*),COUNT(renamed) FROM $legacy.main.t")).containsExactly(listOf("3", "2"))
            assertThat(rows(c, "SELECT i FROM $legacy.main.t WHERE added=42 ORDER BY i"))
                .containsExactly(listOf("1"), listOf("2"), listOf("3"))
            c.createStatement().use { s ->
                s.queryTimeout = 1
                assertThatThrownBy { s.executeQuery("SELECT SLEEP(10) FROM $legacy.main.t") }
                    .isInstanceOf(java.sql.SQLTimeoutException::class.java)
            }
            assertThat(rows(c, "SELECT i FROM $legacy.main.t ORDER BY i"))
                .containsExactly(listOf("1"), listOf("2"), listOf("3"))
            println("ACTUAL S3-backed query cancelled at SLEEP; next scan recovered (not a storage-read/lease test)")
            val (finalDb, finalPath) = produce("1.1")
            // Admission is now checked by the actual pre-create SPI, before
            // ExternalCatalog's database-listing catch can mask the diagnostic.
            assertThatThrownBy { register(c, finalDb, finalPath) }
                .isInstanceOf(java.sql.SQLException::class.java)
                .hasMessageContaining("1.1 reads are not qualified")
            println("FINAL11 default-off admission diagnostic reaches CREATE CATALOG")
            // Failed 1.1 planning must not poison another catalog's pool or FE query state.
            assertThat(rows(c, "SELECT i FROM $legacy.main.t ORDER BY i"))
                .containsExactly(listOf("1"), listOf("2"), listOf("3"))
        }
    }

    internal fun mutate(database: String, warehouse: String, sql: String) {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres; INSTALL httpfs; LOAD httpfs")
            s.execute("CREATE SECRET (TYPE S3, KEY_ID 'r07local', SECRET 'r07-local-only', ENDPOINT '127.0.0.1:19087', USE_SSL false, URL_STYLE 'path', REGION 'us-east-1')")
            s.execute("ATTACH 'ducklake:postgres:host=127.0.0.1 port=15487 dbname=$database user=r07 password=r07-local-only' " +
                "AS lake (DATA_PATH '$warehouse', DUCKLAKE_VERSION '1.1', AUTOMATIC_MIGRATION FALSE)")
            s.execute(sql)
        } }
    }

    @Test
    fun optIn11ActuallyScansDefaultsFieldIdsAndBothNativeDeleteFormats() {
        ensureBucket()
        val (db, path) = produce("1.1")
        fe().use { c ->
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\"")
            assertThat(rows(c, "SELECT i,renamed,HEX(b),CAST(d AS STRING),added FROM $catalog.main.t ORDER BY i"))
                .containsExactly(listOf("1", null, "005CFF", "2024-02-29", "42"),
                    listOf("2", "NULL", "5C783431", null, "42"), listOf("3", "three", null, "2000-01-01", "42"))
            assertThat(rows(c, "SELECT COUNT(*),COUNT(renamed) FROM $catalog.main.t")).containsExactly(listOf("3", "2"))
            assertThat(rows(c, "SELECT i FROM $catalog.main.t WHERE added=42 ORDER BY i"))
                .containsExactly(listOf("1"), listOf("2"), listOf("3"))
            val birth = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15487/$db", "r07", "r07-local-only").use { pg ->
                rows(pg, "SELECT MIN(d.begin_snapshot) FROM ducklake_data_file d JOIN ducklake_table t USING(table_id) WHERE t.table_name='t'")
                    .single().single()!!.toLong()
            }
            val historical = register(c, db, path, ", \"experimental.final11.reads\"=\"true\", \"final11.history-window\"=\"16\"")
            assertThat(rows(c, "SELECT i,s,HEX(b),CAST(d AS STRING) FROM $historical.main.t FOR VERSION AS OF $birth ORDER BY i"))
                .containsExactly(listOf("1", null, "005CFF", "2024-02-29"), listOf("2", "NULL", "5C783431", null),
                    listOf("3", "three", null, "2000-01-01"))
            assertThatThrownBy { c.createStatement().use { it.execute("INSERT INTO $catalog.main.t(i) VALUES(9)") } }
                .hasMessageContaining("read-only")
            mutate(db, path, "CALL lake.set_option('write_deletion_vectors',false); DELETE FROM lake.t WHERE i=2")
            assertThat(rows(c, "SELECT i FROM $catalog.main.t ORDER BY i")).containsExactly(listOf("1"), listOf("3"))
            mutate(db, path, "CALL lake.set_option('write_deletion_vectors',true); DELETE FROM lake.t WHERE i=3")
            assertThat(rows(c, "SELECT i FROM $catalog.main.t ORDER BY i")).containsExactly(listOf("1"))
            assertThat(rows(c, "SELECT COUNT(*) FROM $catalog.main.t")).containsExactly(listOf("1"))
            println("POSITIVE FINAL11 FE/BE: scalar/default/rename/NULL/BLOB/date, Parquet deletes, bare Puffin deletes")
        }
    }

    @Test
    fun optIn11ScansTaggedParquetAndCumulativePuffinFromNativeInlineFlush() {
        ensureBucket()
        val (db, path) = produce("1.1")
        // Disposable native producers only: the connector never materializes inline data.
        mutate(db, path, "CALL lake.set_option('data_inlining_row_limit',100); CALL lake.set_option('write_deletion_vectors',false); " +
            "CREATE TABLE lake.tagged(i INTEGER); INSERT INTO lake.tagged VALUES(1),(2),(3),(4),(5)")
        mutate(db, path, "DELETE FROM lake.tagged WHERE i=2")
        val firstParquet = snapshot(db)
        mutate(db, path, "DELETE FROM lake.tagged WHERE i=4")
        mutate(db, path, "CALL ducklake_flush_inlined_data('lake',table_name=>'tagged')")
        mutate(db, path, "CALL lake.set_option('write_deletion_vectors',true); CREATE TABLE lake.vectors(i INTEGER); " +
            "INSERT INTO lake.vectors VALUES(1),(2),(3),(4),(5)")
        mutate(db, path, "DELETE FROM lake.vectors WHERE i=2")
        val firstVector = snapshot(db)
        mutate(db, path, "DELETE FROM lake.vectors WHERE i=4")
        mutate(db, path, "CALL ducklake_flush_inlined_data('lake',table_name=>'vectors')")
        fe().use { c ->
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\", \"final11.history-window\"=\"${window(db, firstParquet)}\"")
            for (table in listOf("tagged", "vectors")) {
                assertThat(rows(c, "SELECT i FROM $catalog.main.$table ORDER BY i"))
                    .containsExactly(listOf("1"), listOf("3"), listOf("5"))
                assertThat(rows(c, "SELECT COUNT(*) FROM $catalog.main.$table")).containsExactly(listOf("3"))
            }
            assertThat(rows(c, "SELECT a.i FROM $catalog.main.tagged a JOIN $catalog.main.vectors b ON a.i=b.i ORDER BY a.i"))
                .containsExactly(listOf("1"), listOf("3"), listOf("5"))
            assertThat(rows(c, "SELECT i FROM $catalog.main.vectors FOR VERSION AS OF $firstVector ORDER BY i"))
                .containsExactly(listOf("1"), listOf("3"), listOf("4"), listOf("5"))
            assertThat(rows(c, "SELECT COUNT(*) FROM $catalog.main.vectors FOR VERSION AS OF $firstVector"))
                .containsExactly(listOf("4"))
            assertThat(rows(c, "SELECT old.i FROM $catalog.main.vectors FOR VERSION AS OF $firstVector old " +
                "LEFT ANTI JOIN $catalog.main.vectors cur ON old.i=cur.i ORDER BY old.i")).containsExactly(listOf("4"))
            assertThatThrownBy { rows(c, "SELECT * FROM $catalog.main.tagged FOR VERSION AS OF $firstParquet") }
                .hasMessageContaining("per-position snapshot filtering")
            println("POSITIVE FINAL11 FE/BE: current tagged Parquet; current/historical cumulative Puffin; same-path vectors and same-statement join")
        }
        assertNativeVectorWireSlices(db, path, firstVector)
    }

    private fun assertNativeVectorWireSlices(db: String, path: String, historical: Long) {
        val props = hostProperties(db, path) + ("final11.history-window" to window(db, historical).toString())
        val context = dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext("wire", 811L)
        DuckLakeConnectorProvider().create(props, context).use { connector ->
            val session = session(Scope(), "native-wire-slices")
            val handle = connector.getMetadata(session).getTableHandle(session, "main", "vectors").orElseThrow() as DuckLakeTableHandle
            fun descriptor(handle: DuckLakeTableHandle): org.apache.doris.thrift.TIcebergDeleteFileDesc {
                val request = org.apache.doris.connector.spi.scan.ConnectorScanRequest.builder(handle, emptyList()).build()
                val range = connector.scanPlanProvider.planScan(session, request).single()
                val sent = org.apache.doris.thrift.TTableFormatFileDesc()
                range.populateRangeParams(sent, org.apache.doris.thrift.TFileRangeDesc())
                val received = org.apache.doris.thrift.TTableFormatFileDesc()
                org.apache.thrift.TDeserializer().deserialize(received, org.apache.thrift.TSerializer().serialize(sent))
                assertThat(received.icebergParams.isSetContent).isFalse()
                return received.icebergParams.deleteFiles.single()
            }
            val current = descriptor(handle)
            val previous = descriptor(handle.copy(snapshotId = historical))
            assertThat(current.path).isEqualTo(previous.path)
            assertThat(current.contentOffset).isNotEqualTo(previous.contentOffset)
            assertThat(current.isSetContent && previous.isSetContent).isTrue()
            assertThat(current.content).isEqualTo(3)
            assertThat(previous.content).isEqualTo(3)
            println("NATIVE WIRE same path=${current.path}; prior slice=${previous.contentOffset}+${previous.contentSizeInBytes}; current=${current.contentOffset}+${current.contentSizeInBytes}")
        }
        DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15487/$db", "r07", "r07-local-only").use { pg ->
            assertThat(rows(pg, "SELECT CASE WHEN f.partial_max IS NULL THEN 1 ELSE 0 END FROM ducklake_delete_file f JOIN ducklake_table t USING(table_id) " +
                "WHERE t.table_name='tagged' AND f.end_snapshot IS NULL AND t.end_snapshot IS NULL")).containsExactly(listOf("1"))
        }
    }

    internal fun ensureBucket() {
        MinioClient.builder().endpoint("http://127.0.0.1:19087").credentials("r07local", "r07-local-only").build().use {
            if (!it.bucketExists(BucketExistsArgs.builder().bucket("r07").build())) it.makeBucket(MakeBucketArgs.builder().bucket("r07").build())
        }
    }

    @Test
    fun current11MergeUsesEmbeddedAbsoluteIdsAndMultiplePhysicalRowGroups() {
        ensureBucket()
        val (db, path) = produce("1.1")
        mutate(db, path, "CALL lake.set_option('data_inlining_row_limit',2); CREATE TABLE lake.g(i INTEGER); INSERT INTO lake.g VALUES(1),(2),(3)")
        val firstFile = snapshot(db)
        mutate(db, path, "INSERT INTO lake.g VALUES(4)")
        mutate(db, path, "INSERT INTO lake.g VALUES(5),(6),(7)")
        mutate(db, path, "CALL ducklake_merge_adjacent_files('lake','g')")
        mutate(db, path, "DELETE FROM lake.g WHERE i=4")
        val beforeVector = snapshot(db)
        mutate(db, path, "CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',true); DELETE FROM lake.g WHERE i=6")
        // Qualify the SAME historical coordinates before adding unrelated large-table fixtures.
        // The total page ceiling includes terminal pages for every retained frame/table.
        fe().use { c ->
            val historical = register(c, db, path, ", \"experimental.final11.reads\"=\"true\", \"final11.history-window\"=\"${window(db, firstFile)}\"")
            assertThat(rows(c, "SELECT i FROM $historical.main.g FOR VERSION AS OF $beforeVector ORDER BY i"))
                .containsExactly(listOf("1"), listOf("2"), listOf("3"), listOf("5"), listOf("6"), listOf("7"))
            assertThatThrownBy { rows(c, "SELECT i FROM $historical.main.g FOR VERSION AS OF $firstFile") }
                .hasMessageContaining("per-row snapshot filtering")
        }
        mutate(db, path, "CREATE TABLE lake.many_groups(i INTEGER); INSERT INTO lake.many_groups SELECT range::INTEGER FROM range(300000)")
        val escapedPartition = "é/%?".repeat(2)
        mutate(db, path, "CREATE TABLE lake.part(i INTEGER,p VARCHAR); ALTER TABLE lake.part SET PARTITIONED BY(p); " +
            "INSERT INTO lake.part VALUES(1,'$escapedPartition'),(2,NULL),(3,'short')")
        DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15487/$db", "r07", "r07-local-only").use { c ->
            assertThat(rows(c, "SELECT d.row_id_start FROM ducklake_data_file d JOIN ducklake_table t USING(table_id) " +
                "WHERE t.table_name='g' AND t.end_snapshot IS NULL AND d.end_snapshot IS NULL")).containsExactly(listOf(null))
            assertThat(rows(c, "SELECT SUM(d.row_group_count) FROM ducklake_data_file d JOIN ducklake_table t USING(table_id) " +
                "WHERE t.table_name='many_groups' AND t.end_snapshot IS NULL AND d.end_snapshot IS NULL").single().single()!!.toLong())
                .isGreaterThan(1)
        }
        fe().use { c ->
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\"")
            assertThat(rows(c, "SELECT i FROM $catalog.main.g ORDER BY i"))
                .containsExactly(listOf("1"), listOf("2"), listOf("3"), listOf("5"), listOf("7"))
            assertThat(rows(c, "SELECT COUNT(*) FROM $catalog.main.g")).containsExactly(listOf("5"))
            assertThat(rows(c, "SELECT COUNT(*),MIN(i),MAX(i),SUM(i) FROM $catalog.main.many_groups"))
                .containsExactly(listOf("300000", "0", "299999", "44999850000"))
            assertThat(rows(c, "SELECT i,p FROM $catalog.main.part ORDER BY i"))
                .containsExactly(listOf("1", escapedPartition), listOf("2", null), listOf("3", "short"))
            assertThat(rows(c, "SELECT i FROM $catalog.main.part WHERE p='$escapedPartition'"))
                .containsExactly(listOf("1"))
            println("POSITIVE FINAL11: NULL contiguous start, embedded absolute ID gap, physical-position DV, multiple real row groups")
        }
    }

    private fun snapshot(db: String): Long = DriverManager.getConnection(
        "jdbc:postgresql://127.0.0.1:15487/$db", "r07", "r07-local-only",
    ).use { rows(it, "SELECT MAX(snapshot_id) FROM ducklake_snapshot").single().single()!!.toLong() }

    private fun window(db: String, earliest: Long): Int = (snapshot(db) - earliest + 1).toInt().also {
        require(it in 1..16) { "Fixture exceeds the unchanged bounded history window" }
    }

    @Test
    fun losslessScalarTypesWideningAndReplacementIdsAreActuallyReadByBe() {
        ensureBucket()
        val (db, path) = produce("1.1")
        mutate(db, path, "CREATE TABLE lake.scalars(b BOOLEAN,i8 TINYINT,i16 SMALLINT,i64 BIGINT,f REAL,d DOUBLE,n DECIMAL(12,2)," +
            "ts TIMESTAMP,secs TIMESTAMP_S,millis TIMESTAMP_MS); " +
            "INSERT INTO lake.scalars VALUES(true,-128,32767,9223372036854775807,1.25,2.5,1234567890.12," +
            "'2024-02-29 12:34:56.123456'::TIMESTAMP,'2024-02-29 12:34:56'::TIMESTAMP_S,'2024-02-29 12:34:56.123'::TIMESTAMP_MS)," +
            "(false,127,-32768,-9223372036854775808,-1.5,-2.25,-0.01,NULL,NULL,NULL)," +
            "(NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)")
        mutate(db, path, "ALTER TABLE lake.scalars ADD COLUMN flag BOOLEAN DEFAULT true; ALTER TABLE lake.scalars ADD COLUMN dflt DECIMAL(5,2) DEFAULT 12.34; " +
            "ALTER TABLE lake.scalars ADD COLUMN sd TIMESTAMP_S DEFAULT TIMESTAMP_S '2024-02-29 12:34:56'; " +
            "ALTER TABLE lake.scalars ADD COLUMN md TIMESTAMP_MS DEFAULT TIMESTAMP_MS '2024-02-29 12:34:56.123'")
        mutate(db, path, "CREATE TABLE lake.widen(i INTEGER,f REAL); INSERT INTO lake.widen VALUES(123,1.2345678); " +
            "ALTER TABLE lake.widen ALTER COLUMN i TYPE BIGINT; ALTER TABLE lake.widen ALTER COLUMN f TYPE DOUBLE")
        mutate(db, path, "CREATE TABLE lake.replace_id(i INTEGER,k INTEGER); INSERT INTO lake.replace_id VALUES(1,888); " +
            "ALTER TABLE lake.replace_id DROP COLUMN k; ALTER TABLE lake.replace_id ADD COLUMN k INTEGER DEFAULT 42")
        mutate(db, path, "CREATE TABLE lake.nano(v TIMESTAMP_NS); INSERT INTO lake.nano VALUES('2024-02-29 12:34:56.123456789'::TIMESTAMP_NS)")
        fe().use { c ->
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\"")
            val pattern = "%Y-%m-%d %H:%i:%s.%f"
            assertThat(rows(c, "SELECT CAST(b AS INT),i8,i16,i64,CAST(f AS STRING),CAST(d AS STRING),CAST(n AS STRING)," +
                "DATE_FORMAT(ts,'$pattern'),DATE_FORMAT(secs,'$pattern'),DATE_FORMAT(millis,'$pattern')," +
                "CAST(flag AS INT),CAST(dflt AS STRING),DATE_FORMAT(sd,'$pattern'),DATE_FORMAT(md,'$pattern') FROM $catalog.main.scalars WHERE b"))
                .containsExactly(listOf("1", "-128", "32767", "9223372036854775807", "1.25", "2.5", "1234567890.12",
                    "2024-02-29 12:34:56.123456", "2024-02-29 12:34:56.000000", "2024-02-29 12:34:56.123000", "1", "12.34",
                    "2024-02-29 12:34:56.000000", "2024-02-29 12:34:56.123000"))
            assertThat(rows(c, "SELECT COUNT(*),COUNT(b),COUNT(ts),SUM(CAST(flag AS INT)) FROM $catalog.main.scalars"))
                .containsExactly(listOf("3", "2", "1", "3"))
            assertThat(rows(c, "SELECT i8,i16,i64 FROM $catalog.main.scalars WHERE NOT b"))
                .containsExactly(listOf("127", "-32768", "-9223372036854775808"))
            assertThat(rows(c, "SELECT i,CAST(f=CAST('1.2345677614212036' AS DOUBLE) AS INT) FROM $catalog.main.widen"))
                .containsExactly(listOf("123", "1"))
            assertThat(rows(c, "SELECT i,k FROM $catalog.main.replace_id")).containsExactly(listOf("1", "42"))
            assertThatThrownBy { rows(c, "SELECT * FROM $catalog.main.nano") }.hasMessageContaining("not qualified")
            println("POSITIVE FINAL11: Boolean, integer endpoints, float/double, decimal/default, microsecond timestamps, exact widening, replacement IDs; ns rejects")
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(150)
    fun metadataAndWarehouseNetworkFailuresAbortAndFreshStatementsRecover() {
        ensureBucket()
        val (db, path) = produce("1.1")
        fe().use { c ->
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\"")
            val sql = "SELECT i FROM $catalog.main.t ORDER BY i"
            assertThat(rows(c, sql)).containsExactly(listOf("1"), listOf("2"), listOf("3"))
            for (service in listOf("postgres", "minio")) {
                control("pause", service)
                try {
                    val started = System.nanoTime()
                    val failure = org.assertj.core.api.Assertions.catchThrowable {
                        // Exceed Hikari's 30s acquisition bound as well as the
                        // per-RPC timeouts: observe the planning failure rather
                        // than merely the driver's separate cancel deadline.
                        c.createStatement().use { s -> s.queryTimeout = 50; s.executeQuery(sql).use { r -> while (r.next()) r.getString(1) } }
                    }
                    assertThat(failure).isInstanceOf(java.sql.SQLException::class.java)
                    assertThat(failure).isNotInstanceOf(java.sql.SQLTimeoutException::class.java)
                    assertThat(failure.message!!.substringAfter("detailMessage = ").trim()).isNotBlank()
                    assertThat(failure.message).doesNotContain("UndeclaredThrowableException")
                    println("$service outage propagated after ${(System.nanoTime() - started) / 1_000_000}ms: ${failure.message}")
                } finally { control("unpause", service) }
                assertThat(rows(c, sql)).containsExactly(listOf("1"), listOf("2"), listOf("3"))
            }
            c.createStatement().use { s ->
                s.queryTimeout = 1
                assertThatThrownBy { s.executeQuery("SELECT SLEEP(10) FROM $catalog.main.t") }
                    .isInstanceOf(java.sql.SQLTimeoutException::class.java)
            }
            assertThat(rows(c, sql)).containsExactly(listOf("1"), listOf("2"), listOf("3"))
        }
        DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15487/$db", "r07", "r07-local-only").use { pg ->
            assertThat(rows(pg, "SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction'"))
                .containsExactly(listOf("0"))
        }
        println("Final11 SQL metadata/warehouse outage, statement cancellation and subsequent scan recovery PASS; not a physical-file lease test")
    }

    private fun control(action: String, service: String) {
        require(action in setOf("pause", "unpause") && service in setOf("postgres", "minio"))
        val process = ProcessBuilder("docker", action, "doris-r07-qualification-$service-1").redirectErrorStream(true).start()
        if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Isolated $service $action command timed out")
        }
        check(process.exitValue() == 0) { String(process.inputStream.readNBytes(4096)) }
    }

    @Test
    fun mixedInlineFileAndUnknownLiveCountsNeverBecomePartialResultsOrGuesses() {
        ensureBucket()
        val (db, path) = produce("1.1")
        mutate(db, path, "CALL lake.set_option('data_inlining_row_limit',2); CREATE TABLE lake.mixed(i INTEGER); BEGIN; " +
            "INSERT INTO lake.mixed VALUES(1),(2); INSERT INTO lake.mixed VALUES(3),(4),(5),(6),(7); COMMIT")
        fe().use { c ->
            val catalog = register(c, db, path,
                ", \"experimental.final11.reads\"=\"true\", \"experimental.inlined.reads\"=\"true\"")
            assertThatThrownBy { rows(c, "SELECT i FROM $catalog.main.mixed") }.hasMessageContaining("distributed payload channel")
            assertThatThrownBy { rows(c, "SELECT COUNT(*) FROM $catalog.main.mixed") }.hasMessageContaining("distributed payload channel")
        }
        mutate(db, path, "CALL lake.set_option('data_inlining_row_limit',0); CALL lake.set_option('write_deletion_vectors',true); DELETE FROM lake.t WHERE i=2")
        DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15487/$db", "r07", "r07-local-only").use { pg ->
            pg.createStatement().use { it.execute("UPDATE ducklake_delete_file SET delete_count=NULL") }
        }
        fe().use { c ->
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\"")
            assertThat(rows(c, "SELECT COUNT(*) FROM $catalog.main.t")).containsExactly(listOf("2"))
            assertThat(rows(c, "SELECT i FROM $catalog.main.t ORDER BY i")).containsExactly(listOf("1"), listOf("3"))
        }
        println("Mixed inline/file refuses even with old dev flag; NULL delete_count is handled by actual BE scan")
    }

    private class Scope : org.apache.doris.connector.spi.ConnectorStatementScope {
        val values = java.util.concurrent.ConcurrentHashMap<String, Any>()
        var plans = 0
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> computeIfAbsent(key: String, loader: java.util.function.Supplier<T>): T =
            values.computeIfAbsent(key) {
                if (key.startsWith("ducklake:final11-plan:")) plans++
                loader.get() as Any
            } as T
    }

    private fun session(scope: Scope, query: String): org.apache.doris.connector.spi.ConnectorSession =
        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(org.apache.doris.connector.spi.ConnectorSession::class.java)) { _, method, _ -> when (method.name) {
                "getStatementScope" -> scope
                "getCatalogId" -> 811L
                "getQueryId" -> query
                "getUser" -> "r07"
                else -> error("Unexpected session method ${method.name}")
            } } as org.apache.doris.connector.spi.ConnectorSession

    private fun hostProperties(db: String, path: String) = mapOf(
        "metadata.url" to "jdbc:postgresql://127.0.0.1:15487/$db", "metadata.user" to "r07", "metadata.password" to "r07-local-only",
        "storage.warehouse" to path, "s3.endpoint" to "http://127.0.0.1:19087", "s3.access_key" to "r07local",
        "s3.secret_key" to "r07-local-only", "s3.region" to "us-east-1", "experimental.final11.reads" to "true",
    )

    @Test
    @org.junit.jupiter.api.Timeout(90)
    fun controlledProductionCaptureInterruptsLockedPostgresAndOneConnectionRecovers() {
        ensureBucket()
        val (db, path) = produce("1.1")
        val props = hostProperties(db, path)
        val context = dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext("controlled", 811L)
        val config = dev.brikk.ducklake.catalog.DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = props.getValue("metadata.url")
            catalogDatabaseUser = "r07"
            catalogDatabasePassword = "r07-local-only"
            dataPath = path
            maxCatalogConnections = 1
            enableFinal11Reads = true
        }
        dev.brikk.ducklake.catalog.JdbcDucklakeCatalog(config).use { catalog ->
            DuckLakeReadObjects(props, context.httpSecurityHook).use { objects ->
                val original = DuckLakeFinal11Plan.capture(catalog, props, objects).invoke("getCurrentSnapshotId", emptyArray())
                for (explicit in listOf(true, false)) {
                    val seconds = if (explicit) 20 else 3
                    val control = dev.brikk.ducklake.catalog.DucklakeReadSessionControl(seconds)
                    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    pg(db).use { lock ->
                        lock.autoCommit = false
                        lock.createStatement().use { it.execute("LOCK TABLE ducklake_snapshot IN ACCESS EXCLUSIVE MODE") }
                        try {
                            val started = System.nanoTime()
                            val future = executor.submit<Throwable?> {
                                org.assertj.core.api.Assertions.catchThrowable {
                                    DuckLakeFinal11Plan.capture(catalog, props + ("final11.max-planning-millis" to "${seconds * 1000}"), objects, control)
                                }
                            }
                            awaitLockedMetadata(db)
                            if (explicit) assertThat(control.cancel()).isTrue()
                            // The lock is STILL held: success cannot be attributed to fixture unlock.
                            val failure = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                            assertThat(failure).isNotNull()
                            val driver = generateSequence(failure) { it.cause }.filterIsInstance<org.postgresql.util.PSQLException>().first()
                            assertThat(driver.sqlState).isEqualTo("57014")
                            assertThat(driver.message).contains("canceling statement")
                            println("CONTROLLED production capture explicit=$explicit stopped BEFORE unlock after ${(System.nanoTime() - started) / 1_000_000}ms; cause=$driver")
                        } finally {
                            lock.rollback()
                            executor.shutdownNow()
                            check(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                    }
                    // A completed control must not cancel the next borrower of the sole connection.
                    assertThat(control.cancel()).isFalse()
                    val fresh = DuckLakeFinal11Plan.capture(catalog, props, objects)
                    assertThat(fresh.invoke("getCurrentSnapshotId", emptyArray())).isEqualTo(original)
                }
            }
        }
    }

    private fun pg(db: String): Connection = DriverManager.getConnection(
        "jdbc:postgresql://127.0.0.1:15487/$db", "r07", "r07-local-only",
    )

    private fun awaitLockedMetadata(db: String) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        pg(db).use { observer ->
            while (System.nanoTime() < deadline) {
                val waiting = rows(observer, "SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() " +
                    "AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%ducklake_snapshot%' AND query NOT LIKE 'LOCK TABLE%'")
                if (waiting == listOf(listOf("1"))) return
                Thread.sleep(25)
            }
        }
        error("No actual catalog metadata statement observed waiting on fixture lock")
    }

    @Test
    @org.junit.jupiter.api.Timeout(90)
    fun adapterMemoizesLockedStatementTimeoutAndFeReportsDriverCauseBeforeUnlock() {
        ensureBucket()
        val (db, path) = produce("1.1")
        val props = hostProperties(db, path) + ("final11.max-planning-millis" to "3000")
        val context = dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext("deadline", 811L)
        DuckLakeConnectorProvider().create(props, context).use { connector ->
            val scope = Scope()
            val session = session(scope, "locked-metadata")
            val metadata = connector.getMetadata(session)
            pg(db).use { lock ->
                lock.autoCommit = false
                lock.createStatement().use { it.execute("LOCK TABLE ducklake_snapshot IN ACCESS EXCLUSIVE MODE") }
                try {
                    val failure = org.assertj.core.api.Assertions.catchThrowable { metadata.getTableHandle(session, "main", "t") }
                    assertThat(generateSequence(failure) { it.cause }.filterIsInstance<org.postgresql.util.PSQLException>().first().sqlState)
                        .isEqualTo("57014")
                    assertThat(org.assertj.core.api.Assertions.catchThrowable { connector.getMetadata(session).listDatabaseNames(session) })
                        .isSameAs(failure)
                    assertThat(scope.plans).isEqualTo(1)
                    println("ADAPTER locked metadata timeout retains driver cause; same statement memoizes original failure BEFORE unlock: $failure")
                } finally { lock.rollback() }
            }
            val next = session(Scope(), "recovered-metadata")
            assertThat(connector.getMetadata(next).getTableHandle(next, "main", "t")).isPresent()
        }
        fe().use { c ->
            val name = register(c, db, path, ", \"experimental.final11.reads\"=\"true\", \"final11.max-planning-millis\"=\"3000\"")
            assertThat(rows(c, "SELECT i FROM $name.main.t ORDER BY i")).containsExactly(listOf("1"), listOf("2"), listOf("3"))
            pg(db).use { lock ->
                lock.autoCommit = false
                lock.createStatement().use { it.execute("LOCK TABLE ducklake_snapshot IN ACCESS EXCLUSIVE MODE") }
                try {
                    val start = System.nanoTime()
                    val failure = org.assertj.core.api.Assertions.catchThrowable { rows(c, "SELECT i FROM $name.main.t ORDER BY i") }
                    assertThat(failure).isInstanceOf(java.sql.SQLException::class.java)
                        .hasMessageContaining("canceling statement").hasMessageNotContaining("UndeclaredThrowableException")
                    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(9000)
                    println("FE controlled metadata SQL stopped BEFORE unlock: ${failure.message}")
                } finally { lock.rollback() }
            }
            assertThat(rows(c, "SELECT i FROM $name.main.t ORDER BY i")).containsExactly(listOf("1"), listOf("2"), listOf("3"))
        }
        pg(db).use { c ->
            assertThat(rows(c, "SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction'"))
                .containsExactly(listOf("0"))
        }
    }

    @Test
    fun oneOwnedStatementPlanSurvivesPublicationAndBudgetFailuresAreMemoized() {
        ensureBucket()
        val (db, path) = produce("1.1")
        val context = dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext("r07", 811L)
        DuckLakeConnectorProvider().create(hostProperties(db, path), context).use { connector ->
            val scope = Scope()
            // Choose colliding ConcurrentHashMap bins: a premature nested compute
            // inside the engine's metadata loader would throw recursive-update.
            fun bin(s: String): Int = (s.hashCode() xor (s.hashCode() ushr 16)) and 15
            val query = (0..1000).map { "capture-$it" }.first {
                bin("ducklake:final11-plan:811:$it:r07") == bin("metadata:811")
            }
            val session = session(scope, query)
            val metadata = scope.getOrCreateMetadata("metadata:811") { connector.getMetadata(session) }
            val before = metadata.getTableHandle(session, "main", "t").orElseThrow() as DuckLakeTableHandle
            val request = org.apache.doris.connector.spi.scan.ConnectorScanRequest.builder(before, emptyList()).build()
            val ranges = connector.scanPlanProvider.planScan(session, request)
            mutate(db, path, "INSERT INTO lake.t(i) VALUES(99)")
            val same = connector.getMetadata(session).getTableHandle(session, "main", "t").orElseThrow() as DuckLakeTableHandle
            assertThat(same.snapshotId).isEqualTo(before.snapshotId)
            assertThat(connector.scanPlanProvider.planScan(session, request).map { it.path }).isEqualTo(ranges.map { it.path })
            assertThat(scope.plans).isEqualTo(1)
            val next = session(Scope(), "next-query")
            val refreshed = connector.getMetadata(next).getTableHandle(next, "main", "t").orElseThrow() as DuckLakeTableHandle
            assertThat(refreshed.snapshotId).isGreaterThan(before.snapshotId)
        }
        DuckLakeConnectorProvider().create(hostProperties(db, path) + ("final11.max-planning-pages" to "1"), context).use { connector ->
            val scope = Scope()
            val session = session(scope, "budget-failure")
            val first = org.assertj.core.api.Assertions.catchThrowable { connector.getMetadata(session).getTableHandle(session, "main", "t") }
            assertThat(first).hasMessageContaining("page budget")
            val again = org.assertj.core.api.Assertions.catchThrowable { connector.getMetadata(session).listDatabaseNames(session) }
            assertThat(again).isSameAs(first)
            assertThat(scope.plans).isEqualTo(1)
        }
        DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15487/$db", "r07", "r07-local-only").use { c ->
            assertThat(rows(c, "SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND state='idle in transaction'"))
                .containsExactly(listOf("0"))
        }
        println("Owned complete statement capture, publication isolation, next-query refresh, memoized budget abort and transaction release PASS")
    }

    @Test
    fun feQueryTimeoutCancelsAndNextQueryRecovers() {
        fe().use { c ->
            c.createStatement().use { s ->
                s.queryTimeout = 1
                assertThatThrownBy { s.executeQuery("SELECT SLEEP(10)") }
                    .isInstanceOf(java.sql.SQLTimeoutException::class.java)
            }
            assertThat(rows(c, "SELECT 1")).containsExactly(listOf("1"))
            println("FE JDBC query-timeout cancellation/recovery PASS; not metadata-session or file-lease recovery")
        }
    }

    @Test
    fun decodedOldFileDefaultsPreserveHistoryReplacementIdentityAndStoredNullsInBe() {
        ensureBucket()
        val (db, path) = produce("1.1")
        mutate(db, path, "CREATE TABLE lake.evolved(i INTEGER,k INTEGER); INSERT INTO lake.evolved VALUES(1,888); " +
            "ALTER TABLE lake.evolved DROP COLUMN k; ALTER TABLE lake.evolved ADD COLUMN k INTEGER DEFAULT 42; " +
            "ALTER TABLE lake.evolved ADD COLUMN s VARCHAR DEFAULT 'NULL'; " +
            "ALTER TABLE lake.evolved ADD COLUMN sn VARCHAR DEFAULT NULL; " +
            "ALTER TABLE lake.evolved ADD COLUMN b BLOB DEFAULT '\\x00\\x5C\\xFF'::BLOB; " +
            "ALTER TABLE lake.evolved ADD COLUMN d DATE DEFAULT DATE '2024-02-29'; " +
            "ALTER TABLE lake.evolved ADD COLUMN ts TIMESTAMP DEFAULT TIMESTAMP '1969-12-31 23:59:59.123456'; " +
            "ALTER TABLE lake.evolved ADD COLUMN z DECIMAL(20,3) DEFAULT 12345678901234567.125; " +
            "ALTER TABLE lake.evolved ADD COLUMN q VARCHAR DEFAULT '42''); DROP TABLE ducklake_snapshot; --'; " +
            "ALTER TABLE lake.evolved ADD COLUMN slash VARCHAR DEFAULT '\\x41é'")
        val before = snapshot(db)
        mutate(db, path, "ALTER TABLE lake.evolved RENAME COLUMN k TO renamed; " +
            "ALTER TABLE lake.evolved ALTER COLUMN renamed TYPE BIGINT; " +
            "ALTER TABLE lake.evolved ALTER COLUMN renamed SET DEFAULT 77; " +
            "ALTER TABLE lake.evolved ALTER COLUMN s SET DEFAULT 'later'; " +
            "INSERT INTO lake.evolved VALUES(2,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL); " +
            "INSERT INTO lake.evolved(i) VALUES(3)")
        println("DECODED DEFAULT retained comparison fixture database=$db path=$path historical=$before")
        fe().use { c ->
            assertThatThrownBy { register(c, db, path, ", \"experimental.final11.reads\"=\"true\", \"final11.history-window\"=\"16\"") }
                .hasMessageContaining("total page budget exceeded")
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\", \"final11.history-window\"=\"${window(db, before)}\"")
            val literal = "42'); DROP TABLE ducklake_snapshot; --"
            val tail = listOf("005CFF", "2024-02-29", "1969-12-31 23:59:59.123456", "12345678901234567.125", literal, "5C783431C3A9")
            val projection = "i,renamed,s,sn,HEX(b),CAST(d AS STRING),DATE_FORMAT(ts,'%Y-%m-%d %H:%i:%s.%f'),CAST(z AS STRING),q,HEX(slash)"
            val actual = rows(c, "SELECT $projection FROM $catalog.main.evolved ORDER BY i")
            assertThat(actual).containsExactly(listOf("1", "42", "NULL", null) + tail,
                listOf("2") + List(9) { null }, listOf("3", "77", "later", null) + tail)
            val historical = rows(c, "SELECT ${projection.replace("renamed", "k")} FROM $catalog.main.evolved FOR VERSION AS OF $before ORDER BY i")
            assertThat(historical).containsExactly(listOf("1", "42", "NULL", null) + tail)
            assertThat(rows(c, "SELECT COUNT(*),COUNT(renamed),COUNT(s),COUNT(b),SUM(renamed) FROM $catalog.main.evolved"))
                .containsExactly(listOf("3", "2", "2", "2", "119"))
            assertThat(rows(c, "SELECT i FROM $catalog.main.evolved WHERE renamed IS NULL AND s IS NULL AND b IS NULL"))
                .containsExactly(listOf("2"))
            println("ACTUAL DECODED DEFAULT FE/BE current=$actual historical=$historical; stored NULLs and changed insert defaults preserved")
        }
    }

    @Test
    fun decodedDefaultHistoryRefusesUnprojectableExpressionsAndNullParentsNeverBecomeContainers() {
        ensureBucket()
        val (db, path) = produce("1.1")
        mutate(db, path, "CREATE TABLE lake.bad(i INTEGER); INSERT INTO lake.bad VALUES(1); " +
            "ALTER TABLE lake.bad ADD COLUMN n INTEGER DEFAULT 42; " +
            "CREATE TABLE lake.nested(i INTEGER,p STRUCT(a INTEGER)); INSERT INTO lake.nested VALUES(1,NULL)")
        fe().use { c ->
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\"")
            assertThat(rows(c, "SELECT i,n FROM $catalog.main.bad")).containsExactly(listOf("1", "42"))
            // Owned negative fixture only: an expression history WITHOUT a literal initial value.
            // A valid literal initial value can coexist with an INSERT expression (e.g. VARCHAR
            // 'NULL'); that value remains data and must not be incorrectly refused or evaluated.
            pg(db).use { pg -> pg.createStatement().use { it.execute("UPDATE ducklake_column SET default_value_type='expression' WHERE column_name='n'") } }
            assertThat(rows(c, "SELECT i,n FROM $catalog.main.bad")).containsExactly(listOf("1", "42"))
            pg(db).use { pg -> pg.createStatement().use { it.execute("UPDATE ducklake_column SET default_value_type='expression',initial_default=NULL WHERE column_name='n'") } }
            assertThatThrownBy { rows(c, "SELECT i,n FROM $catalog.main.bad") }.hasMessageContaining("non-literal initial-default history")
            try {
                for (initial in listOf("NULL", "'42'")) {
                    pg(db).use { pg -> pg.createStatement().use {
                        it.execute("UPDATE ducklake_column SET default_value_type='unknown',initial_default=$initial WHERE column_name='n'")
                    } }
                    assertThatThrownBy { rows(c, "SELECT i,n FROM $catalog.main.bad") }.hasMessageContaining("unknown initial-default history kind")
                }
            } finally {
                pg(db).use { pg -> pg.createStatement().use {
                    it.execute("UPDATE ducklake_column SET default_value_type='literal',initial_default='42' WHERE column_name='n'")
                } }
            }
            pg(db).use { pg -> pg.createStatement().use { it.execute("UPDATE ducklake_column SET default_value_type='literal',initial_default='42' WHERE column_name='n'") } }
            assertThat(rows(c, "SELECT i,n FROM $catalog.main.bad")).containsExactly(listOf("1", "42"))
            assertThatThrownBy { rows(c, "SELECT * FROM $catalog.main.nested") }.hasMessageContaining("not qualified")
            println("ACTUAL FE/BE expression history rejects; restored fixture fresh statement recovers; nested NULL parent remains explicitly unsupported")
        }
    }

    @Test
    fun oneItemPagesCaptureMoreSchemasTablesAndNativeVersionsBeyond256() {
        ensureBucket()
        val (db, path) = produce("1.1")
        mutate(db, path, "CREATE SCHEMA lake.s1; CREATE SCHEMA lake.s2; " +
            "CREATE TABLE lake.s1.a(k INTEGER); INSERT INTO lake.s1.a VALUES(11); " +
            "CREATE TABLE lake.s1.b(k INTEGER); INSERT INTO lake.s1.b VALUES(12); " +
            "CREATE TABLE lake.s2.c(k INTEGER,p VARCHAR); ALTER TABLE lake.s2.c SET PARTITIONED BY(p); " +
            "INSERT INTO lake.s2.c VALUES(13,'a/b'),(14,NULL); " +
            (1..260).joinToString("; ") { if (it % 2 == 1) "ALTER TABLE lake.t RENAME COLUMN i TO j" else "ALTER TABLE lake.t RENAME COLUMN j TO i" })
        pg(db).use { pg ->
            assertThat(rows(pg, "SELECT max(schema_version) FROM ducklake_snapshot").single().single()!!.toLong()).isGreaterThan(256)
        }
        fe().use { c ->
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\", \"final11.max-planning-page-size\"=\"1\"")
            assertThat(rows(c, "SELECT a.k,b.k,c.k,t.added FROM $catalog.s1.a a CROSS JOIN $catalog.s1.b b " +
                "CROSS JOIN $catalog.s2.c c CROSS JOIN $catalog.main.t t WHERE t.i=1 AND c.k=13"))
                .containsExactly(listOf("11", "12", "13", "42"))
            assertThat(rows(c, "SELECT k,p FROM $catalog.s2.c ORDER BY k"))
                .containsExactly(listOf("13", "a/b"), listOf("14", null))
            assertThat(rows(c, "SELECT a.i FROM $catalog.main.t a JOIN $catalog.main.t b ON a.i=b.i ORDER BY a.i"))
                .containsExactly(listOf("1"), listOf("2"), listOf("3"))
        }
        val context = dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext("schema-budget", 811L)
        DuckLakeConnectorProvider().create(hostProperties(db, path) + mapOf(
            "final11.max-planning-schemas" to "2", "final11.max-planning-page-size" to "1",
        ), context).use { connector ->
            val s = session(Scope(), "schema-budget")
            val metadata = connector.getMetadata(s)
            val original = runCatching { metadata.getTableHandle(s, "main", "t") }.exceptionOrNull()
            assertThat(original).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("node budget")
            assertThatThrownBy { connector.getMetadata(s).getTableHandle(s, "s1", "a") }.isSameAs(original)
        }
        println("SQL one-item pages: 3 schemas/4 tables/native schema version>256, defaults and self join within unchanged ceilings")
    }

    @Test
    fun realAdapterRejectsOversizeDuplicateAndCyclicMetadataThenFreshStatementRecovers() {
        ensureBucket()
        val (db, path) = produce("1.1")
        val context = dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext("paged-negatives", 811L)
        DuckLakeConnectorProvider().create(hostProperties(db, path) + ("final11.max-planning-page-size" to "1"), context).use { connector ->
            val snapshot = snapshot(db)
            val tableId = pg(db).use { rows(it, "SELECT table_id FROM ducklake_table WHERE table_name='t'").single().single()!! }
            fun sql(statement: String) { pg(db).use { it.createStatement().use { s -> s.execute(statement) } } }
            fun failure() {
                val s = session(Scope(), UUID.randomUUID().toString())
                assertThatThrownBy { connector.getMetadata(s).getTableHandle(s, "main", "t") }
                    .isInstanceOf(IllegalStateException::class.java)
            }
            // The oversized cell is rejected by bounded library SQL, not trimmed by this adapter.
            try {
                sql("UPDATE ducklake_column SET column_name=repeat('x',16385) WHERE table_id=$tableId AND column_name='i'")
                val s = session(Scope(), "oversize")
                assertThatThrownBy { connector.getMetadata(s).getTableHandle(s, "main", "t") }.hasMessageContaining("text")
            } finally { sql("UPDATE ducklake_column SET column_name='i' WHERE table_id=$tableId AND length(column_name)=16385") }
            try {
                sql("UPDATE ducklake_column SET parent_column=column_id WHERE table_id=$tableId AND column_name='i'")
                failure()
            } finally { sql("UPDATE ducklake_column SET parent_column=NULL WHERE table_id=$tableId AND column_name='i'") }
            // Duplicate seek key at a one-item boundary: add one owned active history row.
            try {
                sql("INSERT INTO ducklake_column SELECT * FROM ducklake_column WHERE table_id=$tableId AND column_name='i'")
                val s = session(Scope(), "duplicate")
                assertThatThrownBy { connector.getMetadata(s).getTableHandle(s, "main", "t") }.hasMessageContaining("Duplicate")
            } finally {
                sql("DELETE FROM ducklake_column WHERE ctid IN (SELECT ctid FROM ducklake_column WHERE table_id=$tableId AND column_name='i' ORDER BY ctid OFFSET 1)")
            }
            try {
                sql("INSERT INTO ducklake_name_mapping(mapping_id,column_id,source_name,target_field_id,parent_column,is_partition) " +
                    "VALUES(303,11,'i',1,NULL,false),(303,22,'renamed',2,11,true)")
                sql("UPDATE ducklake_data_file SET mapping_id=303 WHERE table_id=$tableId")
                val s = session(Scope(), "mapping-registry-not-target-ids")
                val metadata = connector.getMetadata(s)
                val h = metadata.getTableHandle(s, "main", "t").orElseThrow()
                assertThatThrownBy { metadata.getTableSchema(s, h) }.hasMessageContaining("Hive name mappings")
            } finally {
                sql("UPDATE ducklake_data_file SET mapping_id=NULL WHERE table_id=$tableId")
                sql("DELETE FROM ducklake_name_mapping WHERE mapping_id=303")
            }
            val recovered = session(Scope(), "repaired")
            val h = connector.getMetadata(recovered).getTableHandle(recovered, "main", "t").orElseThrow() as DuckLakeTableHandle
            assertThat(h.snapshotId).isEqualTo(snapshot)
        }
        println("Real adapter SQL-bounded oversize/duplicate boundary/cycle refusals; metadata repaired and fresh statement recovers")
    }

    @Test
    fun physicalMetadataTypesAndNegativePositionsRefuseBeforePayloadAndFreshSqlRecovers() {
        ensureBucket()
        val (db, path) = produce("1.1")
        val table = pg(db).use { rows(it, "SELECT table_id FROM ducklake_table WHERE table_name='t'").single().single()!! }
        val originalStart = pg(db).use { rows(it, "SELECT row_id_start FROM ducklake_data_file WHERE table_id=$table").single().single() }
        fun sql(text: String) { pg(db).use { it.createStatement().use { s -> s.execute(text) } } }
        val context = dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext("physical-types", 811L)
        DuckLakeConnectorProvider().create(hostProperties(db, path), context).use { connector ->
            fe().use { fe ->
                val name = register(fe, db, path, ", \"experimental.final11.reads\"=\"true\", \"final11.history-window\"=\"2\"")
                val query = "SELECT i FROM $name.main.t ORDER BY i"
                fun recovered() { assertThat(rows(fe, query)).containsExactly(listOf("1"), listOf("2"), listOf("3")) }
                recovered() // Initialize the FE namespace before corruption; cold masking is a separate limitation.
                fun refused(message: String) {
                    val s = session(Scope(), UUID.randomUUID().toString())
                    val failure = org.assertj.core.api.Assertions.catchThrowable { connector.getMetadata(s).getTableHandle(s, "main", "t") }
                    assertThat(failure).hasMessageContaining(message)
                    assertThat(generateSequence(failure) { it.cause }.filterIsInstance<org.postgresql.util.PSQLException>().toList()).isEmpty()
                    assertThatThrownBy { connector.getMetadata(s).listDatabaseNames(s) }.isSameAs(failure)
                    assertThatThrownBy { rows(fe, query) }.hasMessageContaining(message)
                }
                try {
                    sql("ALTER TABLE ducklake_data_file ALTER COLUMN footer_size TYPE NUMERIC USING footer_size+0.5")
                    refused("physical") // Admission/type failure, NOT an executed JDBC statement failure.
                } finally { sql("ALTER TABLE ducklake_data_file ALTER COLUMN footer_size TYPE BIGINT USING floor(footer_size)::bigint") }
                recovered()
                try {
                    sql("UPDATE ducklake_data_file SET row_id_start=-1 WHERE table_id=$table")
                    refused("identity/lifetime")
                } finally { sql("UPDATE ducklake_data_file SET row_id_start=${originalStart ?: "NULL"} WHERE table_id=$table") }
                recovered()
                val positional = "ducklake_inlined_delete_$table"
                try {
                    sql("CREATE TABLE $positional(file_id BIGINT,row_id NUMERIC,begin_snapshot BIGINT)")
                    refused("physical layout") // Even an EMPTY optional table must have the exact physical layout.
                    sql("ALTER TABLE $positional ALTER COLUMN row_id TYPE BIGINT")
                    recovered() // Layout validation is scoped to each owned capture, not a poisoned global cache.
                    sql("INSERT INTO $positional VALUES(0,-1,${snapshot(db)})")
                    refused("Negative")
                    sql("UPDATE $positional SET row_id=0,begin_snapshot=-1")
                    assertThatThrownBy { rows(fe, query) }.hasMessageContaining("inline data/deletes")
                    sql("UPDATE $positional SET begin_snapshot=${snapshot(db)}")
                    assertThatThrownBy { rows(fe, query) }.hasMessageContaining("inline data/deletes")
                    val older = snapshot(db) - 1
                    // API13 resolves current table metadata before pinning: current live
                    // inline deletes must STILL refuse, even for an older SQL request.
                    assertThatThrownBy { rows(fe, "SELECT i FROM $name.main.t FOR VERSION AS OF $older ORDER BY i") }
                        .hasMessageContaining("inline data/deletes")
                    val config = dev.brikk.ducklake.catalog.DucklakeCatalogConfig().apply {
                        catalogDatabaseUrl = "jdbc:postgresql://127.0.0.1:15487/$db"
                        catalogDatabaseUser = "r07"
                        catalogDatabasePassword = "r07-local-only"
                        dataPath = path
                        maxCatalogConnections = 1
                        enableFinal11Reads = true
                    }
                    dev.brikk.ducklake.catalog.JdbcDucklakeCatalog(config).use { catalog ->
                        val props = hostProperties(db, path) + ("final11.history-window" to "2")
                        DuckLakeReadObjects(props, context.httpSecurityHook).use { objects ->
                            val plan = DuckLakeFinal11Plan.capture(catalog, props, objects)
                            // Separate host-capture proof, not a claim of successful historical FE SQL:
                            // positive current refusal must NOT poison the supported older frame.
                            assertThat(plan.dispatch("getDataFiles", arrayOf(table.toLong(), older), older) as List<*>)
                                .hasSize(1)
                        }
                    }
                } finally { sql("DROP TABLE IF EXISTS $positional") }
                recovered()
            }
        }
        println("Physical fractional footer/optional position-layout and negative identity refusals are NOT JDBC query-failure evidence; repaired SQL recovers")
    }

    @Test
    fun absentInlineRegistryPositionsDoNotHideLaterLiveRowsAndTerminalBudgetIsRequired() {
        ensureBucket()
        val (db, path) = produce("1.1")
        mutate(db, path, "CALL lake.set_option('data_inlining_row_limit',100); CREATE TABLE lake.inline_later(i INTEGER); " +
            "INSERT INTO lake.inline_later VALUES(91); ALTER TABLE lake.inline_later ADD COLUMN j INTEGER; " +
            "INSERT INTO lake.inline_later VALUES(92,1)")
        val identities = pg(db).use { rows(it, "SELECT table_id,table_name FROM ducklake_inlined_data_tables ORDER BY schema_version") }
        assertThat(identities.size).isEqualTo(2)
        val absent = identities.first()[1]!!
        // Retain registry identity but temporarily hide the first physical table. Not a producer claim.
        pg(db).use { it.createStatement().use { s -> s.execute("ALTER TABLE $absent RENAME TO ${absent}_retained") } }
        try {
            val context = dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext("inline-pages", 811L)
            DuckLakeConnectorProvider().create(hostProperties(db, path) + ("final11.max-planning-page-size" to "1"), context).use { connector ->
                val s = session(Scope(), "later-live-registry")
                val metadata = connector.getMetadata(s)
                val handle = metadata.getTableHandle(s, "main", "inline_later").orElseThrow()
                assertThatThrownBy { metadata.getTableSchema(s, handle) }
                    .hasMessageContaining("inline")
            }
            val config = dev.brikk.ducklake.catalog.DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = "jdbc:postgresql://127.0.0.1:15487/$db"
                catalogDatabaseUser = "r07"
                catalogDatabasePassword = "r07-local-only"
                dataPath = path
                maxCatalogConnections = 1
                enableFinal11Reads = true
            }
            dev.brikk.ducklake.catalog.JdbcDucklakeCatalog(config).use { catalog ->
                catalog.readSession(java.util.function.Supplier {
                    val head = catalog.currentSnapshotId
                    // Exactly one nonempty schema page fits. Its REQUIRED terminal page does not.
                    val pages = DuckLakePlanningPages(catalog, head, DuckLakeReadBudget(mapOf("final11.max-planning-pages" to "1")))
                    assertThatThrownBy { pages.schemas() }.hasMessageContaining("page budget")
                    val complete = DuckLakePlanningPages(catalog, head, DuckLakeReadBudget(emptyMap())).inline(identities.first()[0]!!.toLong())
                    assertThat(complete.map { it.hasLiveRows }).containsExactly(false, true)
                })
            }
        } finally {
            pg(db).use { it.createStatement().use { s -> s.execute("ALTER TABLE ${absent}_retained RENAME TO $absent") } }
        }
        // Restore metadata AND native data, then prove a new real FE/BE query sees both rows.
        mutate(db, path, "CALL ducklake_flush_inlined_data('lake',table_name=>'inline_later')")
        fe().use { c ->
            val catalog = register(c, db, path, ", \"experimental.final11.reads\"=\"true\", \"final11.max-planning-page-size\"=\"1\"")
            assertThat(rows(c, "SELECT i,j FROM $catalog.main.inline_later ORDER BY i"))
                .containsExactly(listOf("91", null), listOf("92", "1"))
        }
    }
}
