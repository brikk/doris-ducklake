package dev.brikk.ducklake.doris.plugin

import org.apache.doris.connector.spi.Connector
import org.apache.doris.connector.spi.ConnectorContext
import org.apache.doris.connector.spi.ConnectorProvider

/** `ServiceLoader` entry, registered in `META-INF/services`. */
class DuckLakeConnectorProvider : ConnectorProvider {

    override fun getType(): String = TYPE

    override fun validateProperties(properties: Map<String, String>) {
        // No unknown-key check: Doris injects engine-level properties into every CREATE CATALOG.
        for (key in DuckLakeConnectorProperties.REQUIRED_KEYS) {
            if (properties[key].isNullOrEmpty()) {
                throw IllegalArgumentException(
                    "DuckLake catalog property '$key' is required",
                )
            }
        }
    }

    override fun create(properties: Map<String, String>, context: ConnectorContext): Connector =
        DuckLakeConnector(properties, context)

    companion object {
        const val TYPE: String = "ducklake"
    }
}
