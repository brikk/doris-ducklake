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
 * [ReplayReadEngine] adapter: mirrors corpus lake reads through a LIVE compose
 * Doris FE+BE (`compose/smoke.sh --up-only`) over the mysql protocol.
 * Reference model: `TrinoReplayEngine` (trino-ducklake) — same
 * metadata-rewrite / alias-rewrite / error-classification shape, different
 * transport.
 *
 * Backend axis wiring (unlike Trino, our engine is out-of-process and half of
 * it is containerized — two views of everything):
 *  - METADATA: [metadataRewriter] allocates a fresh database per corpus file
 *    on the compose substrate PostgreSQL. The ORACLE attaches via the
 *    host-mapped port (`localhost:9432`); the FE catalog gets the
 *    container-DNS form (`trino-ducklake-postgres:5432`) of the SAME database.
 *  - DATA: the oracle writes parquet under `java.io.tmpdir`, which the
 *    `corpusReplayTest` gradle task pins to a host dir the compose bind-mounts
 *    into FE+BE at the SAME absolute path — host-side oracle and
 *    containerized BE read identical file paths.
 *
 * `accepts` gate: [DorisCorpusDialect] (transpile-first — brikk-sql's
 * `certify("doris")` translates DuckDB→Doris and returns the runnable verdict in
 * one call) AND alias-qualified reads only — bare table names may reference the
 * oracle's in-memory temp tables, not the lake.
 */
