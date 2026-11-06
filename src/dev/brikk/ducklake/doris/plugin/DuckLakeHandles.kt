package dev.brikk.ducklake.doris.plugin

/** Narrows an opaque SPI handle to the connector's own type, naming the actual class when it isn't. */
internal inline fun <reified T : Any> Any?.asDuckLakeHandle(): T =
    this as? T
        ?: throw IllegalArgumentException(
            "Expected ${T::class.java.simpleName}, got " +
                (this?.javaClass?.name ?: "null"),
        )
