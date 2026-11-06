package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import java.time.Duration
import java.util.Locale

/** Connection-pool knobs. Every value is optional: an absent setting keeps the catalog library's default. */
internal object DuckLakePoolProperties {
    private val duration = Regex("^(\\d+)\\s*(ms|s|m|h|d)$", RegexOption.IGNORE_CASE)
    private val multipliers = mapOf("ms" to 1L, "s" to 1000L, "m" to 60_000L, "h" to 3_600_000L, "d" to 86_400_000L)

    fun configure(config: DucklakeCatalogConfig, properties: Map<String, String>) {
        val max = integer(properties, DuckLakeConnectorProperties.CATALOG_MAX_CONNECTIONS, 1)
        val min = integer(properties, DuckLakeConnectorProperties.CATALOG_MIN_IDLE_CONNECTIONS, 0)
        require((min ?: config.minIdleCatalogConnections) <= (max ?: config.maxCatalogConnections)) {
            "DuckLake property '${DuckLakeConnectorProperties.CATALOG_MIN_IDLE_CONNECTIONS}' must not exceed " +
                "'${DuckLakeConnectorProperties.CATALOG_MAX_CONNECTIONS}'"
        }
        max?.let { config.maxCatalogConnections = it }
        min?.let { config.minIdleCatalogConnections = it }
        duration(properties, DuckLakeConnectorProperties.CATALOG_CONNECTION_IDLE_TIMEOUT, 10_000)
            ?.let { config.catalogConnectionIdleTimeout = it }
        duration(properties, DuckLakeConnectorProperties.CATALOG_CONNECTION_MAX_LIFETIME, 30_000)
            ?.let { config.catalogConnectionMaxLifetime = it }
        duration(properties, DuckLakeConnectorProperties.CATALOG_CONNECTION_TIMEOUT, 250)
            ?.let { config.catalogConnectionTimeout = it }
    }

    fun integer(properties: Map<String, String>, name: String, minimum: Int): Int? = properties[name]?.let { text ->
        val value = text.trim().toIntOrNull()
        require(value != null && value >= minimum) { "DuckLake property '$name' must be an integer >= $minimum" }
        value
    }

    fun duration(properties: Map<String, String>, name: String, minimumMillis: Long): Duration? = properties[name]?.let { text ->
        val match = duration.matchEntire(text.trim())
        requireNotNull(match) { "DuckLake property '$name' requires a whole-number duration in ms, s, m, h or d" }
        val count = match.groupValues[1].toLongOrNull()
        requireNotNull(count) { "DuckLake property '$name' exceeds the supported duration range" }
        val millis = try {
            Math.multiplyExact(count, multipliers.getValue(match.groupValues[2].lowercase(Locale.ROOT)))
        } catch (overflow: ArithmeticException) {
            throw IllegalArgumentException("DuckLake property '$name' exceeds the supported duration range", overflow)
        }
        require(millis == 0L || millis >= minimumMillis) {
            "DuckLake property '$name' must be zero or at least ${minimumMillis}ms (HikariCP limit)"
        }
        Duration.ofMillis(millis)
    }
}
