package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.apache.doris.connector.spi.ConnectorMetadata
import org.apache.doris.connector.spi.ConnectorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Asserts the [ConnectorType] the FE metadata surfaces for every DuckLake temporal
 * type, using a catalog written by a real DuckDB writer. Unlike [DuckLakeTypeMappingTest],
 * which feeds the mapper hand-written type strings, this checks the strings DuckLake
 * actually stores in `ducklake_column.column_type`, including the bare
 * `list`/`struct`/`map` parent rows that `JdbcDucklakeCatalog.resolveColumnType`
 * has to reconstruct.
 */
internal class DuckLakeTemporalTypeAuditTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeAuditFixtureSeeder.seed(
                server,
                "temporalaudit",
                listOf(
                    "CREATE SCHEMA dl.audit",
                    // DuckDB has no parameterized TIMESTAMP(n); precision variants are
                    // the distinct TIMESTAMP_S / TIMESTAMP_MS / TIMESTAMP_NS types.
                    "CREATE TABLE dl.audit.temporal_all (" +
                        "c_date DATE, c_time TIME, c_timetz TIMETZ, " +
                        "c_ts TIMESTAMP, c_ts_s TIMESTAMP_S, c_ts_ms TIMESTAMP_MS, c_ts_ns TIMESTAMP_NS, " +
                        "c_tstz TIMESTAMP WITH TIME ZONE, c_interval INTERVAL)",
                    // Nested temporal types exercise the type-string reconstruction path.
                    "CREATE TABLE dl.audit.temporal_nested (" +
                        "instants TIMESTAMPTZ[], " +
                        "evt STRUCT(occurred TIMESTAMPTZ, wall TIMESTAMP), " +
                        "by_day MAP(DATE, TIMESTAMPTZ))",
                ),
            )
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            server.close()
        }
    }

    private inline fun <R> withMetadata(block: (ConnectorMetadata) -> R): R {
        val properties = DorisTestSupport.isolatedProperties(isolated)
        return DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L))
            .use { connector -> block(connector.getMetadata(null)) }
    }

    private fun schemaTypes(md: ConnectorMetadata, table: String): Map<String, ConnectorType> {
        val handle = md.getTableHandle(null, "audit", table).orFail("expected audit.$table handle")
        return md.getTableSchema(null, handle).columns.associate { it.name to it.type }
    }

    @Test
    fun timestamptzDegradesToNaiveDatetimeUntilTheBeSupportsTimestamptz() {
        withMetadata { md ->
            val types = schemaTypes(md, "temporal_all")
            val tstz = types.getValue("c_tstz")

            // The 4.1.0 BE parquet reader cannot read a TIMESTAMP_MICROS(isAdjustedToUtc)
            // column into a TimeStampTz slot ("Unsupported type change: DateTimeV2 =>
            // TimeStampTz"), so timestamptz is mapped to DATETIMEV2(6). The stored value
            // is UTC micros, so only the zone-aware typing is lost. Switch back to
            // TIMESTAMPTZ once the BE supports the conversion.
            assertThat(tstz.typeName).isEqualTo("DATETIMEV2")
            // DuckLake timestamptz is micros-only.
            assertThat(tstz.precision).isEqualTo(6)
        }
    }

    @Test
    fun wallClockTimestampVariantsCarryTheirStorageScale() {
        withMetadata { md ->
            val types = schemaTypes(md, "temporal_all")

            // TIMESTAMP without time zone is wall-clock, so naive DATETIMEV2 with the
            // storage variant's scale is the faithful mapping.
            assertThat(types.getValue("c_ts").typeName).isEqualTo("DATETIMEV2")
            assertThat(types.getValue("c_ts").precision).isEqualTo(6)
            assertThat(types.getValue("c_ts_s").precision).isEqualTo(0)
            assertThat(types.getValue("c_ts_ms").precision).isEqualTo(3)

            assertThat(types.getValue("c_date").typeName).isEqualTo("DATEV2")
        }
    }

    @Test
    fun timestampNanosSurfacesAsDatetime() {
        withMetadata { md ->
            assertThat(schemaTypes(md, "temporal_all").getValue("c_ts_ns").typeName)
                .isEqualTo("DATETIMEV2")
        }
    }

    @Test
    fun timestampNanosMustClampToDorisMaxDatetimeScale() {
        withMetadata { md ->
            val tsNs = schemaTypes(md, "temporal_all").getValue("c_ts_ns")
            assertThat(tsNs.typeName).isEqualTo("DATETIMEV2")
            // Doris datetimev2 scale is 0..6, so nanos clamp to 6 (lossy by design).
            assertThat(tsNs.precision).isEqualTo(6)
        }
    }

    @Test
    fun timeAndIntervalDegradeToStringByDesign() {
        withMetadata { md ->
            val types = schemaTypes(md, "temporal_all")

            // Doris has no first-class TIME/TIMETZ/INTERVAL, so the mapping degrades
            // them to STRING rather than failing the whole table. If one of these
            // changes (e.g. TIME -> BIGINT micros), revisit all three together.
            assertThat(types.getValue("c_time").typeName).isEqualTo("STRING")
            assertThat(types.getValue("c_timetz").typeName).isEqualTo("STRING")
            assertThat(types.getValue("c_interval").typeName).isEqualTo("STRING")
        }
    }

    @Test
    fun nestedTimestamptzReconstructsConsistentlyWithTopLevel() {
        withMetadata { md ->
            val types = schemaTypes(md, "temporal_nested")

            // Nested timestamptz must degrade to DATETIMEV2 the same way as the
            // top-level column; the reconstruction path must not diverge from it.
            val instants = types.getValue("instants")
            assertThat(instants.typeName).isEqualTo("ARRAY")
            assertThat(instants.children[0].typeName).isEqualTo("DATETIMEV2")
            assertThat(instants.children[0].precision).isEqualTo(6)

            val evt = types.getValue("evt")
            assertThat(evt.typeName).isEqualTo("STRUCT")
            assertThat(evt.fieldNames).containsExactly("occurred", "wall")
            assertThat(evt.children.map { it.typeName })
                .containsExactly("DATETIMEV2", "DATETIMEV2")

            val byDay = types.getValue("by_day")
            assertThat(byDay.typeName).isEqualTo("MAP")
            assertThat(byDay.children.map { it.typeName })
                .containsExactly("DATEV2", "DATETIMEV2")
        }
    }

    @Test
    fun columnHandleTypesAgreeWithTableSchemaTypes() {
        withMetadata { md ->
            val handle = md.getTableHandle(null, "audit", "temporal_all")
                .orFail("expected audit.temporal_all handle")
            val schemaByName = md.getTableSchema(null, handle).columns.associate { it.name to it.type }
            val handles = md.getColumnHandles(null, handle)

            // getTableSchema drives DESC and getColumnHandles drives planning; they run
            // the mapping independently, so drift would show the user one type and
            // give the planner another.
            assertThat(handles.keys).containsExactlyElementsOf(schemaByName.keys)
            for ((name, columnHandle) in handles) {
                val handleType = (columnHandle as DuckLakeColumnHandle).columnType
                assertThat(handleType.typeName)
                    .`as`("column handle type for %s", name)
                    .isEqualTo(schemaByName.getValue(name).typeName)
                assertThat(handleType.precision)
                    .`as`("column handle precision for %s", name)
                    .isEqualTo(schemaByName.getValue(name).precision)
            }
        }
    }
}
