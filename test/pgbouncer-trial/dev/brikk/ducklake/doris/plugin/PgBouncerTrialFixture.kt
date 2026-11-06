package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

/** Two tiny server slots, no FE/BE, no retained volume; only the driver prepareThreshold=0 lane. */
internal class PgBouncerTrialFixture(root: Path) : AutoCloseable {
    private val server = TestingDucklakePostgreSqlCatalogServer()
    private val database = "doris_pool_" + UUID.randomUUID().toString().replace("-", "")
    private val data = Files.createTempDirectory(root, "data-")
    private val bouncer = GenericContainer(DockerImageName.parse(IMAGE))
    val url: String
    val directUrl: String
    val user: String = server.getUser()
    val password: String = server.getPassword()

    init {
        try {
            server.createDatabase(database)
            directUrl = server.getJdbcUrl(database)
            DriverManager.getConnection("jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
                statement.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
                statement.execute("ATTACH '${server.getDuckDbAttachUri(database)}' AS lake " +
                    "(DATA_PATH '$data'${DuckLakeTestCatalogBootstrap.LEGACY_FORMAT_OPTIONS})")
                statement.execute("CREATE TABLE lake.t(id INTEGER)")
            } }
            val pgPort = Regex("://[^:/]+:(\\d+)/").find(directUrl)!!.groupValues[1]
            bouncer.withExtraHost("host.docker.internal", "host-gateway")
                .withLabel("dev.brikk.ducklake.doris.pool-trial", database)
                .withCopyToContainer(Transferable.of(configuration(pgPort)), "/etc/pgbouncer/pgbouncer.ini")
                .withCopyToContainer(Transferable.of("\"$user\" \"$password\"\n"), "/etc/pgbouncer/userlist.txt")
                .withExposedPorts(6432)
                .withCreateContainerCmdModifier { command -> command.hostConfig?.withMemory(64L * 1024 * 1024)?.withNanoCPUs(250_000_000L) }
                .waitingFor(Wait.forListeningPort())
            bouncer.start()
            url = "jdbc:postgresql://${bouncer.host}:${bouncer.getMappedPort(6432)}/$database" +
                "?prepareThreshold=0&ApplicationName=doris-pgbouncer-trial&connectTimeout=5&socketTimeout=10"
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun properties() = mapOf("metadata.url" to url, "metadata.user" to user, "metadata.password" to password,
        "storage.warehouse" to data.toString(), "catalog.max-connections" to "1", "catalog.min-idle-connections" to "0",
        "catalog.connection-idle-timeout" to "30s", "catalog.connection-max-lifetime" to "3m", "catalog.connection-timeout" to "500ms")

    private fun configuration(port: String) = """
        [databases]
        * = host=host.docker.internal port=$port
        [pgbouncer]
        listen_addr = 0.0.0.0
        listen_port = 6432
        auth_type = plain
        auth_file = /etc/pgbouncer/userlist.txt
        pool_mode = transaction
        default_pool_size = 2
        max_client_conn = 12
        max_prepared_statements = 0
        server_lifetime = 300
        ignore_startup_parameters = extra_float_digits,options
    """.trimIndent()

    override fun close() {
        try { bouncer.stop() } finally { server.close() }
    }

    companion object {
        // PgBouncer 1.23.1; same immutable fixture as the producer's transaction-pooling tests.
        private const val IMAGE = "edoburu/pgbouncer@sha256:377dec3c0e4a66a1077ec043e16a26ed5702a6d954011a7983a1457c2e070b1d"
    }
}
