package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeCatalogFormat
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.ConnectorContext

/** Release adoption does not promote the separately qualified experimental 1.1 reader. */
internal class DuckLakeFinal11(properties: Map<String, String>, context: ConnectorContext) : DuckLakeFinal11Support {
    override val enabled = properties[DuckLakeConnectorProperties.ENABLE_FINAL11_READS].toBoolean()

    override fun configure(config: DucklakeCatalogConfig) {
        check(!enabled) { "experimental.final11.reads requires the checksum-attested R07 candidate build" }
        config.requiredCatalogFormat = DucklakeCatalogFormat.V1_0
    }

    override fun view(catalog: JdbcDucklakeCatalog, session: ConnectorSession?): DucklakeCatalog =
        error("Final-1.1 is unavailable in the released dependency build")

    override fun close() = Unit
    override fun validate(catalog: JdbcDucklakeCatalog) = Unit
}
