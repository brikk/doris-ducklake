package dev.brikk.ducklake.doris.plugin.cache

import org.apache.doris.connector.spi.ConnectorContext

/** Minimal [ConnectorContext] test double; the interface defaults cover everything else the plugin touches. */
class FakeConnectorContext(
    private val catalogName: String,
    private val catalogId: Long,
) : ConnectorContext {

    override fun getCatalogName(): String = catalogName

    override fun getCatalogId(): Long = catalogId
}
