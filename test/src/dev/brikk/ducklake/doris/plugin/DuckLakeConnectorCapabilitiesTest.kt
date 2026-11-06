package dev.brikk.ducklake.doris.plugin

import java.util.EnumSet

import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext

import org.apache.doris.connector.spi.Connector
import org.apache.doris.connector.spi.ConnectorCapability
import org.junit.jupiter.api.Test

import org.assertj.core.api.Assertions.assertThat

/**
 * Pins the capability declaration. Adding one of the opt-ins (`SUPPORTS_SHOW_CREATE_DDL`,
 * `SUPPORTS_TOPN_LAZY_MATERIALIZE`, `SUPPORTS_NESTED_COLUMN_PRUNE`, `SUPPORTS_VIEW`,
 * `SUPPORTS_COLUMN_AUTO_ANALYZE`, `SUPPORTS_METADATA_PRELOAD`, `SUPPORTS_STORAGE_PREDICATE_PRUNING`,
 * `SUPPORTS_FIELD_ID_ACCESS_PATH`, `SUPPORTS_SYS_TABLE_NESTED_COLUMN_PRUNE`) is a deliberate step
 * with prerequisites in `dev-docs/TODO-read.md`; bump this assertion in the same change.
 */
internal class DuckLakeConnectorCapabilitiesTest {

    @Test
    fun declaresOnlyMvccSnapshot() {
        val connector: Connector = DuckLakeConnector(
            emptyMap(),
            FakeConnectorContext("dl", 1L),
        )
        assertThat(connector.capabilities)
            .containsExactlyInAnyOrderElementsOf(
                EnumSet.of(
                    ConnectorCapability.SUPPORTS_MVCC_SNAPSHOT,
                ),
            )
    }

    @Test
    fun newOptInCapabilitiesStayOffUntilTheirPrerequisitesLand() {
        // Each of these has behavioral prerequisites (field-id stamping for
        // nested prune, credential-free properties for SHOW CREATE, ...);
        // declaring one without the matching implementation misplans queries.
        val connector: Connector = DuckLakeConnector(
            emptyMap(),
            FakeConnectorContext("dl", 1L),
        )
        assertThat(connector.capabilities)
            .doesNotContain(
                ConnectorCapability.SUPPORTS_SHOW_CREATE_DDL,
                ConnectorCapability.SUPPORTS_TOPN_LAZY_MATERIALIZE,
                ConnectorCapability.SUPPORTS_NESTED_COLUMN_PRUNE,
                ConnectorCapability.SUPPORTS_FIELD_ID_ACCESS_PATH,
                ConnectorCapability.SUPPORTS_SYS_TABLE_NESTED_COLUMN_PRUNE,
                ConnectorCapability.SUPPORTS_STORAGE_PREDICATE_PRUNING,
                ConnectorCapability.SUPPORTS_VIEW,
                ConnectorCapability.SUPPORTS_COLUMN_AUTO_ANALYZE,
                ConnectorCapability.SUPPORTS_METADATA_PRELOAD,
                ConnectorCapability.SUPPORTS_WRITE_ONLY_DEFAULTS,
            )
    }
}
