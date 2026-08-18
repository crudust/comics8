package com.comics8.desktop.data

import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class DesktopDatabaseMigrationTest {
    @Test
    fun legacyUserVersionZeroRebuildsAndSetsVersionOne() {
        val file = File.createTempFile("comics8-v0", ".db")
        file.deleteOnExit()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE favorites (
                        id TEXT PRIMARY KEY,
                        title TEXT,
                        thumbUrl TEXT,
                        href TEXT,
                        genre TEXT,
                        updatedAt TEXT,
                        savedAt INTEGER
                    )
                    """.trimIndent(),
                )
                stmt.execute("INSERT INTO favorites VALUES ('123','원피스','t','h','액션','08.16',1)")
                stmt.execute(
                    """
                    CREATE TABLE seen_toons (
                        id TEXT PRIMARY KEY,
                        title TEXT,
                        updatedAt TEXT,
                        firstSeenAt INTEGER,
                        lastSeenAt INTEGER,
                        notifiedKey TEXT
                    )
                    """.trimIndent(),
                )
                stmt.execute("INSERT INTO seen_toons VALUES ('123','원피스','08.16',1,2,'123|999')")
                stmt.execute(
                    """
                    CREATE TABLE tombstones (
                        entityType TEXT,
                        entityId TEXT,
                        deletedAt INTEGER,
                        PRIMARY KEY (entityType, entityId)
                    )
                    """.trimIndent(),
                )
                stmt.execute("INSERT INTO tombstones VALUES ('FAVORITE','456',1)")
                stmt.execute("PRAGMA user_version = 0")
            }
        }

        val db = DesktopDatabase(file)
        assertThat(db.userVersion()).isEqualTo(1)
        val favs = db.getAllFavorites()
        assertThat(favs).hasSize(1)
        assertThat(favs[0].sourceId).isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(favs[0].id).isEqualTo("123")
        val tombs = db.getTombstonesSince(0)
        assertThat(tombs).hasSize(1)
        assertThat(tombs[0].entityId).isEqualTo("eleven:456")
        val seen = db.getSeenByIds(listOf(WorkId.eleven("123")))
        assertThat(seen).hasSize(1)
        assertThat(seen.values.single().notifiedKey).isEqualTo("eleven:123|999")
    }
}
