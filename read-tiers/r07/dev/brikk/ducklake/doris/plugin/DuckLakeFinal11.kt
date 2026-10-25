package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.ConnectorContext
import org.apache.doris.connector.spi.DorisConnectorException
import java.lang.reflect.Proxy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Compiled only against the exact, checksum-attested candidate; never linked into the 0.12.0 build. */
internal class DuckLakeFinal11(private val properties: Map<String, String>, private val context: ConnectorContext) : DuckLakeFinal11Support {
    override val enabled = properties[DuckLakeConnectorProperties.ENABLE_FINAL11_READS].toBoolean()
    private val objectsDelegate = lazy { DuckLakeReadObjects(properties, context.httpSecurityHook) }

    override fun configure(config: DucklakeCatalogConfig) {
        if (!enabled) return
        require(config.catalogDatabaseUrl!!.startsWith("jdbc:postgresql:")) { "Qualified final-1.1 scope requires PostgreSQL JDBC" }
        require(properties.getValue("storage.warehouse").startsWith("s3://")) { "Qualified final-1.1 scope requires S3 storage" }
        listOf("s3.endpoint", "s3.access_key", "s3.secret_key").forEach { require(!properties[it].isNullOrBlank()) { "Final-1.1 requires $it" } }
        config.catalogDatabaseUrl = context.sanitizeOutboundUrl(boundedUrl(config.catalogDatabaseUrl!!))
        config.enableFinal11Reads = true
        DuckLakeReadBudget(properties) // Validate every configured limit before touching data.
    }

    override fun view(catalog: JdbcDucklakeCatalog, session: ConnectorSession?): DucklakeCatalog {
        // Deferred until a metadata method runs: getMetadata itself is built inside
        // the engine's computeIfAbsent. Nesting another compute there can deadlock or
        // trigger ConcurrentHashMap's recursive-update guard on colliding key bins.
        val loaded = lazy {
            val load = java.util.function.Supplier { runCatching {
                context.executeAuthenticated { DuckLakeFinal11Plan.capture(catalog, properties, objectsDelegate.value) }
            }.fold({ Result.success(it) }, { Result.failure<DuckLakeFinal11Plan>(planningFailure(it)) }) }
            session?.statementScope?.computeIfAbsent(
                "ducklake:final11-plan:${session.catalogId}:${session.queryId}:${session.user}", load,
            ) ?: load.get()
        }
        fun proxy(snapshot: Long?): DuckLakeQualifiedReadCatalog = Proxy.newProxyInstance(
            DuckLakeQualifiedReadCatalog::class.java.classLoader, arrayOf(DuckLakeQualifiedReadCatalog::class.java),
        ) { facade, method, args ->
            // Object methods and closing this non-owning view must never capture or consume a plan budget.
            when (method.name) {
                "equals" -> facade === args?.singleOrNull()
                "hashCode" -> System.identityHashCode(facade)
                "toString" -> "DuckLakeQualifiedReadCatalog@${Integer.toHexString(System.identityHashCode(facade))}(snapshot=${snapshot ?: "current"})"
                "close" -> null // The connector/statement owns the resources, not this facade.
                else -> {
                    val plan = loaded.value.getOrThrow()
                    if (method.name == "atSnapshot") {
                        val selected = args!![0] as Long
                        plan.requireFrame(selected)
                        proxy(selected)
                    } else plan.dispatch(method.name, args ?: emptyArray(), snapshot)
                }
            }
        } as DuckLakeQualifiedReadCatalog
        return proxy(null)
    }

    private fun boundedUrl(url: String): String {
        var result = url
        val parameters = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
                URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
        }
        for ((key, default) in listOf("socketTimeout" to 10, "connectTimeout" to 5)) {
            val values = parameters.filter { it.first == key }.map { it.second }
            require(values.size <= 1 && (values.isEmpty() || values.single().toInt() in 1..30)) { "Final-1.1 requires bounded $key (1..30 seconds)" }
            if (values.isEmpty()) result += (if ('?' in result) "&" else "?") + "$key=$default"
        }
        return result
    }

    override fun close() { if (objectsDelegate.isInitialized()) objectsDelegate.value.close() }

    override fun validate(catalog: JdbcDucklakeCatalog) {
        context.executeAuthenticated { DuckLakeFinal11Plan.capture(catalog, properties, objectsDelegate.value) }
    }

    companion object {
        // The catalog interface declares no checked exceptions. Letting one cross
        // a java.lang.reflect.Proxy wraps it in a message-less UTE at SQL.
        internal fun planningFailure(failure: Throwable): Throwable =
            if (failure is RuntimeException || failure is Error) failure else DorisConnectorException(
                "Final-1.1 planning failed (${failure.javaClass.simpleName}): ${failure.message ?: failure.cause?.message ?: "no detail"}",
                failure,
            )
    }
}
