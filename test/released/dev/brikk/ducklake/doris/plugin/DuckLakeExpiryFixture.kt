package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeCatalogFormat
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

/** Native 1.0 bootstrap, then small metadata-only paging fixtures in an owned PostgreSQL database. */
internal class DuckLakeExpiryFixture(server: TestingDucklakePostgreSqlCatalogServer, root: Path, deadFiles: Int) : AutoCloseable {
    private val database = "expiry_" + UUID.randomUUID().toString().replace("-", "")
    private val url: String
    private val user = server.getUser()
    private val password = server.getPassword()
    val catalog: JdbcDucklakeCatalog
    val a: Long
    val b: Long

    init {
        server.createDatabase(database)
        url = server.getJdbcUrl(database)
        val data = Files.createTempDirectory(root, "lake-")
        DriverManager.getConnection("jdbc:duckdb:").use { native -> native.createStatement().use { statement ->
            statement.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
            statement.execute("ATTACH '${server.getDuckDbAttachUri(database)}' AS dl " +
                "(DATA_PATH '$data'${DuckLakeTestCatalogBootstrap.LEGACY_FORMAT_OPTIONS})")
            statement.execute("CREATE TABLE dl.a(id INTEGER)")
            statement.execute("CREATE TABLE dl.b(id INTEGER)")
        } }
        sql("INSERT INTO ducklake_snapshot(snapshot_id,schema_version,next_catalog_id,next_file_id) " +
            "VALUES (3,0,20000,30000),(4,0,20000,30000)")
        catalog = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = url
            catalogDatabaseUser = user
            catalogDatabasePassword = password
            requiredCatalogFormat = DucklakeCatalogFormat.V1_0
            maxCatalogConnections = 1
        })
        a = requireNotNull(catalog.getTable("main", "a", 4)).tableId
        b = requireNotNull(catalog.getTable("main", "b", 4)).tableId
        sql("INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,end_snapshot,path,path_is_relative,file_format) " +
            "SELECT i,$a,2,3,'dead-'||i||'.parquet',true,'parquet' FROM generate_series(1,$deadFiles) AS g(i)")
        sql("INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,path,path_is_relative,file_format) " +
            "VALUES (10000,$a,3,'live-a.parquet',true,'parquet'),(10001,$b,3,'live-b.parquet',true,'parquet')")
    }

    fun sql(text: String) = DriverManager.getConnection(url, user, password).use { connection ->
        connection.createStatement().use { it.execute(text) }
    }

    fun scalar(text: String): Long = DriverManager.getConnection(url, user, password).use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(text).use { rows -> check(rows.next()); rows.getLong(1) } }
    }

    fun count(table: String): Long = scalar("SELECT count(*) FROM ducklake_$table")

    fun expireProcedure(dryRun: Boolean = false) = DuckLakeProcedureOps(catalog, emptyMap()) {
        error("expire_snapshots must not construct a physical storage client")
    }.execute(null, null, "expire_snapshots", mapOf("snapshot_ids" to "1,2,3", "dry_run" to dryRun.toString()), null, emptyList())

    fun assertLiveFiles() {
        org.assertj.core.api.Assertions.assertThat(catalog.currentSnapshotId).isEqualTo(4)
        org.assertj.core.api.Assertions.assertThat(catalog.getDataFiles(a, 4).map { it.dataFileId }).containsExactly(10000L)
        org.assertj.core.api.Assertions.assertThat(catalog.getDataFiles(b, 4).map { it.dataFileId }).containsExactly(10001L)
        org.assertj.core.api.Assertions.assertThat(scalar(
            "SELECT count(*) FROM ducklake_files_scheduled_for_deletion WHERE data_file_id IN (10000,10001)",
        )).isZero()
    }

    override fun close() { catalog.close() }
}
