package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import org.apache.doris.connector.spi.ConnectorContext
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.ConnectorStatementScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

internal class DuckLakeFinal11LifecycleTest {
    @Test fun objectMethodsAndCloseDoNotCaptureAnUnusedFacade() {
        catalog().use { jdbc ->
            val context = FailingContext()
            val support = DuckLakeFinal11(emptyMap(), context)
            val first = support.view(jdbc, null)
            val second = support.view(jdbc, null)
            identityAndClose(first, second)
            assertThat(context.captures).isZero()
            support.close()
            assertThat(context.captures).isZero()
        }
    }

    @Test fun failedCaptureIsMemoizedWhileObjectMethodsAndCloseStayUsable() {
        catalog().use { jdbc ->
            val context = FailingContext()
            val support = DuckLakeFinal11(emptyMap(), context)
            val first = support.view(jdbc, null)
            assertThatThrownBy { first.getSpecVersion() }.isSameAs(context.failure)
            identityAndClose(first, support.view(jdbc, null))
            assertThatThrownBy { first.getSpecVersion() }.isSameAs(context.failure)
            assertThat(context.captures).isEqualTo(1)
        }
    }

    @Test fun statementScopeSharesCaptureFailureWithoutLifecycleLoading() {
        catalog().use { jdbc ->
            val context = FailingContext()
            val scope = Scope()
            val session = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ConnectorSession::class.java)) { _, method, _ ->
                when (method.name) {
                    "getStatementScope" -> scope
                    "getCatalogId" -> 701L
                    "getQueryId" -> "lifecycle"
                    "getUser" -> "test"
                    else -> error("Unexpected session method ${method.name}")
                }
            } as ConnectorSession
            val support = DuckLakeFinal11(emptyMap(), context)
            val first = support.view(jdbc, session)
            val second = support.view(jdbc, session)
            identityAndClose(first, second)
            assertThat(scope.loads).isZero()
            assertThatThrownBy { first.getSpecVersion() }.isSameAs(context.failure)
            assertThatThrownBy { second.getSpecVersion() }.isSameAs(context.failure)
            identityAndClose(first, second)
            assertThat(scope.loads).isEqualTo(1)
            assertThat(context.captures).isEqualTo(1)
        }
    }

    private fun identityAndClose(first: DucklakeCatalog, second: DucklakeCatalog) {
        assertThat(first.equals(first)).isTrue()
        assertThat(first.equals(second)).isFalse()
        assertThat(second.equals(first)).isFalse()
        assertThat(first.equals(null)).isFalse()
        assertThat(first.equals("unrelated")).isFalse()
        val hash = first.hashCode()
        assertThat(hash).isEqualTo(System.identityHashCode(first))
        assertThat(first.toString()).contains("DuckLakeQualifiedReadCatalog")
        first.close()
        first.close()
        second.close()
        assertThat(first.hashCode()).isEqualTo(hash)
    }

    // Real catalog instance, but every attempted plan capture fails before touching it or S3.
    private fun catalog() = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = "jdbc:duckdb:"
        maxCatalogConnections = 1
    })

    private class FailingContext : ConnectorContext {
        val failure = IllegalStateException("capture must not run for Object/close")
        var captures = 0
        override fun getCatalogName() = "lifecycle"
        override fun getCatalogId() = 701L
        override fun <T : Any?> executeAuthenticated(action: Callable<T>): T {
            captures++
            throw failure
        }
    }

    private class Scope : ConnectorStatementScope {
        private val values = ConcurrentHashMap<String, Any>()
        var loads = 0
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> computeIfAbsent(key: String, loader: Supplier<T>): T = values.computeIfAbsent(key) {
            loads++
            loader.get() as Any
        } as T
    }
}
