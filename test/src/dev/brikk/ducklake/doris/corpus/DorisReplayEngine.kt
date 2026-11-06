package dev.brikk.ducklake.doris.corpus

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.net.URI
import java.nio.file.Path
import java.util.function.Supplier

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.InlinedTypeNode
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.corpus.OracleAttachment
import dev.brikk.ducklake.corpus.ReplayEngineSkip
import dev.brikk.ducklake.corpus.ReplayReadEngine
import dev.brikk.ducklake.corpus.GoldenComparator

/**
 * [ReplayReadEngine] that mirrors corpus lake reads through a live compose Doris
 * FE+BE (`compose/smoke.sh --up-only`) over the mysql protocol.
 *
 * The engine is out-of-process and half containerized, so every resource has two
 * addresses. Metadata: [metadataRewriter] allocates a fresh database per corpus
 * file on the compose PostgreSQL; the oracle attaches via the host-mapped port
 * while the FE catalog uses the container-DNS form of the same database. Data: the
 * oracle writes parquet under `java.io.tmpdir`, which the `corpusReplayTest` task
 * pins to a host dir the compose bind-mounts into FE+BE at the same absolute path.
 */
internal class DorisReplayEngine(
    private val feJdbcUrl: String =
        System.getProperty("doris.corpus.fe.url", "jdbc:mysql://127.0.0.1:9030/?user=root"),
    private val pgHostUrl: String =
        System.getProperty("doris.corpus.pg.host.url", "jdbc:postgresql://localhost:9432/ducklake"),
    private val pgUser: String = System.getProperty("doris.corpus.pg.user", "ducklake"),
    private val pgPassword: String = System.getProperty("doris.corpus.pg.password", "ducklake"),
    /** Host/port the oracle attaches the rewritten metadata by. */
    private val pgOracleHostPort: String =
        System.getProperty("doris.corpus.pg.oracle.hostport", "host=localhost port=9432"),
    /** Host:port the FE reaches the same PostgreSQL by from inside the compose network. */
    private val pgFeHostPort: String =
        System.getProperty("doris.corpus.pg.fe.hostport", "trino-ducklake-postgres:5432"),
    scratchRoot: Path? = System.getProperty("doris.corpus.scratch.root")?.let(Path::of),
) : ReplayReadEngine {

    override val name: String = "doris"

    private val counter = AtomicInteger()

    /**
     * The corpus test chunks files across engine instances; a best-effort drop that
     * failed in an earlier instance must not collide with names allocated here.
     */
    private val runId: String = UUID.randomUUID().toString().replace("-", "")
    private val createdDatabases = mutableListOf<String>()
    private val createdCatalogs = linkedSetOf<String>()
    private val scratch = DorisReplayScratch(scratchRoot)

    private val fe: Connection = DriverManager.getConnection(feJdbcUrl)
    private val pg: Connection = DriverManager.getConnection(pgHostUrl, pgUser, pgPassword)

    private var currentCatalog: String? = null
    private var currentAlias: String? = null
    private var currentAttachment: OracleAttachment? = null
    private val databasesByTarget = mutableMapOf<String, String>()

    /** Allocates a fresh PostgreSQL database per original metadata path. */
    val metadataRewriter: (String) -> String = { _ ->
        val db = "corpus_doris_${runId}_" + counter.incrementAndGet()
        // Only a database this CREATE succeeded on is owned; a name collision must
        // never lead to adopting or dropping someone else's database.
        pg.createStatement().use { it.execute("CREATE DATABASE \"$db\"") }
        createdDatabases += db
        val target = "postgres:dbname=$db $pgOracleHostPort user=$pgUser password=$pgPassword"
        databasesByTarget[target] = db
        target
    }

    override fun connect(attachment: OracleAttachment) {
        currentCatalog = null
        currentAlias = null
        currentAttachment = null
        val db = databasesByTarget[attachment.metadataUri]
            ?: error("connect() requires an acknowledged metadata target owned by this replay engine")
        if (attachment.dataPath.isBlank()) {
            // Without DATA_PATH, DuckLake derived a default from a local metadata path
            // that has no engine-side equivalent. Staying disconnected makes accepts()
            // reject everything for this file.
            return
        }
        val catalog = "corpus_" + db.removePrefix("corpus_doris_")
        val scratchProperty = scratch.forTarget(db)?.let { path ->
            ", 'scratch.path'='${path.toString().replace("'", "''")}'"
        }.orEmpty()
        fe.createStatement().use { st ->
            // DETACH/re-ATTACH can return to an already allocated database. Only a
            // catalog whose earlier CREATE we owned may be replaced.
            if (catalog in createdCatalogs) {
                st.execute("DROP CATALOG IF EXISTS `$catalog`")
                createdCatalogs.remove(catalog)
            }
            st.execute(
                """
                CREATE CATALOG `$catalog` PROPERTIES (
                    'type' = 'ducklake',
                    'metadata.url' = 'jdbc:postgresql://$pgFeHostPort/$db',
                    'metadata.user' = '$pgUser',
                    'metadata.password' = '$pgPassword',
                    'storage.warehouse' = '${attachment.dataPath}',
                    -- Inlined reads are blocked by default; the compose cluster shares
                    -- warehouse storage between FE and BE, which is their precondition.
                    'experimental.inlined.reads' = 'true'$scratchProperty
                )
                """.trimIndent(),
            )
        }
        // Reached only after a successful CREATE: a failed one gives no authority to
        // drop the catalog, even though the name was generated here.
        createdCatalogs += catalog
        currentCatalog = catalog
        currentAlias = attachment.catalogAlias
        currentAttachment = attachment
    }

    /** accepts() and executeQuery() see the same SQL in turn; transpile it once. */
    private var lastGate: Pair<String, DorisCorpusDialect.Gate>? = null

    private fun gateFor(sql: String): DorisCorpusDialect.Gate {
        lastGate?.let { (cachedSql, gate) -> if (cachedSql == sql) return gate }
        val gate = DorisCorpusDialect.gate(sql)
        lastGate = sql to gate
        return gate
    }

    override fun accepts(sql: String): Boolean {
        val alias = currentAlias ?: return false
        val s = sql.trim()
        // Bare table names may be oracle-local temp tables the FE can't see.
        if (!Regex("\\b${Regex.escape(alias)}\\.").containsMatchIn(s)) {
            return false
        }
        // Catalog-scoped table functions (`<alias>.snapshots()`, …) have no Doris rewrite.
        if (Regex("\\b${Regex.escape(alias)}\\.\\w+\\s*\\(").containsMatchIn(s)) {
            return false
        }
        return gateFor(sql) is DorisCorpusDialect.Run
    }

    override fun executeQuery(sql: String): List<List<String?>> {
        val catalog = currentCatalog ?: error("connect() was not called")
        val alias = currentAlias ?: error("connect() was not called")
        val run = gateFor(sql) as? DorisCorpusDialect.Run
            ?: error("executeQuery on a non-runnable query: ${gateFor(sql)}")
        var s = run.dorisSql
        // <alias>.<t> with no further dot maps to DuckLake's default schema `main`.
        s = s.replace(Regex("\\b${Regex.escape(alias)}\\.(\\w+)\\.(\\w+)"), "$catalog.$1.$2")
        s = s.replace(Regex("\\b${Regex.escape(alias)}\\.(\\w+)\\b(?!\\.)"), "$catalog.main.$1")
        return try {
            fe.createStatement().use { st ->
                // The oracle writes to the lake between our reads and the FE caches
                // external-catalog objects (db/table lists, snapshot state); without a
                // refresh, newly created schemas go missing and recreated tables serve
                // stale rows.
                st.execute("REFRESH CATALOG $catalog")
                st.executeQuery(s).use { rs -> readRows(rs, run.dorisSql) }
            }
        } catch (e: SQLException) {
            throw classify(e)
        }
    }

    /** Documented connector gaps become [ReplayEngineSkip]; anything else propagates. */
    private fun classify(e: SQLException): Exception {
        val message = e.message ?: return e
        return when {
            // The connector doesn't surface DuckLake views, so their names don't resolve.
            message.contains("Unknown table") ||
                message.contains("Unknown database") ||
                message.contains("does not exist") ->
                ReplayEngineSkip("relation does not resolve (views/unsupported surface): " + firstLine(message))
            // The connector's deliberate rejections (type mapper, nested limits,
            // inlined-state guard, …).
            message.contains("not supported") || message.contains("Unsupported") ->
                ReplayEngineSkip("connector documented gap: " + firstLine(message))
            // The inlined-delete refusal doesn't use the "not supported" wording.
            message.contains("inlined DELETEs in the metadata catalog") ->
                ReplayEngineSkip("connector documented gap (inlined deletes): " + firstLine(message))
            // DuckDB functions with no Doris equivalent (typeof, uuid_extract_version, …).
            message.contains("Can not found function") ->
                ReplayEngineSkip("dialect: function missing in doris — " + firstLine(message))
            else -> e
        }
    }

    private fun readRows(rs: ResultSet, sql: String): List<List<String?>> {
        val md = rs.metaData
        val cols = md.columnCount
        // Doris BOOLEAN is TINYINT(1) on the wire; the driver reports BIT/BOOLEAN for
        // width 1, which lets the normalizer render 1/0 as true/false like the oracle.
        val logicalBoolean = (1..cols).map { i ->
            md.getColumnType(i) == Types.BIT || md.getColumnType(i) == Types.BOOLEAN
        }
        val rows = mutableListOf<List<String?>>()
        var inspectedTypes = false
        var types: List<InlinedTypeNode?>? = null
        while (rs.next()) {
            val values = (1..cols).map(rs::getObject)
            // The text-shape check only avoids a metadata read; column types come
            // from the catalog, never from the value.
            if (!inspectedTypes && values.any { it is String && it.trimStart().firstOrNull() in listOf('{', '[') }) {
                inspectedTypes = true
                val resolved = projectionTypes(sql)
                require(resolved == null || resolved.size == cols) { "Replay projection metadata width changed" }
                types = resolved
            }
            rows += values.mapIndexed { index, value -> normalizeReplayCell(value, logicalBoolean[index], types?.get(index)) }
        }
        return rows
    }

    private fun projectionTypes(sql: String): List<InlinedTypeNode?>? {
        val attachment = currentAttachment ?: return null
        val database = databasesByTarget.getValue(attachment.metadataUri)
        val base = URI(pgHostUrl.removePrefix("jdbc:"))
        require(base.scheme == "postgresql" && base.rawAuthority != null) { "Replay type inspection requires a PostgreSQL network URL" }
        val url = "jdbc:${base.scheme}://${base.rawAuthority}/$database" + (base.rawQuery?.let { "?$it" } ?: "")
        return DorisReplayProjection.types(sql, attachment.catalogAlias) { schema, table ->
            JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = url
                catalogDatabaseUser = pgUser
                catalogDatabasePassword = pgPassword
                dataPath = attachment.dataPath
                maxCatalogConnections = 1
            }).use { catalog ->
                catalog.readSession(Supplier {
                    val snapshot = catalog.currentSnapshotId
                    catalog.getTable(schema, table, snapshot)?.let { catalog.getTableColumns(it.tableId, snapshot) }
                })
            }
        }
    }

    private fun firstLine(message: String): String = message.lineSequence().first()

    override fun close() {
        var catalogsDropped = true
        for (catalog in createdCatalogs) {
            runCatching { fe.createStatement().use { it.execute("DROP CATALOG IF EXISTS `$catalog`") } }
                .onFailure {
                    catalogsDropped = false
                    System.err.println("corpus cleanup: DROP CATALOG $catalog failed: ${it.message}")
                }
        }
        runCatching { fe.close() }
        // FORCE because the FE plugin may still hold pooled connections to these
        // databases. Failures are non-fatal (names are run-unique) but reported.
        for (db in createdDatabases) {
            runCatching { pg.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$db\" WITH (FORCE)") } }
                .onFailure { System.err.println("corpus cleanup: DROP DATABASE $db failed: ${it.message}") }
        }
        runCatching { pg.close() }
        if (catalogsDropped) scratch.close()
        else System.err.println("corpus cleanup: retaining owned scratch after failed catalog removal")
    }
}

/** Floats and blobs use the golden renderer; everything else follows Doris boolean/temporal rules. */
internal fun normalizeReplayCell(value: Any?, logicalBoolean: Boolean = false): String? =
    if (value is Float || value is Double || value is ByteArray) GoldenComparator.renderCell(value)
    else DorisValueNormalizer.normalize(value, logicalBoolean)

internal fun normalizeReplayCell(value: Any?, logicalBoolean: Boolean, type: InlinedTypeNode?): String? =
    if (type != null && DorisNestedCellRenderer.canRender(type)) DorisNestedCellRenderer.render(value, type)
    else normalizeReplayCell(value, logicalBoolean)
