package dev.brikk.ducklake.doris.plugin

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Parses `key=value/` segments out of a data-file path, for the `add_files(..., hive_partitioning => true)`
 * layout where partition columns live in the path and are absent from the parquet body.
 */
internal object HivePartitionPath {

    const val HIVE_DEFAULT_PARTITION: String = "__HIVE_DEFAULT_PARTITION__"

    /** Lowercased key → raw value. The basename is skipped so a stray `=` in a filename is never split. */
    fun parse(path: String): Map<String, String> {
        val normalized = path.replace('\\', '/')
        val lastSlash = normalized.lastIndexOf('/')
        val dirs = if (lastSlash < 0) "" else normalized.substring(0, lastSlash)
        val out = LinkedHashMap<String, String>()
        for (segment in dirs.split('/')) {
            val eq = segment.indexOf('=')
            if (eq > 0 && eq < segment.length - 1) {
                out[segment.substring(0, eq).lowercase(Locale.ROOT)] = segment.substring(eq + 1)
            }
        }
        return out
    }

    /** `%XX` escapes decode as UTF-8; unlike `java.net.URLDecoder`, `+` stays literal; malformed escapes pass through. */
    fun urlDecode(value: String): String {
        if (value.indexOf('%') < 0) {
            return value
        }
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi shl 4) + lo)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
