package com.comics8.monitor.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.sql.DriverManager

class AppDatabaseMigrationTest {
    @Test
    fun v9FixtureMigratesToV10WithSameRowCounts() {
        val url = "jdbc:sqlite::memory:"
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                V9Schema.statements.forEach { stmt.execute(it) }
            }

            SchemaV10.statements().forEach { sql ->
                conn.createStatement().use { it.execute(sql) }
            }

            conn.createStatement().use { stmt ->
                assertThat(count(stmt, "favorites")).isEqualTo(1)
                assertThat(count(stmt, "seen_toons")).isEqualTo(1)
                assertThat(count(stmt, "reader_settings")).isEqualTo(1)
                assertThat(count(stmt, "read_history")).isEqualTo(1)
                assertThat(count(stmt, "read_episodes")).isEqualTo(1)
                assertThat(count(stmt, "downloaded_episodes")).isEqualTo(1)
                assertThat(count(stmt, "tombstones")).isEqualTo(1)

                stmt.executeQuery("SELECT sourceId, toonId FROM favorites").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("sourceId")).isEqualTo("eleven")
                    assertThat(rs.getString("toonId")).isEqualTo("123")
                }
                stmt.executeQuery("SELECT entityId FROM tombstones").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("entityId")).isEqualTo("eleven:456")
                }
                stmt.executeQuery("SELECT notifiedKey FROM seen_toons").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("notifiedKey")).isEqualTo("eleven:123|999")
                }
            }
        }
    }

    private fun count(stmt: java.sql.Statement, table: String): Int {
        stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
            rs.next()
            return rs.getInt(1)
        }
    }
}
