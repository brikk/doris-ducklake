package dev.brikk.ducklake.doris.plugin

import java.time.LocalDate

/**
 * The Iceberg bucket transform DuckLake's writer uses: `(murmur3_32(value) & Int.MAX) % arity`.
 * Bucket pruning only works if this computes the identical bucket, so hash and per-type encoding
 * must match the spec exactly (ints/longs/dates as 8-byte little-endian, strings as UTF-8).
 * Unsupported literal types return null and the caller keeps all files.
 */
internal object DuckLakeBucketTransform {

    fun bucket(value: Any?, arity: Int): Int? {
        require(arity > 0) { "bucket arity must be positive, got $arity" }
        val bytes = encode(value) ?: return null
        return (murmur3(bytes) and Int.MAX_VALUE) % arity
    }

    private fun encode(value: Any?): ByteArray? = when (value) {
        is Int -> longLe(value.toLong())
        is Long -> longLe(value)
        is Short -> longLe(value.toLong())
        is Byte -> longLe(value.toLong())
        is LocalDate -> longLe(value.toEpochDay())
        is String -> value.toByteArray(Charsets.UTF_8)
        else -> null
    }

    private fun longLe(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) {
            b[i] = ((v ushr (i * 8)) and 0xff).toByte()
        }
        return b
    }

    // Murmur3 x86 32-bit, seed 0 (Guava's murmur3_32_fixed).
    private fun murmur3(data: ByteArray): Int {
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593
        var h1 = 0
        val len = data.size
        val nblocks = len ushr 2
        for (i in 0 until nblocks) {
            val i4 = i shl 2
            var k1 = (data[i4].toInt() and 0xff) or
                ((data[i4 + 1].toInt() and 0xff) shl 8) or
                ((data[i4 + 2].toInt() and 0xff) shl 16) or
                ((data[i4 + 3].toInt() and 0xff) shl 24)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }
        val tail = nblocks shl 2
        var k1 = 0
        val rem = len and 3
        if (rem >= 3) k1 = k1 xor ((data[tail + 2].toInt() and 0xff) shl 16)
        if (rem >= 2) k1 = k1 xor ((data[tail + 1].toInt() and 0xff) shl 8)
        if (rem >= 1) {
            k1 = k1 xor (data[tail].toInt() and 0xff)
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }
        h1 = h1 xor len
        h1 = h1 xor (h1 ushr 16)
        h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)
        return h1
    }
}
