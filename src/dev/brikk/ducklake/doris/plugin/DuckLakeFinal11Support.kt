package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import org.apache.doris.connector.spi.ConnectorSession

/** Compile-time contract shared by the released refusal and pinned-candidate implementations. */
internal interface DuckLakeFinal11Support : AutoCloseable {
    val enabled: Boolean
    fun configure(config: DucklakeCatalogConfig)
    fun view(catalog: JdbcDucklakeCatalog, session: ConnectorSession?): DucklakeCatalog
    fun validate(catalog: JdbcDucklakeCatalog)
}
