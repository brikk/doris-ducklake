package dev.brikk.ducklake.doris.plugin

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/** Strict producer gate, not a connector pass. Signed 417a9117f5 + this MinIO fails before scanning. */
internal class DuckLakeR07LongPartitionNativeTest {
    @Test
    fun nativeProducerMustPreserveLongEscapedPartitionValuesWithoutAnObjectNameFailure() {
        val fixture = DuckLakeR07LiveQualificationTest()
        fixture.ensureBucket()
        val (db, path) = fixture.produce("1.1")
        val value = "é/%?".repeat(24)
        // Kept as a strict failing assertion. Do not shorten this input, change
        // an expected golden, or substitute newer native hashing underneath it.
        assertThatCode { fixture.mutate(db, path,
            "CREATE TABLE lake.long_part(i INTEGER,p VARCHAR); ALTER TABLE lake.long_part SET PARTITIONED BY(p); " +
                "INSERT INTO lake.long_part VALUES(1,'$value')") }.doesNotThrowAnyException()
    }
}
