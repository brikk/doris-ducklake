package dev.brikk.ducklake.doris.corpus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * Reads a pre-seeded nested table (struct, list, map) through a live FE and checks
 * that null parents stay distinct from null children. Fixture comes from the
 * `DORIS_READ_CORRECTNESS_FIXTURE` properties file.
 */
internal class DuckLakeDefaultNestedLiveTest {
    @Test
    fun mixedScalarStructListAndMapKeepValuesAndNullParents() {
        val properties = Properties().apply {
            Files.newInputStream(Path.of(requireNotNull(System.getenv("DORIS_READ_CORRECTNESS_FIXTURE")))).use(::load)
        }
        val catalog = properties.getProperty("catalog")
        DriverManager.getConnection(properties.getProperty("fe.url"), "root", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SHOW FRONTENDS").use { rows ->
                    check(rows.next())
                    assertThat(rows.getString("Version")).contains("4bc7f426828")
                }
                statement.executeQuery("SELECT id,s.a,s.label,struct_element(element_at(items,1),'x')," +
                    "struct_element(element_at(attrs,'k'),'a') FROM $catalog.tpch.default_nested ORDER BY id").use { rows ->
                    val actual = buildList { while (rows.next()) add((1..5).map { rows.getString(it) }) }
                    assertThat(actual).containsExactly(listOf("1","7","NULL","list","map"),
                        listOf("2",null,null,null,null), listOf("3",null,null,null,null))
                }
                statement.executeQuery("SELECT s IS NULL,items IS NULL,attrs IS NULL FROM $catalog.tpch.default_nested ORDER BY id").use { rows ->
                    val parents = buildList { while (rows.next()) add((1..3).map { rows.getBoolean(it) }) }
                    assertThat(parents).containsExactly(listOf(false,false,false),listOf(true,true,true),listOf(false,false,false))
                }
            }
        }
        println("DEFAULT NESTED native reads passed; literal NULL remains text; null parents/containers stay distinct; JVM=" +
            System.getProperty("java.version"))
    }
}
