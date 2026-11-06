package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig

/** Pool knobs available since catalog 0.14; the ordinary 0.15.1 pin is intentionally unchanged. */
internal fun configureDuckLakePool(config: DucklakeCatalogConfig, properties: Map<String, String>) {
    val max = DuckLakePoolProperties.integer(properties, DuckLakeConnectorProperties.CATALOG_MAX_CONNECTIONS, 1)
    val min = DuckLakePoolProperties.integer(properties, DuckLakeConnectorProperties.CATALOG_MIN_IDLE_CONNECTIONS, 0)
    require((min ?: config.minIdleCatalogConnections) <= (max ?: config.maxCatalogConnections)) {
        "DuckLake property '${DuckLakeConnectorProperties.CATALOG_MIN_IDLE_CONNECTIONS}' must not exceed " +
            "'${DuckLakeConnectorProperties.CATALOG_MAX_CONNECTIONS}'"
    }
    max?.let { config.maxCatalogConnections = it }
    min?.let { config.minIdleCatalogConnections = it }
    DuckLakePoolProperties.duration(properties, DuckLakeConnectorProperties.CATALOG_CONNECTION_IDLE_TIMEOUT, 10_000)
        ?.let { config.catalogConnectionIdleTimeout = it }
    DuckLakePoolProperties.duration(properties, DuckLakeConnectorProperties.CATALOG_CONNECTION_MAX_LIFETIME, 30_000)
        ?.let { config.catalogConnectionMaxLifetime = it }
    DuckLakePoolProperties.duration(properties, DuckLakeConnectorProperties.CATALOG_CONNECTION_TIMEOUT, 250)
        ?.let { config.catalogConnectionTimeout = it }
}