internal class DorisReplayEngine(
    private val feJdbcUrl: String =
        System.getProperty("doris.corpus.fe.url", "jdbc:mysql://127.0.0.1:9030/?user=root"),
    private val pgHostUrl: String =
        System.getProperty("doris.corpus.pg.host.url", "jdbc:postgresql://localhost:9432/ducklake"),
    private val pgUser: String = System.getProperty("doris.corpus.pg.user", "ducklake"),
    private val pgPassword: String = System.getProperty("doris.corpus.pg.password", "ducklake"),
    /** DuckLake ATTACH remainder host/port the ORACLE uses for the rewritten metadata. */
    private val pgOracleHostPort: String =
        System.getProperty("doris.corpus.pg.oracle.hostport", "host=localhost port=9432"),
    /** JDBC host:port the FE (inside the compose network) reaches the same PG by. */
    private val pgFeHostPort: String =
        System.getProperty("doris.corpus.pg.fe.hostport", "trino-ducklake-postgres:5432"),
    scratchRoot: Path? = System.getProperty("doris.corpus.scratch.root")?.let(Path::of),
) : ReplayReadEngine {

    override val name: String = "doris"

    private val counter = AtomicInteger()

    /**
     * Globally-unique naming: the corpus test chunks files across engine
     * instances, and a failed (best-effort) drop in a previous instance must
     * never collide with this one — first full-corpus contact lost 112 files
     * to exactly that ("database corpus_doris_1 already exists").
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

    /**
     * The corpus driver's metadata-rewrite hook: fresh PG database per
     * original metadata path; the oracle re-attaches on the host-mapped port.
     */
    val metadataRewriter: (String) -> String = { _ ->
        val db = "corpus_doris_${runId}_" + counter.incrementAndGet()
        // CREATE must succeed before this exact, internally generated name is
        // owned. Never adopt/drop an existing database on a naming collision.
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
            // No DATA_PATH on the attach: DuckLake derived a default from a
            // local metadata path that has no engine-side equivalent. Stay
            // disconnected — accepts() rejects everything for this file.
            return
        }
        // Re-ATTACH can return to an older target without calling the memoized rewriter again.
        val catalog = "corpus_" + db.removePrefix("corpus_doris_")
        val scratchProperty = scratch.forTarget(db)?.let { path ->
            ", 'scratch.path'='${path.toString().replace("'", "''")}'"
        }.orEmpty()
        fe.createStatement().use { st ->
            // DETACH/reattach can reconnect this engine to the same allocated
            // database. Replace only a catalog whose prior CREATE we owned.
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
                    -- Opt into the experimental inlined-data read path: the corpus
                    -- compose cluster shares warehouse storage FE<->BE (the crutch's
                    -- precondition), so we exercise + validate the feature here even
                    -- though it's hard-blocked by default in real deployments.
                    'experimental.inlined.reads' = 'true'$scratchProperty
                )
                """.trimIndent(),
            )
        }
        // A failed CREATE (including an existing-name refusal) gives us no
        // authority to drop that catalog, even though the name was generated here.
        createdCatalogs += catalog
        currentCatalog = catalog
        currentAlias = attachment.catalogAlias
        currentAttachment = attachment
    }

    /** Cache the last gate decision so accepts() + executeQuery() transpile once. */
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
        // Must reference the lake by alias — bare names may be oracle-local
        // temp tables (invisible to the FE by design).
        if (!Regex("\\b${Regex.escape(alias)}\\.").containsMatchIn(s)) {
            return false
        }
        // Catalog-scoped table functions (`<alias>.snapshots()`, …) have no
        // Doris-side rewrite.
        if (Regex("\\b${Regex.escape(alias)}\\.\\w+\\s*\\(").containsMatchIn(s)) {
            return false
        }
        // Transpile-first gate: runnable only if brikk-sql can faithfully
        // translate the DuckDB query to Doris (see DorisCorpusDialect).
        return gateFor(sql) is DorisCorpusDialect.Run
    }

    override fun executeQuery(sql: String): List<List<String?>> {
        val catalog = currentCatalog ?: error("connect() was not called")
        val alias = currentAlias ?: error("connect() was not called")
        // The transpiled Doris SQL (DuckDB-isms rewritten, time travel mapped,
        // ORDER BY ALL handled) — accepts() already confirmed this is a Run.
        val run = gateFor(sql) as? DorisCorpusDialect.Run
            ?: error("executeQuery on a non-runnable query: ${gateFor(sql)}")
        var s = run.dorisSql
        // <alias>.<schema>.<t> → <catalog>.<schema>.<t>; then <alias>.<t>
        // (no further dot) → <catalog>.main.<t> (DuckLake's default schema).
        s = s.replace(Regex("\\b${Regex.escape(alias)}\\.(\\w+)\\.(\\w+)"), "$catalog.$1.$2")
        s = s.replace(Regex("\\b${Regex.escape(alias)}\\.(\\w+)\\b(?!\\.)"), "$catalog.main.$1")
        return try {
            fe.createStatement().use { st ->
                // The oracle writes to the lake BETWEEN our reads; Doris's
                // external-catalog objects (db/table lists, snapshot state)
                // are FE-cached by design and REFRESH is the documented
                // freshness contract for externally-written catalogs. Without
                // it, first contact showed schemas created after CREATE
                // CATALOG missing and drop/recreate cycles serving stale rows.
                st.execute("REFRESH CATALOG $catalog")
                st.executeQuery(s).use { rs -> readRows(rs, run.dorisSql) }
            }
        } catch (e: SQLException) {
            throw classify(e)
        }
    }

    /**
     * Error classification, mirroring the Trino adapter's tiers: documented
     * gaps become [ReplayEngineSkip]; everything else fails loudly.
     */
    private fun classify(e: SQLException): Exception {
        val message = e.message ?: return e
        return when {
            // Our connector doesn't surface DuckLake VIEWs (nor DuckDB-dialect
            // views); their names don't resolve FE-side by design.
            message.contains("Unknown table") ||
                message.contains("Unknown database") ||
                message.contains("does not exist") ->
                ReplayEngineSkip("relation does not resolve (views/unsupported surface): " + firstLine(message))
            // The connector's own deliberate rejection gates (type mapper,
            // nested v1 limits, inlined-state guard, …) fail cleanly by design.
            message.contains("not supported") || message.contains("Unsupported") ->
                ReplayEngineSkip("connector documented gap: " + firstLine(message))
            // Inlined-DELETE guard (Stage 2): the connector fails loud on a table
            // whose deletes live in ducklake_inlined_delete_* rather than a delete
            // file — a documented gap (its message predates the "not supported"
            // wording the branch above keys on), so classify it as an engine-skip.
            message.contains("inlined DELETEs in the metadata catalog") ->
                ReplayEngineSkip("connector documented gap (inlined deletes, Stage 2): " + firstLine(message))
            // DuckDB functions with no Doris equivalent (typeof,
            // uuid_extract_version, …): dialect gap, not a correctness signal —
            // the generic classification beats an ever-growing deny-list.
            message.contains("Can not found function") ->
                ReplayEngineSkip("dialect: function missing in doris — " + firstLine(message))
            else -> e
        }
    }

    private fun readRows(rs: ResultSet, sql: String): List<List<String?>> {
        val md = rs.metaData
        val cols = md.columnCount
        // Doris BOOLEAN is TINYINT(1) on the wire; the driver exposes it as
        // BIT/BOOLEAN jdbc types when it detects width 1 — thread that to the
        // normalizer so 1/0 renders true/false like the oracle's booleans.
        val logicalBoolean = (1..cols).map { i ->
            md.getColumnType(i) == Types.BIT || md.getColumnType(i) == Types.BOOLEAN
        }
        val rows = mutableListOf<List<String?>>()
        var inspectedTypes = false
        var types: List<InlinedTypeNode?>? = null
        while (rs.next()) {
            val values = (1..cols).map(rs::getObject)
            // Text shape merely avoids unnecessary metadata reads. It NEVER establishes a type.
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
        // FORCE: the FE plugin may hold pooled connections to the corpus DBs.
        // Failures are non-fatal (names are run-unique) but must be VISIBLE —
        // silent drop failures cost 112 files on first full-corpus contact.
        for (db in createdDatabases) {
            runCatching { pg.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$db\" WITH (FORCE)") } }
                .onFailure { System.err.println("corpus cleanup: DROP DATABASE $db failed: ${it.message}") }
        }
        runCatching { pg.close() }
        if (catalogsDropped) scratch.close()
        else System.err.println("corpus cleanup: retaining owned scratch after failed catalog removal")
    }
}

/** Replay's pinned native float/blob renderer, retaining Doris Boolean/temporal rules. */
internal fun normalizeReplayCell(value: Any?, logicalBoolean: Boolean = false): String? =
    if (value is Float || value is Double || value is ByteArray) GoldenComparator.renderCell(value)
    else DorisValueNormalizer.normalize(value, logicalBoolean)

internal fun normalizeReplayCell(value: Any?, logicalBoolean: Boolean, type: InlinedTypeNode?): String? =
    if (type != null && DorisNestedCellRenderer.canRender(type)) DorisNestedCellRenderer.render(value, type)
    else normalizeReplayCell(value, logicalBoolean)
