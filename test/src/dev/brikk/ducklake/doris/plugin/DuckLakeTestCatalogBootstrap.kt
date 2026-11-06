package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Comparator
import java.util.Locale

/**
 * Bootstraps a Postgres-backed DuckLake catalog through DuckDB's `ducklake` and
 * `postgres` extensions, seeded with the shared schemas and tables the connector
 * tests read.
 */
object DuckLakeTestCatalogBootstrap {

    class IsolatedCatalog(
        private val jdbcUrl: String,
        private val user: String,
        private val password: String,
        private val dataDir: Path,
    ) {
        fun jdbcUrl(): String = jdbcUrl

        fun user(): String = user

        fun password(): String = password

        fun dataDir(): Path = dataDir
    }

    @JvmStatic
    @Throws(Exception::class)
    fun bootstrap(server: TestingDucklakePostgreSqlCatalogServer, testName: String): IsolatedCatalog {
        val databaseName = "ducklake_" + testName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_")
        server.createDatabase(databaseName)

        val target = Path.of("build", "test-data", "doris-$testName")
        val dataDir = target.resolve("data")
        recreateDirectory(target)
        Files.createDirectories(dataDir)

        val attachUri = server.getDuckDbAttachUri(databaseName)
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSTALL ducklake")
                statement.execute("LOAD ducklake")
                statement.execute("INSTALL postgres")
                statement.execute("LOAD postgres")
                statement.execute(
                    "ATTACH '" + escape(attachUri) + "' AS dl (DATA_PATH '" +
                        escape(dataDir.toAbsolutePath().toString()) + "'" + LEGACY_FORMAT_OPTIONS + ")",
                )

                statement.execute("CREATE SCHEMA dl.sales")
                statement.execute("CREATE TABLE dl.sales.orders (id INTEGER, total DOUBLE)")
                statement.execute("CREATE TABLE dl.sales.customers (id INTEGER, name VARCHAR)")
                // returns_file gets a file-based delete (position-delete parquet plus a
                // ducklake_delete_file row). returns_inline is meant for inline deletes
                // (`ducklake_inlined_delete_<tableId>` rows), but DuckDB-JDBC does not
                // honour `data_inlining_row_limit` for DELETE and always writes file-based
                // deletes, so it is seeded with INSERTs only; producing an inline delete
                // needs a direct INSERT into the catalog table via PG JDBC.
                statement.execute("CREATE TABLE dl.sales.returns_file (id INTEGER, amount DOUBLE)")
                statement.execute("CREATE TABLE dl.sales.returns_inline (id INTEGER, amount DOUBLE)")

                statement.execute("CREATE SCHEMA dl.analytics")
                statement.execute("CREATE TABLE dl.analytics.events (ts TIMESTAMP, kind VARCHAR)")

                // Two INSERTs do not guarantee two data files: the CHECKPOINT below can
                // consolidate small inserts into one parquet file. Tests on sales.orders
                // must not assert an exact file count. Only the partitioned fixtures
                // (by_region, by_name_bucket) can assert a floor of N files, because
                // CHECKPOINT does not compact across partitions.
                statement.execute("INSERT INTO dl.sales.orders VALUES (1, 9.99), (2, 19.50)")
                statement.execute("INSERT INTO dl.sales.orders VALUES (3, 5.25)")

                // Identity partition pruning: 'us' and 'eu' land in separate data files,
                // so `region = 'us'` must drop the 'eu' file(s).
                statement.execute(
                    "CREATE TABLE dl.sales.by_region (id INTEGER, region VARCHAR, amount DOUBLE)",
                )
                statement.execute("ALTER TABLE dl.sales.by_region SET PARTITIONED BY (region)")
                statement.execute("INSERT INTO dl.sales.by_region VALUES (1, 'us', 10.0), (2, 'us', 20.0)")
                statement.execute("INSERT INTO dl.sales.by_region VALUES (3, 'eu', 30.0), (4, 'eu', 40.0)")

                // Bucket partition pruning: murmur3 bucket(4) sends alice->1, bob->2,
                // charlie->3, so `name = 'alice'` must keep only bucket 1.
                statement.execute("CREATE TABLE dl.sales.by_name_bucket (id INTEGER, name VARCHAR)")
                statement.execute("ALTER TABLE dl.sales.by_name_bucket SET PARTITIONED BY (bucket(4, name))")
                statement.execute(
                    "INSERT INTO dl.sales.by_name_bucket VALUES (1, 'alice'), (2, 'bob'), (3, 'charlie')",
                )

                statement.execute(
                    "INSERT INTO dl.sales.returns_inline " +
                        "VALUES (10, 1.00), (11, 2.00), (12, 3.00), (13, 4.00)",
                )

                // Forces a position-delete parquet rather than an inlined catalog row.
                statement.execute("CALL dl.set_option('data_inlining_row_limit', '0')")
                statement.execute(
                    "INSERT INTO dl.sales.returns_file " +
                        "VALUES (10, 1.00), (11, 2.00), (12, 3.00), (13, 4.00)",
                )
                statement.execute("DELETE FROM dl.sales.returns_file WHERE id = 12")

                statement.execute("CHECKPOINT dl")
            }
        }

        return IsolatedCatalog(
            server.getJdbcUrl(databaseName),
            server.getUser(),
            server.getPassword(),
            dataDir,
        )
    }

    // DuckDB 2.0 defaults new lakes to format 1.1; the connector's write path requires 1.0.
    internal const val LEGACY_FORMAT_OPTIONS =
        ", DUCKLAKE_VERSION '1.0', AUTOMATIC_MIGRATION FALSE"

    @Throws(Exception::class)
    private fun recreateDirectory(directory: Path) {
        if (Files.exists(directory)) {
            Files.walk(directory).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { path ->
                    try {
                        Files.delete(path)
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to delete $path", e)
                    }
                }
            }
        }
        Files.createDirectories(directory)
    }

    private fun escape(value: String): String = value.replace("'", "''")
}
