package dev.brikk.ducklake.doris.plugin

import java.io.IOException
import java.util.EnumSet
import java.util.function.Supplier

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog

import org.apache.doris.connector.spi.Connector
import org.apache.doris.connector.spi.ConnectorCapability
import org.apache.doris.connector.spi.ConnectorMetadata
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.procedure.ConnectorProcedureOps
import org.apache.doris.connector.spi.scan.ConnectorScanPlanProvider
import org.apache.doris.connector.spi.write.ConnectorWritePlanProvider
import org.apache.doris.connector.spi.ConnectorContext
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.ConnectorValidationContext

/**
 * Read-side DuckLake [Connector]. Owns a single [JdbcDucklakeCatalog]
 * lazily constructed from the catalog properties on first metadata call. Capability
 * set will grow as features land (see `dev-docs/TODO-read.md`).
 */
class DuckLakeConnector internal constructor(
    properties: Map<String, String>,
    private val context: ConnectorContext,
) : Connector {
    private val properties = java.util.Map.copyOf(properties)
    private val final11: DuckLakeFinal11Support = DuckLakeFinal11(this.properties, context)

    @Volatile
    private var catalog: JdbcDucklakeCatalog? = null

    @Volatile
    private var scanPlanProvider: DuckLakeScanPlanProvider? = null

    @Volatile
    private var writePlanProvider: DuckLakeWritePlanProvider? = null

    @Volatile
    private var procedureOps: DuckLakeProcedureOps? = null

    override fun getMetadata(session: ConnectorSession?): ConnectorMetadata =
        DuckLakeConnectorMetadata(
            catalog().let { if (final11.enabled) final11.view(it, session) else it },
            properties[DuckLakeConnectorProperties.ENABLE_MAPPING_TIMESTAMP_TZ].toBoolean(),
        )

    override fun getScanPlanProvider(): ConnectorScanPlanProvider {
        var local = scanPlanProvider
        if (local == null) {
            synchronized(this) {
                local = scanPlanProvider
                if (local == null) {
                    val cat = catalog()
                    val warehouse = DuckLakeConnectorProperties.requireString(
                        properties, DuckLakeConnectorProperties.STORAGE_WAREHOUSE,
                    )
                    local = DuckLakeScanPlanProvider(
                        cat,
                        DuckLakePathResolver(cat, warehouse),
                        properties,
                        if (final11.enabled) { session -> final11.view(cat, session) } else null,
                    )
                    scanPlanProvider = local
                }
            }
        }
        return local!!
    }

    override fun getWritePlanProvider(): ConnectorWritePlanProvider {
        requireWritableTier()
        var local = writePlanProvider
        if (local == null) {
            synchronized(this) {
                local = writePlanProvider
                if (local == null) {
                    val cat = catalog()
                    val warehouse = DuckLakeConnectorProperties.requireString(
                        properties, DuckLakeConnectorProperties.STORAGE_WAREHOUSE,
                    )
                    local = DuckLakeWritePlanProvider(
                        cat,
                        DuckLakePathResolver(cat, warehouse),
                        properties,
                    )
                    writePlanProvider = local
                }
            }
        }
        return local!!
    }

    /**
     * Table-procedure surface (`ALTER TABLE t EXECUTE <proc>(...)`). Lazily/singleton-built like
     * the scan/write providers so the catalog is only constructed on first real use. Today it
     * exposes `expire_snapshots` — see [DuckLakeProcedureOps] for the catalog-wide-vs-table
     * semantics.
     */
    override fun getProcedureOps(): ConnectorProcedureOps {
        requireWritableTier()
        var local = procedureOps
        if (local == null) {
            synchronized(this) {
                local = procedureOps
                if (local == null) {
                    local = DuckLakeProcedureOps(catalog(), properties)
                    procedureOps = local
                }
            }
        }
        return local!!
    }

    /**
     * Capabilities after the P6 iceberg-cutover SPI (doris `branch-catalog-spi`
     * @ 8b391c7, connector-capability-unification): the declarative capability
     * enum was gutted — pushdown/insert/create-table/time-travel/statistics
     * constants were **deleted** because fe-core never consumed them. Pushdown
     * is now attempted unconditionally (`applyFilter`/`applyProjection` on the
     * metadata), INSERT admission comes from
     * `ConnectorWritePlanProvider.supportedOperations()` (default `{INSERT}`),
     * and DDL routes through `ConnectorTableOps`/`ConnectorSchemaOps` directly.
     *
     * `SUPPORTS_MVCC_SNAPSHOT` is the one surviving flag that matters to us —
     * it gates MVCC table creation in `PluginDrivenExternalDatabase` (snapshot
     * pinning + time travel). New opt-ins we deliberately do NOT declare yet:
     * `SUPPORTS_SHOW_CREATE_DDL`, `SUPPORTS_TOPN_LAZY_MATERIALIZE`,
     * `SUPPORTS_NESTED_COLUMN_PRUNE` (needs per-field ids),
     * `SUPPORTS_VIEW`, `SUPPORTS_COLUMN_AUTO_ANALYZE`,
     * `SUPPORTS_METADATA_PRELOAD` — each has behavioral prerequisites tracked
     * in `dev-docs/TODO-read.md`.
     */
    override fun getCapabilities(): Set<ConnectorCapability> =
        EnumSet.of(
            ConnectorCapability.SUPPORTS_MVCC_SNAPSHOT,
        )

    private fun catalog(): JdbcDucklakeCatalog {
        var local = catalog
        if (local == null) {
            synchronized(this) {
                local = catalog
                if (local == null) {
                    local = buildCatalog()
                    catalog = local
                }
            }
        }
        return local!!
    }

    private fun buildCatalog(): JdbcDucklakeCatalog {
        // Force Postgres JDBC driver registration on the plugin classloader. DriverManager's registry
        // is populated by ServiceLoader at JVM startup with the *system* classloader; our plugin jar
        // is on a child classloader, so its META-INF/services/java.sql.Driver isn't discovered without
        // an explicit Class.forName from inside the plugin. The static initializer self-registers the
        // driver once per classloader; it stays registered for the (long-lived, shared-across-catalogs)
        // plugin classloader's lifetime — see close()'s note on why we must NOT deregister it.
        try {
            Class.forName("org.postgresql.Driver")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("PostgreSQL JDBC driver missing from plugin classpath", e)
        }
        val config = DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = DuckLakeConnectorProperties.requireString(
                properties, DuckLakeConnectorProperties.METADATA_URL,
            )
            catalogDatabaseUser = DuckLakeConnectorProperties.requireString(
                properties, DuckLakeConnectorProperties.METADATA_USER,
            )
            catalogDatabasePassword =
                properties.getOrDefault(DuckLakeConnectorProperties.METADATA_PASSWORD, "")
            dataPath = DuckLakeConnectorProperties.requireString(
                properties, DuckLakeConnectorProperties.STORAGE_WAREHOUSE,
            )
        }
        final11.configure(config)
        val result = JdbcDucklakeCatalog(config)
        var initialized = false
        try {
            result.readSession(Supplier { requireQualifiedFormat(result) })
            initialized = true
            return result
        } finally {
            if (!initialized) result.close()
        }
    }

    private fun requireQualifiedFormat(result: JdbcDucklakeCatalog) {
        if (result.getSpecVersion() == "1.1" && !final11.enabled) {
            throw DorisConnectorException(
                "DuckLake 1.1 reads are not qualified by the Doris connector. " +
                    "A library read opt-in is not connector scan/CDC qualification; " +
                    "1.1 writes and automatic migration are also disabled.",
            )
        }
    }

    // The real pre-create SPI surfaces admission failures before ExternalCatalog's
    // database-listing catch can turn them into a misleading missing database.
    override fun preCreateValidation(context: ConnectorValidationContext?) {
        runCatching {
            val source = catalog()
            if (final11.enabled) final11.validate(source)
        }.onFailure { failure ->
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
        }.getOrThrow()
    }

    private fun requireWritableTier() {
        if (final11.enabled) throw DorisConnectorException("Final-1.1 is read-only: writes and maintenance are disabled")
    }

    @Throws(IOException::class)
    override fun close() {
        // Close the catalog's connection pool only. We deliberately do NOT deregister the Postgres
        // JDBC driver here.
        //
        // close() is called on catalog DROP / re-init (frequent), NOT on plugin unload. The plugin
        // classloader is built ONCE at FE startup and SHARED across every DuckLake catalog, so the
        // driver registration is shared too. Deregistering it on one catalog's close breaks every
        // other live DuckLake catalog AND any subsequent CREATE CATALOG — the PG static initializer
        // only self-registers once per classloader, so it cannot re-register after a deregister,
        // giving "No suitable driver". (Caught by the compose smoke: DROP CATALOG then CREATE CATALOG
        // failed with exactly that.) And there is no per-catalog leak to fix here — the shared loader
        // lives for the process, so the registration is process-lifetime by nature.
        //
        // The only real leak is on plugin RELOAD/UNLOAD (fe-core tears the loader down but the
        // still-registered driver pins it). That needs a plugin-unload hook, not Connector.close();
        // deferred — see dev-docs/TODO-read.md. Low severity (reloads are rare admin actions).
        try { catalog?.close() } finally { final11.close() }
    }

    internal fun properties(): Map<String, String> = properties

    internal fun context(): ConnectorContext = context
}
