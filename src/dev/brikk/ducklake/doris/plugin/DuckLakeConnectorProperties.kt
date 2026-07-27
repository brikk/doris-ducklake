package dev.brikk.ducklake.doris.plugin

/**
 * Catalog-level property names and the required-key set for this connector.
 *
 * The SPI's typed `ConnectorPropertyMetadata` descriptor was removed (upstream
 * #66135-era connector consolidation): the engine no longer consumes a per-property
 * metadata list, and required-property validation is done directly by the connector's
 * `ConnectorProvider.validateProperties`. We therefore keep only the key constants and
 * the plain set of required keys the provider checks — identical validation behaviour,
 * no dependency on the deleted descriptor type. (Iceberg's connector likewise validates
 * with plain code in IcebergConnectorProvider.validateProperties.)
 */
internal object DuckLakeConnectorProperties {

    const val METADATA_URL = "metadata.url"
    const val METADATA_USER = "metadata.user"
    const val METADATA_PASSWORD = "metadata.password"
    const val STORAGE_WAREHOUSE = "storage.warehouse"

    // Mirrors the in-tree iceberg connector's ENABLE_MAPPING_TIMESTAMP_TZ
    // (fe-connector-iceberg IcebergConnectorProperties): default false maps a
    // DuckLake `timestamptz` to naive DATETIMEV2 (correct UTC values, zone-naive
    // typing) for broad BE compatibility; true maps to zone-aware TIMESTAMPTZ,
    // which needs a BE new enough to read TIMESTAMP_MICROS(isAdjustedToUtc) into
    // a TimeStampTz slot (Int64ToTimestampTz, master-only; NOT in 4.0.x/4.1.x releases incl. 4.1.2 — verified).
    const val ENABLE_MAPPING_TIMESTAMP_TZ = "enable.mapping.timestamp_tz"

    // Retention floor for the expire_snapshots maintenance procedure's retention mode — the
    // Doris analogue of Trino's `ducklake.maintenance.min-retention`. Guards against a too-small
    // retention_threshold nuking recent time-travel snapshots. Default 7d (see DuckLakeProcedureOps).
    const val MAINTENANCE_MIN_RETENTION = "maintenance.min-retention"

    // The catalog properties that must be present and non-empty on CREATE CATALOG.
    // Same set the former requiredStringProperty(...) descriptors declared:
    // metadata.url, metadata.user, storage.warehouse. metadata.password is optional
    // (trust auth), and the boolean/retention knobs have defaults.
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
