package dev.brikk.ducklake.doris.corpus

import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.corpus.OracleAttachment
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

/** Real isolated PostgreSQL ownership checks; the recording FE is not an engine-correctness oracle. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
internal class DorisCorpusIsolationTest {
    private lateinit var server: TestingDucklakePostgreSqlCatalogServer
    private val fe = RecordingCorpusFeDriver()

    @TempDir
    lateinit var directory: Path

    @BeforeAll
    fun start() {
        server = TestingDucklakePostgreSqlCatalogServer()
        DriverManager.registerDriver(fe)
    }

    @AfterAll
    fun stop() {
        DriverManager.deregisterDriver(fe)
        server.close()
    }

    @Test
    fun startupPreservesActiveAbandonedAndWildcardLookalikeDatabases() {
        val names = listOf("corpus_doris_active", "corpus_doris_abandoned", "corpusxdorisyforeign", "unrelated_catalog")
        names.forEach(server::createDatabase)
        connection(names.first()).use { active ->
            active.createStatement().use { it.execute("CREATE TABLE marker AS SELECT 42 AS value") }
            withStartupProperties {
                // Exercise the actual @BeforeAll entry, including normal DuckDB and JDBC readiness checks.
                // A failed prerequisite is a test failure here, not a new assumption-skip hiding F22.
                assertThatCode { DorisCorpusReplayTest().setUp() }.doesNotThrowAnyException()
            }
            assertThat(databases()).containsAll(names)
            active.createStatement().use { statement ->
                statement.executeQuery("SELECT value FROM marker").use {
                    assertThat(it.next()).isTrue()
                    assertThat(it.getInt(1)).isEqualTo(42)
                }
            }
        }
    }

    @Test
    fun closingOneRunDropsOnlyItsAcknowledgedResourcesWhileAnotherRunIsConnected() {
        val first = engine()
        val second = engine()
        val executor = Executors.newSingleThreadExecutor()
        var firstClosed = false
        try {
            val firstDb = databaseName(first.metadataRewriter("first"))
            val firstOtherDb = databaseName(first.metadataRewriter("first-other"))
            val secondDb = databaseName(second.metadataRewriter("second"))
            val before = fe.commands.size
            first.connect(OracleAttachment("unused", directory.toUri().toString(), "lake"))
            assertThat(fe.commands.drop(before)).allMatch { it.startsWith("CREATE CATALOG ") }
            connection(secondDb).use { active ->
                active.createStatement().use { it.execute("CREATE TABLE marker AS SELECT 73 AS value") }
                executor.submit { first.close() }.get(10, TimeUnit.SECONDS)
                firstClosed = true
                assertThat(databases()).contains(secondDb).doesNotContain(firstDb, firstOtherDb)
                active.createStatement().use { statement ->
                    statement.executeQuery("SELECT value FROM marker").use {
                        assertThat(it.next()).isTrue()
                        assertThat(it.getInt(1)).isEqualTo(73)
                    }
                }
            }
            assertThat(fe.commands.last()).startsWith("DROP CATALOG IF EXISTS ")
        } finally {
            if (!firstClosed) first.close()
            second.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedCatalogCreationDoesNotAuthorizeDroppingThatCatalog() {
        val engine = engine()
        val db = databaseName(engine.metadataRewriter("failed-catalog"))
        val before = fe.commands.size
        fe.rejectCreate.set(true)
        try {
            assertThatThrownBy {
                engine.connect(OracleAttachment("unused", directory.toUri().toString(), "lake"))
            }.isInstanceOf(SQLException::class.java).hasMessageContaining("injected CREATE refusal")
        } finally {
            fe.rejectCreate.set(false)
            engine.close()
        }
        assertThat(fe.commands.drop(before)).allMatch { it.startsWith("CREATE CATALOG ") }
        assertThat(databases()).doesNotContain(db)
    }

    @Test
    fun reconnectReplacesOnlyTheAlreadyOwnedCatalogAndCleansItOnce() {
        val before = fe.commands.size
        engine().use { engine ->
            engine.metadataRewriter("reattach")
            val attachment = OracleAttachment("unused", directory.toUri().toString(), "lake")
            engine.connect(attachment)
            engine.connect(attachment)
        }
        val commands = fe.commands.drop(before)
        assertThat(commands.map { it.substringBefore(" CATALOG") }).containsExactly("CREATE", "DROP", "CREATE", "DROP")
        val name = commands.first().substringAfter("CREATE CATALOG ").substringBefore(" PROPERTIES")
        assertThat(commands.filter { it.startsWith("DROP ") }).containsExactly(
            "DROP CATALOG IF EXISTS $name", "DROP CATALOG IF EXISTS $name",
        )
    }

    @Test
    fun failedRecreationDoesNotRetainOwnershipOfTheDroppedCatalog() {
        val before = fe.commands.size
        engine().use { engine ->
            engine.metadataRewriter("failed-reattach")
            val attachment = OracleAttachment("unused", directory.toUri().toString(), "lake")
            engine.connect(attachment)
            fe.rejectCreate.set(true)
            try {
                assertThatThrownBy { engine.connect(attachment) }.isInstanceOf(SQLException::class.java)
            } finally {
                fe.rejectCreate.set(false)
            }
        }
        assertThat(fe.commands.drop(before).map { it.substringBefore(" CATALOG") })
            .containsExactly("CREATE", "DROP", "CREATE")
    }

    private fun engine(): DorisReplayEngine = DorisReplayEngine(
        feJdbcUrl = RecordingCorpusFeDriver.URL,
        pgHostUrl = server.getJdbcUrl(),
        pgUser = server.getUser(),
        pgPassword = server.getPassword(),
    )

    private fun connection(database: String = "ducklake"): Connection =
        DriverManager.getConnection(server.getJdbcUrl(database), server.getUser(), server.getPassword())

    private fun databases(): List<String> = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT datname FROM pg_database").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private fun databaseName(rewritten: String): String = rewritten.substringAfter("dbname=").substringBefore(' ')

    private fun withStartupProperties(block: () -> Unit) {
        val properties = mapOf(
            "ducklake.corpus.root" to directory.toString(),
            "doris.corpus.fe.url" to RecordingCorpusFeDriver.URL,
            "doris.corpus.pg.host.url" to server.getJdbcUrl(),
            "doris.corpus.pg.user" to server.getUser(),
            "doris.corpus.pg.password" to server.getPassword(),
        )
        val previous = properties.keys.associateWith(System::getProperty)
        try {
            properties.forEach(System::setProperty)
            block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }
}

/** Implements only SQL used by startup/connect/close. Unknown calls fail instead of pretending to succeed. */
private class RecordingCorpusFeDriver : Driver {
    val commands = CopyOnWriteArrayList<String>()
    val rejectCreate = AtomicBoolean()

