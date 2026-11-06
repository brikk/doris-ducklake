package dev.brikk.ducklake.doris.plugin

/** Catalog property names. Required-key validation happens in [DuckLakeConnectorProvider.validateProperties]. */
internal object DuckLakeConnectorProperties {

    const val METADATA_URL = "metadata.url"
    const val METADATA_USER = "metadata.user"
    const val METADATA_PASSWORD = "metadata.password"
    const val STORAGE_WAREHOUSE = "storage.warehouse"
    const val CATALOG_MAX_CONNECTIONS = "catalog.max-connections"
    const val CATALOG_MIN_IDLE_CONNECTIONS = "catalog.min-idle-connections"
    const val CATALOG_CONNECTION_IDLE_TIMEOUT = "catalog.connection-idle-timeout"
    const val CATALOG_CONNECTION_MAX_LIFETIME = "catalog.connection-max-lifetime"
    const val CATALOG_CONNECTION_TIMEOUT = "catalog.connection-timeout"

    /**
     * Default false maps DuckLake `timestamptz` to zone-naive DATETIMEV2 (values are correct UTC
     * instants). True maps it to zone-aware TIMESTAMPTZ, which needs a BE that can read
     * TIMESTAMP_MICROS(isAdjustedToUtc) into a TimeStampTz slot — master only, not in any 4.x release.
     */
    const val ENABLE_MAPPING_TIMESTAMP_TZ = "enable.mapping.timestamp_tz"

    /** Floor for `expire_snapshots`' retention mode, so a careless threshold can't drop recent snapshots. Default 7d. */
    const val MAINTENANCE_MIN_RETENTION = "maintenance.min-retention"

    /** `metadata.password` is optional (trust auth); the pool and mapping knobs have defaults. */
    val REQUIRED_KEYS: List<String> = listOf(
        METADATA_URL,
        METADATA_USER,
        STORAGE_WAREHOUSE,
    )

    fun requireString(props: Map<String, String>, key: String): String {
        val value = props[key]
        require(!value.isNullOrEmpty()) {
            "DuckLake catalog property '$key' is required"
        }
        return value
    }
}
