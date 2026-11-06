package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeCatalogFormat
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

internal class DuckLakePoolConfigurationTest {
    private val base = mapOf("metadata.url" to "jdbc:postgresql://127.0.0.1:1/lake", "metadata.user" to "user",
        "storage.warehouse" to "s3://bucket/lake")

    private fun connector(extra: Map<String, String> = emptyMap()) =
        DuckLakeConnector(base + extra, FakeConnectorContext("pool-config", 1L))

    @Test
    fun defaultsAreNotRetuned() {
        val config = connector().catalogConfiguration()
        val defaults = DucklakeCatalogConfig()
        assertThat(config.maxCatalogConnections).isEqualTo(defaults.maxCatalogConnections)
        assertThat(config.minIdleCatalogConnections).isEqualTo(defaults.minIdleCatalogConnections)
        assertThat(config.catalogConnectionIdleTimeout).isNull()
        assertThat(config.catalogConnectionMaxLifetime).isNull()
        assertThat(config.catalogConnectionTimeout).isEqualTo(defaults.catalogConnectionTimeout)
        assertThat(config.requiredCatalogFormat).isEqualTo(DucklakeCatalogFormat.V1_0)
    }

    @Test
    fun everyPoolSettingReachesTheLibraryConfig() {
        val config = connector(mapOf("catalog.max-connections" to "3", "catalog.min-idle-connections" to "0",
            "catalog.connection-idle-timeout" to "45s", "catalog.connection-max-lifetime" to "4m",
            "catalog.connection-timeout" to "500ms")).catalogConfiguration()
        assertThat(config.maxCatalogConnections).isEqualTo(3)
        assertThat(config.minIdleCatalogConnections).isZero()
        assertThat(config.catalogConnectionIdleTimeout).isEqualTo(Duration.ofSeconds(45))
        assertThat(config.catalogConnectionMaxLifetime).isEqualTo(Duration.ofMinutes(4))
        assertThat(config.catalogConnectionTimeout).isEqualTo(Duration.ofMillis(500))
    }

    @Test
    fun zeroAndBoundaryValuesRetainHikariSemantics() {
        val config = connector(mapOf("catalog.connection-idle-timeout" to "0ms", "catalog.connection-max-lifetime" to "0s",
            "catalog.connection-timeout" to "250ms")).catalogConfiguration()
        assertThat(config.catalogConnectionIdleTimeout).isEqualTo(Duration.ZERO)
        assertThat(config.catalogConnectionMaxLifetime).isEqualTo(Duration.ZERO)
        assertThat(config.catalogConnectionTimeout).isEqualTo(Duration.ofMillis(250))
        assertThat(connector(mapOf("catalog.connection-timeout" to "0ms")).catalogConfiguration().catalogConnectionTimeout)
            .isEqualTo(Duration.ZERO)
    }

    @Test
    fun partialOverridesLeaveAllOtherDefaultsAlone() {
        val config = connector(mapOf("catalog.connection-timeout" to "2 S")).catalogConfiguration()
        assertThat(config.maxCatalogConnections).isEqualTo(10)
        assertThat(config.minIdleCatalogConnections).isEqualTo(1)
        assertThat(config.catalogConnectionIdleTimeout).isNull()
        assertThat(config.catalogConnectionMaxLifetime).isNull()
        assertThat(config.catalogConnectionTimeout).isEqualTo(Duration.ofSeconds(2))
    }

    @Test
    fun jdbcUrlParametersArePassedThroughVerbatim() {
        val url = "jdbc:postgresql://proxy:6432/lake?prepareThreshold=0&ApplicationName=pool%20probe&options=-c%20search_path%3Dpublic"
        assertThat(connector(mapOf("metadata.url" to url)).catalogConfiguration().catalogDatabaseUrl).isEqualTo(url)
    }

    @Test
    fun invalidPoolSizesAndMinAboveMaxFailBeforeConnection() {
        for (extra in listOf(mapOf("catalog.max-connections" to "0"), mapOf("catalog.max-connections" to "2147483648"),
            mapOf("catalog.min-idle-connections" to "-1"), mapOf("catalog.min-idle-connections" to ""),
            mapOf("catalog.max-connections" to "1", "catalog.min-idle-connections" to "2"))) {
            assertThatThrownBy { connector(extra).preCreateValidation(null) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("catalog.")
        }
    }

    @Test
    fun badDurationsCannotSilentlyFallBackToHikariDefaults() {
        for ((name, value) in listOf("catalog.connection-idle-timeout" to "9s", "catalog.connection-max-lifetime" to "29999ms",
            "catalog.connection-timeout" to "249ms", "catalog.connection-timeout" to "1.5s",
            "catalog.connection-timeout" to "-1s", "catalog.connection-timeout" to "30",
            "catalog.connection-timeout" to "9223372036854775807d", "catalog.connection-timeout" to "")) {
            assertThatThrownBy { connector(mapOf(name to value)).preCreateValidation(null) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(name)
        }
    }

    @Test
    fun requiredPropertiesStillFailWithoutOpeningAPool() {
        val props = base - "metadata.url"
        assertThatThrownBy { DuckLakeConnector(props, FakeConnectorContext("missing-url", 1L)).catalogConfiguration() }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("metadata.url")
    }
}