    override fun acceptsURL(url: String?): Boolean = url == URL
    override fun connect(url: String?, properties: Properties?): Connection? {
        if (!acceptsURL(url)) return null
        return jdbcProxy(Connection::class.java) { name, _ ->
            when (name) {
                "createStatement" -> statement()
                "close" -> null
                else -> error("Unexpected FE connection method: $name")
            }
        }
    }

    private fun statement(): Statement = jdbcProxy(Statement::class.java) { name, args ->
        when (name) {
            "execute" -> {
                val sql = (args?.first() as String).trim()
                commands += sql
                if (rejectCreate.get() && sql.startsWith("CREATE CATALOG ")) {
                    throw SQLException("injected CREATE refusal: catalog ownership not acquired")
                }
                false
            }
            "executeQuery" -> {
                check(args?.first() == "SELECT 1")
                val first = AtomicBoolean(true)
                jdbcProxy(ResultSet::class.java) { method, _ ->
                    when (method) {
                        "next" -> first.getAndSet(false)
                        "close" -> null
                        else -> error("Unexpected FE result method: $method")
                    }
                }
            }
            "close" -> null
            else -> error("Unexpected FE statement method: $name")
        }
    }

    private fun <T> jdbcProxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args) },
    )

    override fun getPropertyInfo(url: String?, properties: Properties?): Array<DriverPropertyInfo> = emptyArray()
    override fun getMajorVersion(): Int = 1
    override fun getMinorVersion(): Int = 0
    override fun jdbcCompliant(): Boolean = false
    override fun getParentLogger(): Logger = Logger.getLogger(javaClass.name)

    companion object {
        val URL = "jdbc:f22-recording-fe:${UUID.randomUUID()}"
    }
}
