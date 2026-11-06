package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeSchema
import dev.brikk.ducklake.catalog.DucklakeTable

/** Resolves DuckLake's scoped paths (catalog data path → schema → table → file) to absolute storage URIs. */
internal class DuckLakePathResolver(
    private val catalog: DucklakeCatalog,
    private val configuredDataPath: String?,
) {

    fun rootDataPath(): String =
        (catalog.getDataPath() ?: configuredDataPath)
            ?: throw IllegalStateException("No data path configured for relative file paths")

    fun resolveSchemaDataPath(schema: DucklakeSchema): String =
        resolveScopedPath(schema.path, schema.pathIsRelative, rootDataPath())

    fun resolveTableDataPath(schema: DucklakeSchema, table: DucklakeTable): String =
        resolveScopedPath(table.path, table.pathIsRelative, resolveSchemaDataPath(schema))

    fun resolveFilePath(path: String, isRelative: Boolean, tableDataPath: String): String {
        if (!isRelative) {
            return path
        }
        return joinPaths(tableDataPath, path)
    }

    companion object {
        fun resolveScopedPath(
            path: String?,
            isRelative: Boolean?,
            parentPath: String,
        ): String {
            if (path == null || path.isBlank()) {
                return parentPath
            }
            if (isRelative ?: false) {
                return joinPaths(parentPath, path)
            }
            return path
        }

        private fun joinPaths(parent: String, child: String): String {
            if (parent.endsWith("/")) {
                return parent + child
            }
            return "$parent/$child"
        }
    }
}
