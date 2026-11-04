package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeColumn
import dev.brikk.ducklake.catalog.DucklakeInvalidOperationException
import org.apache.doris.connector.spi.DorisConnectorException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.function.Supplier

/** Positive evidence guard, not a claim that missing/unknown statistics prove representability. */
internal object DuckLakeTemporalDomain {
    private val types = setOf("date", "timestamp", "timestamp_s", "timestamp_ms", "timestamp_ns", "timestamptz")
    private val infinities = setOf("infinity", "+infinity", "-infinity", "inf", "+inf", "-inf")
    private val yearPrefix = Regex("^([+-]?\\d+)-")
    private val minDay = LocalDate.of(1, 1, 1).toEpochDay()
    private val maxDay = LocalDate.of(9999, 12, 31).toEpochDay()
    private const val MIN_MICROS = -62135596800000000L
    private const val MAX_MICROS = 253402300799999999L
    private const val MAX_INLINE_ROWS = 4096L
    private const val MAX_INLINE_VALUES = 16384L
    private const val MAX_INLINE_SCHEMAS = 64

    fun checkKnownBounds(catalog: DucklakeCatalog, handle: DuckLakeTableHandle, columns: List<DucklakeColumn>) {
        if (catalog is DuckLakeQualifiedReadCatalog) return
        val temporal = columns.filter { it.columnType.lowercase(Locale.ROOT) in types }
        if (temporal.isEmpty()) return
        catalog.readSession(Supplier {
            val stats = catalog.getColumnStats(handle.tableId, handle.snapshotId).associateBy { it.columnId }
            for (column in temporal) {
                val bounds = stats[column.columnId]
                for (value in listOf(bounds?.minValue, bounds?.maxValue, column.initialDefault)) {
                    requireRepresentable(column.columnType, value, column.columnName)
                }
            }
            checkInlineValues(catalog, handle, temporal)
        })
    }

    private fun checkInlineValues(catalog: DucklakeCatalog, handle: DuckLakeTableHandle, columns: List<DucklakeColumn>) {
        val infos = catalog.getInlinedDataInfos(handle.tableId, handle.snapshotId).filter { it.hasLiveRows }
        if (infos.isEmpty()) return
        if (infos.size > MAX_INLINE_SCHEMAS || columns.any { it.parentColumn != null }) {
            refuse("cannot be validated for this nested/large inline shape")
        }
        var remaining = MAX_INLINE_ROWS
        var remainingValues = MAX_INLINE_VALUES
        for (info in infos) {
            val count = catalog.countInlinedRows(handle.tableId, info.schemaVersion, handle.snapshotId)
            if (count < 0 || count > remaining || count > remainingValues / columns.size) {
                refuse("validation exceeds its bounded inline row/value budget")
            }
            remaining -= count
            remainingValues -= count * columns.size
            val rows = try {
                catalog.readInlinedDataDecoded(handle.tableId, info.schemaVersion, handle.snapshotId, columns)
            } catch (failure: DucklakeInvalidOperationException) {
                refuse("cannot be established from native inline values", failure)
            }
            if (rows.size.toLong() != count || rows.any { it.size != columns.size }) {
                refuse("validation found inconsistent inline metadata")
            }
            for (row in rows) {
                columns.forEachIndexed { index, column -> requireRepresentable(column.columnType, row[index], column.columnName) }
            }
        }
    }

    private fun refuse(reason: String, cause: Throwable? = null): Nothing =
        throw DorisConnectorException("Doris temporal domain $reason; read refused", cause)

    fun requireRepresentable(type: String, value: Any?, column: String) {
        val normalized = type.lowercase(Locale.ROOT)
        if (value == null || normalized !in types) return
        if (outsideDomain(normalized, value)) {
            throw DorisConnectorException(
                "DuckLake temporal domain exceeds Doris DATE/DATETIME for column '$column'; " +
                    "non-finite or out-of-range temporal values cannot be represented. This read is refused.",
            )
        }
    }

    private fun outsideDomain(type: String, value: Any): Boolean = when (value) {
        is LocalDate -> value.year !in 1..9999
        is LocalDateTime -> value.year !in 1..9999
        is OffsetDateTime -> value.withOffsetSameInstant(ZoneOffset.UTC).year !in 1..9999
        is Instant -> runCatching { value.atOffset(ZoneOffset.UTC).year !in 1..9999 }.getOrDefault(true)
        is java.sql.Timestamp -> outsideDomain(type, value.toInstant())
        is java.sql.Date -> outsideDomain(type, Instant.ofEpochMilli(value.time))
        is Number -> {
            val exact = value.toString().toLongOrNull()
            exact == null || if (type == "date") exact !in minDay..maxDay else exact !in MIN_MICROS..MAX_MICROS
        }
        is ByteArray -> outsideTextDomain(String(value, Charsets.UTF_8))
        else -> outsideTextDomain(value.toString())
    }

    private fun outsideTextDomain(value: String): Boolean {
        val text = value.trim().removeSurrounding("'").lowercase(Locale.ROOT)
        if (text in infinities || text.endsWith(" bc") || text.endsWith("(bc)")) return true
        val year = yearPrefix.find(text)?.groupValues?.get(1) ?: return false
        return year.toLongOrNull()?.let { it !in 1L..9999L } ?: true
    }
}
