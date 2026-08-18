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
                stmt.execute(
                    """
                    CREATE TABLE seen_toons (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        updatedAt TEXT,
                        firstSeenAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        notifiedKey TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE favorites (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        thumbUrl TEXT NOT NULL,
                        href TEXT NOT NULL,
                        genre TEXT NOT NULL,
                        updatedAt TEXT,
                        savedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE reader_settings (
                        toonId TEXT PRIMARY KEY NOT NULL,
                        viewMode TEXT NOT NULL,
                        readDirection TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE read_episodes (
                        toonId TEXT NOT NULL,
                        wrId TEXT NOT NULL,
                        readAt INTEGER NOT NULL,
                        lastPage INTEGER NOT NULL,
                        PRIMARY KEY (toonId, wrId)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE read_history (
                        toonId TEXT PRIMARY KEY NOT NULL,
                        toonTitle TEXT NOT NULL,
                        toonThumbUrl TEXT NOT NULL,
                        toonHref TEXT NOT NULL,
                        lastWrId TEXT NOT NULL,
                        lastEpisodeTitle TEXT NOT NULL,
                        lastEpisodeHref TEXT NOT NULL,
                        lastReadOrder INTEGER NOT NULL,
                        totalEpisodes INTEGER NOT NULL,
                        lastReadAt INTEGER NOT NULL,
                        nextWrId TEXT,
                        nextEpisodeTitle TEXT,
                        nextEpisodeHref TEXT,
                        hasNew INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE tombstones (
                        entityType TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        deletedAt INTEGER NOT NULL,
                        PRIMARY KEY (entityType, entityId)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE downloaded_episodes (
                        toonId TEXT NOT NULL,
                        wrId TEXT NOT NULL,
                        toonTitle TEXT NOT NULL,
                        toonThumbUrl TEXT NOT NULL,
                        toonHref TEXT NOT NULL,
                        episodeTitle TEXT NOT NULL,
                        episodeHref TEXT NOT NULL,
                        imageCount INTEGER NOT NULL,
                        totalBytes INTEGER NOT NULL,
                        downloadedAt INTEGER NOT NULL,
                        localDirPath TEXT NOT NULL,
                        PRIMARY KEY (toonId, wrId)
                    )
                    """.trimIndent(),
                )

                stmt.execute("INSERT INTO favorites VALUES ('123','원피스','t','h','액션','08.16',1)")
                stmt.execute("INSERT INTO seen_toons VALUES ('123','원피스','08.16',1,2,'k')")
                stmt.execute("INSERT INTO reader_settings VALUES ('123','SCROLL','RIGHT_TO_LEFT',1)")
                stmt.execute("INSERT INTO read_history VALUES ('123','원피스','t','h','999','ep','eh',1,10,1,NULL,NULL,NULL,0)")
                stmt.execute("INSERT INTO read_episodes VALUES ('123','999',1,5)")
                stmt.execute("INSERT INTO downloaded_episodes VALUES ('123','999','원피스','t','h','ep','eh',3,10,1,'/old')")
                stmt.execute("INSERT INTO tombstones VALUES ('FAVORITE','456',1)")
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
