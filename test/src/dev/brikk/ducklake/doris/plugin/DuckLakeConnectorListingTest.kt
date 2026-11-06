package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.apache.doris.connector.spi.ConnectorColumn
import org.apache.doris.connector.spi.ConnectorContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Read-only metadata path behind `SHOW DATABASES`, `SHOW TABLES` and `DESC`, driven through
 * [DuckLakeConnectorProvider.create] so provider/connector wiring regressions surface here.
 */
internal class DuckLakeConnectorListingTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeTestCatalogBootstrap.bootstrap(server, "listing")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            server.close()
        }
    }

    @Test
    fun providerValidatesRequiredProperties() {
        val provider = DuckLakeConnectorProvider()
        assertThatThrownBy { provider.validateProperties(mapOf("type" to "ducklake")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("metadata.url")
    }

    @Test
    fun providerAcceptsEngineInjectedProperties() {
        // Doris injects `enable.mapping.varbinary` into every CREATE CATALOG.
        val provider = DuckLakeConnectorProvider()
        provider.validateProperties(
            mapOf(
                DuckLakeConnectorProperties.METADATA_URL to "jdbc:postgresql://x/y",
                DuckLakeConnectorProperties.METADATA_USER to "u",
                DuckLakeConnectorProperties.STORAGE_WAREHOUSE to "file:///tmp",
                "enable.mapping.varbinary" to "false",
            ),
        )
    }

    @Test
    @Throws(Exception::class)
    fun listsSeededSchemasAndTables() {
        val provider = DuckLakeConnectorProvider()
        assertThat(provider.type).isEqualTo("ducklake")

        val properties = DorisTestSupport.isolatedProperties(isolated)

        provider.validateProperties(properties)

        val ctx: ConnectorContext = FakeConnectorContext("dl", 1L)
        provider.create(properties, ctx).use { connector ->
            val metadata = connector.getMetadata(null)

            // DuckLake also bootstraps an implicit `main` schema, hence contains rather than exactly.
            assertThat(metadata.listDatabaseNames(null))
                .contains("sales", "analytics")

            assertThat(metadata.databaseExists(null, "sales")).isTrue()
            assertThat(metadata.databaseExists(null, "does_not_exist")).isFalse()

            assertThat(metadata.listTableNames(null, "sales"))
                .containsExactlyInAnyOrder(
                    "orders", "customers", "returns_file", "returns_inline", "by_region", "by_name_bucket",
                )
            assertThat(metadata.listTableNames(null, "analytics"))
                .containsExactly("events")
            assertThat(metadata.listTableNames(null, "does_not_exist"))
                .isEmpty()

            val orders = metadata.getTableHandle(null, "sales", "orders")
                .orFail("expected sales.orders handle")
            assertThat(metadata.getTableHandle(null, "sales", "nope")).isEmpty()
            assertThat(metadata.getTableHandle(null, "nope", "orders")).isEmpty()

            val ordersSchema = metadata.getTableSchema(null, orders)
            assertThat(ordersSchema.tableName).isEqualTo("orders")
            assertThat(ordersSchema.columns)
                .extracting<String>(ConnectorColumn::getName)
                .containsExactly("id", "total")
            assertThat(ordersSchema.columns)
                .extracting<String> { c -> c.type.typeName }
                .containsExactly("INT", "DOUBLE")

            val ordersHandles = metadata.getColumnHandles(null, orders)
            assertThat(ordersHandles.keys).containsExactly("id", "total")

            // analytics.events covers timestamp and varchar through DuckLakeTypeMapping.
            val events = metadata.getTableHandle(null, "analytics", "events")
                .orFail("expected analytics.events handle")
            assertThat(metadata.getTableSchema(null, events).columns)
                .extracting<String> { c -> c.type.typeName }
                .containsExactly("DATETIMEV2", "STRING")
        }
    }

    @Test
    @Throws(Exception::class)
    fun appliesProjectionPushdown() {
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val orders = metadata.getTableHandle(null, "sales", "orders")
                    .orFail("expected sales.orders handle")
                val total = metadata.getColumnHandles(null, orders)["total"]
                    ?: error("expected a 'total' column handle")

                // The handle records projected column ids so the engine's fixed-point loop stops.
                val result = metadata.applyProjection(null, orders, listOf(total))
                assertThat(result.isPresent).isTrue()
                val applied = result.get()
                assertThat(applied.assignments).hasSize(1)
                val newHandle = applied.handle.asDuckLakeHandle<DuckLakeTableHandle>()
                assertThat(newHandle.projectedColumnIds)
                    .containsExactly((total as DuckLakeColumnHandle).columnId)

                // Re-applying the same projection, or an empty one, opts out.
                assertThat(metadata.applyProjection(null, newHandle, listOf(total)).isPresent)
                    .isFalse()
                assertThat(metadata.applyProjection(null, orders, emptyList()).isPresent)
                    .isFalse()
            }
    }

    @Test
    @Throws(Exception::class)
    fun reportsTableStatistics() {
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val orders = metadata.getTableHandle(null, "sales", "orders")
                    .orFail("expected sales.orders handle")
                val stats = metadata.getTableStatistics(null, orders)
                    .orFail("expected stats for sales.orders")
                // sales.orders is seeded with 3 rows.
                assertThat(stats.rowCount).isEqualTo(3L)
                assertThat(stats.dataSize).isPositive()
            }
    }
}
