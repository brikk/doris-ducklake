package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig

/** Keep the historical pre-0.14 API isolated; never pretend unavailable pool knobs took effect. */
internal fun configureDuckLakePool(config: DucklakeCatalogConfig, properties: Map<String, String>) {
    val unavailable = listOf(DuckLakeConnectorProperties.CATALOG_MIN_IDLE_CONNECTIONS,
        DuckLakeConnectorProperties.CATALOG_CONNECTION_IDLE_TIMEOUT, DuckLakeConnectorProperties.CATALOG_CONNECTION_MAX_LIFETIME,
        DuckLakeConnectorProperties.CATALOG_CONNECTION_TIMEOUT)
    require(unavailable.none(properties::containsKey)) { "These catalog pool properties require the released catalog tier" }
    DuckLakePoolProperties.integer(properties, DuckLakeConnectorProperties.CATALOG_MAX_CONNECTIONS, 1)
        ?.let { config.maxCatalogConnections = it }
}
