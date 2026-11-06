package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.apache.doris.connector.spi.ConnectorColumn
import org.apache.doris.connector.spi.ConnectorMetadata
import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.ddl.ConnectorCreateTableRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Guards that schema, table and column names with CJK, emoji, spaces, embedded
 * quotes, mixed case and NFC/NFD-distinct forms survive unchanged through the FE
 * metadata surface. Cell values are read by the BE and are out of scope here.
 */
internal class DuckLakeUnicodeIdentifierAuditTest {

    companion object {
        // Precomposed (U+00E9) vs decomposed (e + U+0301) "café". Written as escapes
        // so no editor or tooling normalization can collapse the two.
        private const val CAFE_NFC = "caf\u00E9"
        private const val CAFE_NFD = "cafe\u0301"

        // Family emoji: 5 code points joined by ZWJ.
        private const val ZWJ_FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"

        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeAuditFixtureSeeder.seed(
                server,
                "unicodeaudit",
                listOf(
                    "CREATE SCHEMA dl.\"数据库\"",
                    "CREATE TABLE dl.\"数据库\".\"订单 🦆\" (" +
                        "\"标识\" INTEGER, " +
                        "\"col with space\" VARCHAR, " +
                        "\"MixedCase\" INTEGER, " +
                        "\"quo\"\"te\" VARCHAR)",
                    "CREATE TABLE dl.\"数据库\".norm_probe (" +
                        "\"$CAFE_NFC\" INTEGER, " +
                        "\"$CAFE_NFD\" VARCHAR, " +
                        "\"$ZWJ_FAMILY\" INTEGER)",
                    "CREATE SCHEMA dl.\"schema with space\"",
                    "CREATE TABLE dl.\"schema with space\".plain (id INTEGER)",
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

    @Test
    fun listsUnicodeSchemasAndTables() {
        withMetadata { md ->
            assertThat(md.listDatabaseNames(null)).contains("数据库", "schema with space")
            assertThat(md.databaseExists(null, "数据库")).isTrue()
            // Identity is by code point: a prefix or folded name must not match.
            assertThat(md.databaseExists(null, "数据")).isFalse()

            assertThat(md.listTableNames(null, "数据库"))
                .containsExactlyInAnyOrder("订单 🦆", "norm_probe")
            assertThat(md.listTableNames(null, "schema with space"))
                .containsExactly("plain")
        }
    }

    @Test
    fun unicodeColumnIdentifiersRoundTripExactly() {
        withMetadata { md ->
            val handle = md.getTableHandle(null, "数据库", "订单 🦆")
                .orFail("expected 数据库.订单 🦆 handle")

            val schema = md.getTableSchema(null, handle)
            assertThat(schema.tableName).isEqualTo("订单 🦆")
            // No case folding at the metadata layer, and the DDL's "" comes back as
            // a single ".
            assertThat(schema.columns)
                .extracting<String>(ConnectorColumn::getName)
                .containsExactly("标识", "col with space", "MixedCase", "quo\"te")

            assertThat(md.getColumnHandles(null, handle).keys)
                .containsExactly("标识", "col with space", "MixedCase", "quo\"te")
        }
    }

    @Test
    fun normalizationSensitiveIdentifiersStayBinaryDistinct() {
        withMetadata { md ->
            val handle = md.getTableHandle(null, "数据库", "norm_probe")
                .orFail("expected 数据库.norm_probe handle")
            val names = md.getTableSchema(null, handle).columns.map { it.name }

            // Precomposed and decomposed "café" are different names; an NFC-normalizing
            // layer anywhere between catalog and metadata would collapse them.
            assertThat(names).containsExactly(CAFE_NFC, CAFE_NFD, ZWJ_FAMILY)
            assertThat(CAFE_NFC).isNotEqualTo(CAFE_NFD)
            assertThat(names[2].codePointCount(0, names[2].length)).isEqualTo(5)

            val handles = md.getColumnHandles(null, handle)
            assertThat(handles.keys).containsExactly(CAFE_NFC, CAFE_NFD, ZWJ_FAMILY)
            assertThat((handles.getValue(CAFE_NFC) as DuckLakeColumnHandle).columnType.typeName)
                .isEqualTo("INT")
            assertThat((handles.getValue(CAFE_NFD) as DuckLakeColumnHandle).columnType.typeName)
                .isEqualTo("STRING")
        }
    }

    @Test
    fun metadataDdlRoundTripsUnicodeIdentifiers() {
        withMetadata { md ->
            // Reverse direction: Doris-side DDL writes the identifiers and the read
            // path lists them back.
            md.createDatabase(null, "événements 夏", emptyMap())
            assertThat(md.listDatabaseNames(null)).contains("événements 夏")

            val request = ConnectorCreateTableRequest.builder()
                .dbName("événements 夏")
                .tableName("mesures ☀")
                .columns(
                    listOf(
                        ConnectorColumn("clé", ConnectorType.of("INT"), "", false, null),
                        ConnectorColumn("värde 数", ConnectorType.of("STRING"), "", true, null),
                    ),
                )
                .build()
            md.createTable(null, request)

            assertThat(md.listTableNames(null, "événements 夏")).containsExactly("mesures ☀")
            val handle = md.getTableHandle(null, "événements 夏", "mesures ☀")
                .orFail("expected événements 夏.mesures ☀ handle")
            assertThat(md.getTableSchema(null, handle).columns)
                .extracting<String>(ConnectorColumn::getName)
                .containsExactly("clé", "värde 数")

            md.dropTable(null, handle)
            md.dropDatabase(null, "événements 夏", false, false)
            assertThat(md.listDatabaseNames(null)).doesNotContain("événements 夏")
        }
    }
}
