package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeColumn

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the mapping of `ducklake_column.initial_default` onto a Doris column default, which the FE
 * backfills onto rows from data files written before the column existed. Only scalar types with a
 * Doris-castable literal surface a default: the FE casts defaults eagerly at scan-plan time, so an
 * uncastable value would break the whole table scan rather than one column. A skipped default
 * reads as NULL on old rows.
 */
internal class DuckLakeBackfillDefaultTest {

    private fun column(type: String, initialDefault: String?): DucklakeColumn =
        DucklakeColumn(
            columnId = 1L,
            beginSnapshot = 1L,
            endSnapshot = null,
            tableId = 1L,
            columnOrder = 0L,
            columnName = "b",
            columnType = type,
            nullsAllowed = true,
            parentColumn = null,
            initialDefault = initialDefault,
        )

    @Test
    fun surfacesRawDefaultForScalarTypes() {
        // The raw value passes through untouched; Doris quotes and casts it per column type.
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("int32", "42"))).isEqualTo("42")
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("int64", "-7"))).isEqualTo("-7")
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("boolean", "true"))).isEqualTo("true")
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("float64", "3.14"))).isEqualTo("3.14")
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("decimal(10,2)", "1.50"))).isEqualTo("1.50")
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("varchar", "hi 'there'"))).isEqualTo("hi 'there'")
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("date", "2024-01-15"))).isEqualTo("2024-01-15")
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("timestamp", "2024-01-15 12:00:00"))).isEqualTo("2024-01-15 12:00:00")
    }

    @Test
    fun nullInitialDefaultYieldsNoDefault() {
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("int32", null))).isNull()
    }

    @Test
    fun skipsComplexTypes() {
        // list/struct/map have no castable scalar literal, so old rows read NULL.
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("list<int32>", "[1, 2]"))).isNull()
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("struct<a:int32>", "{a: 1}"))).isNull()
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("map<varchar,int32>", "{x: 1}"))).isNull()
    }

    @Test
    fun skipsBinaryAndSpatialTypes() {
        // These map to Doris binary types and DuckDB's text rendering is not castable to them.
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("blob", "\\x00"))).isNull()
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("uuid", "00000000-0000-0000-0000-000000000000"))).isNull()
        assertThat(DuckLakeConnectorMetadata.backfillDefaultValue(column("geometry", "POINT(0 0)"))).isNull()
    }

    @Test
    fun isBackfillDefaultSafeIsCaseAndWhitespaceInsensitive() {
        assertThat(DuckLakeConnectorMetadata.isBackfillDefaultSafe("  INT32 ")).isTrue()
        assertThat(DuckLakeConnectorMetadata.isBackfillDefaultSafe("STRUCT<a:INT32>")).isFalse()
        assertThat(DuckLakeConnectorMetadata.isBackfillDefaultSafe("BLOB")).isFalse()
    }
}
