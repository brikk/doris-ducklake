package dev.brikk.ducklake.doris.plugin

import io.minio.MakeBucketArgs
import io.minio.BucketExistsArgs
import io.minio.MinioClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Requires ONLY the isolated R07 compose; never borrows the Trino stack or shared lake. */
internal class DuckLakeR07LiveQualificationTest {
    private val suffix = UUID.randomUUID().toString().replace("-", "")

    private fun fe(): Connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:19387/", "root", "")

    private fun rows(c: Connection, sql: String): List<List<String?>> = c.createStatement().use { s ->
        s.executeQuery(sql).use { r -> buildList {
            while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
        } }
    }

    private fun produce(version: String): Pair<String, String> {
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

    private fun register(c: Connection, database: String, warehouse: String): String {
        val name = "catalog_$database"
        c.createStatement().use { s -> s.execute("""
            CREATE CATALOG $name PROPERTIES (
              "type"="ducklake", "metadata.url"="jdbc:postgresql://postgres:5432/$database",
              "metadata.user"="r07", "metadata.password"="r07-local-only", "storage.warehouse"="$warehouse",
              "s3.endpoint"="http://minio:9000", "s3.access_key"="r07local", "s3.secret_key"="r07-local-only",
              "s3.region"="us-east-1", "use_path_style"="true")
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
            val final = register(c, finalDb, finalPath)
            // API-13 ExternalCatalog.buildDbForInit catches the connector's format
            // refusal and exposes a generic missing-database error. Exact guard
            // text is independently asserted headlessly and attested in the FE
            // log. This pins observed failure, NOT successful 1.1 qualification.
            assertThatThrownBy { rows(c, "SELECT * FROM $final.main.t") }
                .isInstanceOf(java.sql.SQLException::class.java)
                .hasMessageContaining("Database [main] does not exist")
            println("FINAL11 refused; FE masks the format diagnostic (inspect FE log; R07 remains NO-GO)")
            // Failed 1.1 planning must not poison another catalog's pool or FE query state.
            assertThat(rows(c, "SELECT i FROM $legacy.main.t ORDER BY i"))
                .containsExactly(listOf("1"), listOf("2"), listOf("3"))
        }
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
}
