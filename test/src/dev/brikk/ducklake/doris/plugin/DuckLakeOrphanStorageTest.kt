package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeTableFilePathRef
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import io.minio.MakeBucketArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import org.apache.doris.connector.spi.DorisConnectorException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Runs remove_orphan_files against real catalog ownership rows and the real S3 list/delete implementation rather than a fake lister. */
internal class DuckLakeOrphanStorageTest {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var storage: OrphanTestMinio

        @BeforeAll
        @JvmStatic
        @Suppress("TooGenericExceptionCaught")
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            try {
                storage = OrphanTestMinio()
            } catch (failure: Exception) {
                server.close()
                throw failure
            }
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            try {
                if (::storage.isInitialized) storage.close()
            } finally {
                if (::server.isInitialized) server.close()
            }
        }
    }

    @Test
    fun aliasesKeepRealCatalogOwnershipAndExactObjectBytesWhileDeletingOnlyOldResidue() {
        for (scheme in listOf("s3", "s3a", "s3n")) {
            val isolated = seed(scheme)
            val bytes = openCatalog(isolated).use { catalog ->
                val resolver = DuckLakePathResolver(catalog, null)
                catalog.listAllReferencedFiles().tableFiles.map { ref ->
                    Files.readAllBytes(Path.of(resolve(ref, resolver.rootDataPath())))
                }
            }
            val bucket = "f01-" + UUID.randomUUID().toString().replace("-", "")
            val root = "$scheme://$bucket/lake/"
            storage.client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
            rewriteOwnedMetadata(isolated, root)
            openCatalog(isolated).use { catalog ->
                val fixture = populateObjects(catalog, root, bytes)
                val props = storage.properties + mapOf(
                    "storage.warehouse" to "s3://wrong-bucket/wrong-fallback",
                    "maintenance.min-retention" to "1s",
                )
                assertThat(catalog.getDataPath()).isEqualTo(root)
                assertThat(catalog.getTable("main", "retired", catalog.currentSnapshotId)).isNull()
                val handle = DuckLakeConnectorMetadata(catalog).getTableHandle(null, "main", "live").orElseThrow()
                val ops = DuckLakeProcedureOps(catalog, props)
                val arguments = mapOf("scope" to "catalog", "retention_threshold" to "1s")
                val dry = ops.execute(null, handle, "remove_orphan_files", arguments + ("dry_run" to "true"), null, emptyList())
                assertThat(dry.rows.single()).containsExactly("true", "1s", fixture.orphans.size.toString(), "0")
                assertObjects(fixture.contents)
                val actual = ops.execute(null, handle, "remove_orphan_files", arguments, null, emptyList())
                assertThat(actual.rows.single()).containsExactly("false", "1s", fixture.orphans.size.toString(), "0")
                assertObjects(fixture.contents - fixture.orphans.toSet())
                assertThat(S3WarehouseBlobStore(storage.client).list(root).map { it.uri })
                    .containsExactlyInAnyOrderElementsOf(fixture.contents.keys - fixture.orphans.toSet())
                fixture.orphans.forEach(::assertAbsent)
                println("F01 real catalog/S3 $scheme: exact survivors=${fixture.contents.size - fixture.orphans.size}, deleted=${fixture.orphans.size}")
            }
        }
    }

    @Test
    fun unrepresentableDeleteCannotTargetACollidingReferencedKey() {
        val bucket = "f01-" + UUID.randomUUID().toString().replace("-", "")
        storage.client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
        val root = "s3://$bucket/paths/"
        val live = root + "ducklake-live.parquet"
        val repeated = root + "/ducklake-live.parquet"
        // This MinIO server rejects raw repeated-slash object names, but the SDK
        // must still not rewrite an attempted deletion onto this existing key.
        val contents = mapOf(live to "live".toByteArray())
        contents.forEach(::put)
        assertObjects(contents)
        val store = S3WarehouseBlobStore(storage.client)
        assertThat(store.list(root).map { it.uri }).containsExactlyInAnyOrderElementsOf(contents.keys)
        // The backend rejects this exact raw prefix; it must not be broadened to paths/.
        assertThatThrownBy { store.list(root + "/") }
            .isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("failed to read a listed object")
        val aliased = storage.client.statObject(StatObjectArgs.builder().bucket(bucket).`object`("paths//ducklake-live.parquet").build())
        assertThat(aliased.size()).describedAs("SDK stat rewrites the absent raw key onto the existing live key")
            .isEqualTo(contents.getValue(live).size.toLong())
        val refused = listOf(repeated, root + "./ducklake-live.parquet", root + "child/../ducklake-live.parquet")
        val result = store.delete(refused)
        assertThat(result.deleted).isEmpty()
        assertThat(result.failed.keys).containsExactlyInAnyOrderElementsOf(refused)
        assertObjects(contents)
    }

    private fun seed(scheme: String): DuckLakeTestCatalogBootstrap.IsolatedCatalog {
        val token = "f01_${scheme}_" + UUID.randomUUID().toString().replace("-", "")
        check(!Files.exists(Path.of("build", "test-data", "doris-$token")))
        return DuckLakeAuditFixtureSeeder.seed(server, token, listOf(
            "CALL dl.set_option('data_inlining_row_limit', '0')",
            "CREATE TABLE dl.main.live (id INTEGER)", "INSERT INTO dl.main.live VALUES (1)",
            "CREATE TABLE dl.main.retired (id INTEGER)", "INSERT INTO dl.main.retired VALUES (2)",
            "CREATE TABLE dl.main.absolute_path (id INTEGER)", "INSERT INTO dl.main.absolute_path VALUES (3)",
            "CREATE TABLE dl.main.deleted (id INTEGER)", "INSERT INTO dl.main.deleted SELECT range FROM range(10)",
            "CHECKPOINT dl", "DELETE FROM dl.main.deleted WHERE id=1", "DROP TABLE dl.main.retired",
        ))
    }

    private fun openCatalog(isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog) =
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = isolated.jdbcUrl()
            catalogDatabaseUser = isolated.user()
            catalogDatabasePassword = isolated.password()
            dataPath = "s3://wrong-bucket/wrong-fallback"
        })

    private fun resolve(ref: DucklakeTableFilePathRef, root: String): String {
        val schema = DuckLakePathResolver.resolveScopedPath(ref.schemaPath, ref.schemaPathIsRelative, root)
        val table = DuckLakePathResolver.resolveScopedPath(ref.tablePath, ref.tablePathIsRelative, schema)
        return if (ref.pathIsRelative) table + (if (table.endsWith('/')) "" else "/") + ref.path else ref.path
    }

    private fun rewriteOwnedMetadata(isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog, root: String) {
        DriverManager.getConnection(isolated.jdbcUrl(), isolated.user(), isolated.password()).use { connection ->
            update(connection, "UPDATE ducklake_metadata SET value=? WHERE key='data_path' AND scope IS NULL", root)
            update(connection, "UPDATE ducklake_schema SET path='schema/',path_is_relative=true")
            update(connection, "UPDATE ducklake_table SET path=table_name || '/',path_is_relative=true")
            update(connection, "UPDATE ducklake_table SET path=?,path_is_relative=false WHERE table_name='live'",
                root.replaceBefore("://", "s3n") + "schema/live/")
            update(connection, "UPDATE ducklake_data_file SET path=?,path_is_relative=false WHERE table_id IN " +
                "(SELECT table_id FROM ducklake_table WHERE table_name='absolute_path')",
                root.replaceBefore("://", "s3a") + "absolute/ducklake-file.parquet")
            val scheduled = rawKnownKeys + listOf("scheduled/ducklake-relative.puffin", "scheduled/ducklake-absolute.puffin")
            for ((index, key) in scheduled.withIndex()) {
                val absolute = index == scheduled.lastIndex
                update(connection, "INSERT INTO ducklake_files_scheduled_for_deletion " +
                    "(data_file_id,path,path_is_relative,schedule_start) VALUES (?,?,?,now()-interval '1 hour')",
                    900000L + index, if (absolute) root.replaceBefore("://", "s3n") + key else key, !absolute)
            }
        }
    }

    private fun update(connection: Connection, sql: String, vararg args: Any) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            check(statement.executeUpdate() > 0) { "Owned fixture metadata update affected no rows" }
        }
    }

    private fun populateObjects(catalog: JdbcDucklakeCatalog, root: String, nativeFiles: List<ByteArray>): Objects {
        val refs = catalog.listAllReferencedFiles()
        assertThat(refs.tableFiles).hasSize(nativeFiles.size).hasSizeGreaterThanOrEqualTo(5)
        val contents = linkedMapOf<String, ByteArray>()
        refs.tableFiles.forEachIndexed { index, ref ->
            // Independent listing oracle: production lists literal bucket/key text with s3://.
            contents[resolve(ref, root).replaceBefore("://", "s3")] = nativeFiles[index]
        }
        refs.scheduledFiles.forEach { ref ->
            val uri = if (ref.pathIsRelative) root + ref.path else ref.path
            contents[uri.replaceBefore("://", "s3")] = "scheduled:${ref.path}".toByteArray()
        }
        val canonicalRoot = root.replaceBefore("://", "s3")
        val orphans = (rawOrphanKeys + "ducklake-orphan.parquet").map { canonicalRoot + it }
        orphans.forEach { contents[it] = "old-unreferenced:$it".toByteArray() }
        for (foreign in listOf("data.parquet", "_SUCCESS", "ducklake-unmanaged.vortex")) {
            contents[canonicalRoot + foreign] = "foreign:$foreign".toByteArray()
        }
        contents.forEach(::put)
        // Actual object ages, not a mocked last-modified clock; leave young residue protected.
        Thread.sleep(2000)
        val young = canonicalRoot + "ducklake-young.parquet"
        contents[young] = "young".toByteArray()
        put(young, contents.getValue(young))
        return Objects(contents, orphans)
    }

    private fun put(uri: String, bytes: ByteArray) {
        val (bucket, key) = S3WarehouseBlobStore.parseS3Uri(uri)
        storage.putRaw(bucket, key, bytes)
    }

    private fun assertObjects(contents: Map<String, ByteArray>) {
        contents.forEach { (uri, bytes) ->
            val (bucket, key) = S3WarehouseBlobStore.parseS3Uri(uri)
            assertThat(storage.getRaw(bucket, key)).describedAs(uri).isEqualTo(bytes)
        }
    }

    private fun assertAbsent(uri: String) {
        val (bucket, key) = S3WarehouseBlobStore.parseS3Uri(uri)
        assertThatThrownBy { storage.client.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build()) }
            .isInstanceOfSatisfying(ErrorResponseException::class.java) { failure ->
                assertThat(failure.errorResponse().code()).isEqualTo("NoSuchKey")
            }
    }

    private val rawKnownKeys = listOf("keys/a%2Fb/ducklake-raw.parquet", "keys/a+b/ducklake-raw.parquet",
        "keys/space%20key/ducklake-raw.parquet", "keys/é/ducklake-raw.parquet")
    private val rawOrphanKeys = listOf("keys/a/b/ducklake-raw.parquet", "keys/a b/ducklake-raw.parquet",
        "keys/space key/ducklake-raw.parquet", "keys/e\u0301/ducklake-raw.parquet")

    private data class Objects(val contents: Map<String, ByteArray>, val orphans: List<String>)
}
