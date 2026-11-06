package dev.brikk.ducklake.doris.plugin

import java.util.Optional

object DorisTestSupport {

    /** The connector properties every SPI-surface test builds from an isolated catalog. Mutable so tests can add `s3.*` keys. */
    @JvmStatic
    fun isolatedProperties(
        isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog,
    ): LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        put("type", "ducklake")
        put(DuckLakeConnectorProperties.METADATA_URL, isolated.jdbcUrl())
        put(DuckLakeConnectorProperties.METADATA_USER, isolated.user())
        put(DuckLakeConnectorProperties.METADATA_PASSWORD, isolated.password())
        put(
            DuckLakeConnectorProperties.STORAGE_WAREHOUSE,
            isolated.dataDir().toAbsolutePath().toString(),
        )
    }
}

internal fun <T : Any> Optional<T>.orFail(message: String): T =
    orElseThrow { AssertionError(message) }
