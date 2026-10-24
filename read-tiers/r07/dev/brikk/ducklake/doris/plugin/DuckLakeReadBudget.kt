package dev.brikk.ducklake.doris.plugin

import java.util.concurrent.CancellationException

/** One budget for the whole catalog plan, not a new allowance for each companion page/table. */
internal class DuckLakeReadBudget(properties: Map<String, String>) {
    val pageSize = 128
    val maxTables = limit(properties, "tables", 128)
    val maxSchemas = limit(properties, "schemas", 64)
    val historyWindow = properties["final11.history-window"]?.toInt() ?: 0
    private var pages = limit(properties, "pages", 128).toLong()
    private var paths = limit(properties, "paths", 4096).toLong()
    private var work = limit(properties, "work", 100_000).toLong()
    private var bytes = limit(properties, "bytes", 67_108_864).toLong()
    private var text = 4_194_304L
    private val started = System.nanoTime()
    private val millis = limit(properties, "millis", 30_000)
    // JDBC's API is whole seconds; the original millisecond budget still rejects late output.
    val queryTimeoutSeconds = (millis + 999) / 1000
    private var captured = false

    init { require(historyWindow in 0..16) { "final11.history-window must be 0..16 (0=current only)" } }

    @Synchronized fun step(amount: Int = 1) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("DuckLake planning cancelled")
        check(captured || (System.nanoTime() - started) / 1_000_000 <= millis) { "Final-1.1 planning deadline exceeded" }
        require(amount >= 0)
        work -= amount
        check(work >= 0) { "Final-1.1 total planning work budget exceeded" }
    }

    fun page() { step(); check(--pages >= 0) { "Final-1.1 total page budget exceeded" } }
    fun path(value: String) {
        step()
        check(--paths >= 0) { "Final-1.1 total retained-path budget exceeded" }
        check(value.length <= 16_384) { "Final-1.1 path length exceeded" }
        text -= value.toByteArray(Charsets.UTF_8).size
        check(text >= 0) { "Final-1.1 total path-byte budget exceeded" }
    }
    fun transfer(amount: Int) { step(); require(amount >= 0); bytes -= amount; check(bytes >= 0) { "Final-1.1 total footer/vector byte budget exceeded" } }
    fun finish() { step(); captured = true }

    private fun limit(properties: Map<String, String>, name: String, maximum: Int): Int {
        val value = properties["final11.max-planning-$name"]?.toInt() ?: maximum
        require(value in 1..maximum) { "Invalid final11.max-planning-$name (maximum $maximum)" }
        return value
    }
}
