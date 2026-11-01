package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog

/** Released lane retains its existing scalar dictionary; no nested-reader adoption here. */
@Suppress("UNUSED_PARAMETER")
internal fun encodeReadSchemaDictionary(
    catalog: DucklakeCatalog,
    handle: DuckLakeTableHandle,
    columns: List<DuckLakeColumnHandle>,
    nameMaps: Collection<Map<Long, String>>,
    hasMappings: Boolean,
): String? = DuckLakeSchemaDictionary.encode(columns, nameMaps)
