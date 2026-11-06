package dev.brikk.ducklake.doris.plugin

import java.io.IOException
import java.util.EnumSet
import java.util.function.Supplier

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeCatalogFormat
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

/** One [JdbcDucklakeCatalog] per Doris catalog, built on first use. */
class DuckLakeConnector internal constructor(
    properties: Map<String, String>,
    private val context: ConnectorContext,
) : Connector {
    private val properties = properties.toMap()
    private val catalogHolder = lazy { buildCatalog() }
    private val catalog: JdbcDucklakeCatalog by catalogHolder

    private val scanPlanProvider: DuckLakeScanPlanProvider by lazy {
        DuckLakeScanPlanProvider(catalog, DuckLakePathResolver(catalog, warehouse()), properties)
    }
    private val writePlanProvider: DuckLakeWritePlanProvider by lazy {
        DuckLakeWritePlanProvider(catalog, DuckLakePathResolver(catalog, warehouse()), properties)
    }
    private val procedureOps: DuckLakeProcedureOps by lazy { DuckLakeProcedureOps(catalog, properties) }

    override fun getMetadata(session: ConnectorSession?): ConnectorMetadata =
        DuckLakeConnectorMetadata(
            catalog,
            properties[DuckLakeConnectorProperties.ENABLE_MAPPING_TIMESTAMP_TZ].toBoolean(),
        )

    override fun getScanPlanProvider(): ConnectorScanPlanProvider = scanPlanProvider

    override fun getWritePlanProvider(): ConnectorWritePlanProvider = writePlanProvider

    override fun getProcedureOps(): ConnectorProcedureOps = procedureOps

    /**
     * Pushdown is attempted by fe-core unconditionally, INSERT is admitted through
     * [ConnectorWritePlanProvider.supportedOperations] and DDL routes straight to the metadata, so
     * only the MVCC flag (snapshot pinning and time travel) is declared. Other opt-ins (SHOW CREATE,
     * TopN lazy materialization, nested column prune, views, auto-analyze, metadata preload) have
     * prerequisites tracked in `dev-docs/TODO.md`.
     */
    override fun getCapabilities(): Set<ConnectorCapability> =
        EnumSet.of(ConnectorCapability.SUPPORTS_MVCC_SNAPSHOT)

    private fun warehouse(): String =
        DuckLakeConnectorProperties.requireString(properties, DuckLakeConnectorProperties.STORAGE_WAREHOUSE)

    private fun buildCatalog(): JdbcDucklakeCatalog {
        // DriverManager only discovers drivers on the system classloader; the plugin jar lives on a
        // child loader, so the Postgres driver has to be loaded explicitly to self-register.
        try {
            Class.forName("org.postgresql.Driver")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("PostgreSQL JDBC driver missing from plugin classpath", e)
        }
        val result = JdbcDucklakeCatalog(catalogConfiguration())
        var initialized = false
        try {
            // Checked before the first session: the library's own admission would refuse too, but
            // with a message that doesn't say why.
            requireFormat10(result)
            result.readSession(Supplier { result.currentSnapshotId })
            initialized = true
            return result
        } finally {
            if (!initialized) result.close()
        }
    }

    /** The complete JDBC URL is preserved, including driver query parameters. */
    internal fun catalogConfiguration(): DucklakeCatalogConfig =
        DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = DuckLakeConnectorProperties.requireString(
                properties, DuckLakeConnectorProperties.METADATA_URL,
            )
            catalogDatabaseUser = DuckLakeConnectorProperties.requireString(
                properties, DuckLakeConnectorProperties.METADATA_USER,
            )
            catalogDatabasePassword =
                properties.getOrDefault(DuckLakeConnectorProperties.METADATA_PASSWORD, "")
            dataPath = warehouse()
            DuckLakePoolProperties.configure(this, properties)
            // The library enforces this on every session, so a lake migrated to 1.1 under a live
            // connector is refused on its next read rather than scanned with the wrong semantics.
            requiredCatalogFormat = DucklakeCatalogFormat.V1_0
        }

    private fun requireFormat10(result: JdbcDucklakeCatalog) {
        if (result.getSpecVersion() == "1.1") {
            throw DorisConnectorException(
                "DuckLake 1.1 catalogs are not supported by the Doris connector: the BE reader cannot apply " +
                    "snapshot-tagged deletes or deletion vectors. Reads, writes and automatic migration are disabled.",
            )
        }
    }

    /** Surfaces admission failures before ExternalCatalog's database listing turns them into "missing database". */
    override fun preCreateValidation(context: ConnectorValidationContext?) {
        runCatching { catalog }.onFailure { failure ->
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
        }.getOrThrow()
    }

    /**
     * Closes the connection pool only. The Postgres driver is deliberately left registered: the plugin
     * classloader is shared by every DuckLake catalog in the FE and the driver's static initializer
     * runs once per loader, so deregistering here breaks every other catalog and any later CREATE
     * CATALOG with "No suitable driver".
     */
    @Throws(IOException::class)
    override fun close() {
        if (catalogHolder.isInitialized()) catalog.close()
    }

    internal fun properties(): Map<String, String> = properties

    internal fun context(): ConnectorContext = context
}
