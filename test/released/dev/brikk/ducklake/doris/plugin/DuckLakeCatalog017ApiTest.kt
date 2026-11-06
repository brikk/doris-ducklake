package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Additive interface compatibility only: the optional latest-only bounds API is not used by Doris planning. */
internal class DuckLakeCatalog017ApiTest {
    @Test
    fun qualifiedReadCatalogInheritsTheOptionalBoundsMethod() {
        val method = DuckLakeQualifiedReadCatalog::class.java.getMethod("getCurrentColumnBounds", Long::class.javaPrimitiveType)
        assertThat(method.declaringClass).isEqualTo(DucklakeCatalog::class.java)
        assertThat(method.returnType).isEqualTo(List::class.java)
    }
}
