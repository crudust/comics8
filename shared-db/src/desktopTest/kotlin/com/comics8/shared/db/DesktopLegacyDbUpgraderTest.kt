package com.comics8.shared.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DesktopLegacyDbUpgraderTest {
    @Test
    fun upgradesLegacyDesktopV1DatabaseWithNullsAndWalToRoomV15() = runBlocking {
        val dir = createTempDirectory("legacy-v1-upgrade").toFile()
        val dbFile = File(dir, "comics8.db")
        try {
            // Seed a legacy Desktop JDBC-style DB with user_version=1, nullable columns, missing
            // downloaded_episodes indices, and pending WAL frames.
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                conn.execSQL("PRAGMA journal_mode=WAL")
                conn.execSQL("PRAGMA user_version = 1")
                conn.execSQL(
                    """
                    CREATE TABLE seen_toons (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        title TEXT,
                        updatedAt TEXT,
                        firstSeenAt INTEGER,
                        lastSeenAt INTEGER,
                        notifiedKey TEXT,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("CREATE INDEX index_seen_toons_toonId ON seen_toons(toonId)")
                conn.execSQL(
                    """
                    CREATE TABLE favorites (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        title TEXT,
                        thumbUrl TEXT,
                        href TEXT,
                        genre TEXT,
                        updatedAt TEXT,
                        savedAt INTEGER,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("CREATE INDEX index_favorites_toonId ON favorites(toonId)")
                conn.execSQL("CREATE INDEX index_favorites_savedAt ON favorites(savedAt)")
                conn.execSQL(
                    """
                    CREATE TABLE reader_settings (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        viewMode TEXT,
                        readDirection TEXT,
                        splitMode TEXT DEFAULT 'FIT',
                        updatedAt INTEGER,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("CREATE INDEX index_reader_settings_updatedAt ON reader_settings(updatedAt)")
                conn.execSQL(
                    """
                    CREATE TABLE read_episodes (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        wrId TEXT NOT NULL,
                        readAt INTEGER,
                        lastPage INTEGER DEFAULT 0,
                        PRIMARY KEY (sourceId, toonId, wrId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("CREATE INDEX index_read_episodes_toonId ON read_episodes(toonId)")
                conn.execSQL("CREATE INDEX index_read_episodes_readAt ON read_episodes(readAt)")
                conn.execSQL(
                    """
                    CREATE TABLE read_history (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        toonTitle TEXT,
                        toonThumbUrl TEXT,
                        toonHref TEXT,
                        lastWrId TEXT,
                        lastEpisodeTitle TEXT,
                        lastEpisodeHref TEXT,
                        lastReadOrder INTEGER,
                        totalEpisodes INTEGER,
                        lastReadAt INTEGER,
                        nextWrId TEXT,
                        nextEpisodeTitle TEXT,
                        nextEpisodeHref TEXT,
                        hasNew INTEGER DEFAULT 0,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("CREATE INDEX index_read_history_toonId ON read_history(toonId)")
                conn.execSQL("CREATE INDEX index_read_history_lastReadAt ON read_history(lastReadAt)")
                conn.execSQL(
                    """
                    CREATE TABLE tombstones (
                        entityType TEXT,
                        entityId TEXT,
                        deletedAt INTEGER,
                        PRIMARY KEY (entityType, entityId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("CREATE INDEX index_tombstones_deletedAt ON tombstones(deletedAt)")
                conn.execSQL(
                    """
                    CREATE TABLE downloaded_episodes (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        wrId TEXT NOT NULL,
                        toonTitle TEXT,
                        toonThumbUrl TEXT,
                        toonHref TEXT,
                        episodeTitle TEXT,
                        episodeHref TEXT,
                        imageCount INTEGER,
                        totalBytes INTEGER,
                        downloadedAt INTEGER,
                        localDirPath TEXT,
                        PRIMARY KEY (sourceId, toonId, wrId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL(
                    """
                    CREATE TABLE toon_catalog (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        totalEpisodes INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        lastPage INTEGER NOT NULL DEFAULT 0,
                        pageSize INTEGER NOT NULL DEFAULT 0,
                        delta INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("CREATE INDEX index_toon_catalog_toonId ON toon_catalog(toonId)")

                // Rows with NULL non-PK columns that would fail strict Room schema without COALESCE.
                conn.execSQL("INSERT INTO seen_toons VALUES ('eleven', 's1', NULL, NULL, NULL, NULL, NULL)")
                conn.execSQL("INSERT INTO favorites VALUES ('hitomi', 'g:1', NULL, NULL, NULL, NULL, '10.26', NULL)")
                conn.execSQL("INSERT INTO reader_settings VALUES ('eleven', 's1', NULL, NULL, NULL, NULL)")
                conn.execSQL("INSERT INTO read_episodes VALUES ('eleven', 's1', 'ep1', NULL, NULL)")
                conn.execSQL(
                    """
                    INSERT INTO read_history VALUES (
                        'eleven', 's1', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
                    )
                    """.trimIndent(),
                )
                conn.execSQL("INSERT INTO tombstones VALUES ('FAVORITE', 'eleven:old', NULL)")
                conn.execSQL("INSERT INTO tombstones VALUES (NULL, 'bad', 100)")
                conn.execSQL(
                    """
                    INSERT INTO downloaded_episodes VALUES (
                        'eleven', 's1', 'ep1', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
                    )
                    """.trimIndent(),
                )
                conn.execSQL("INSERT INTO toon_catalog VALUES ('eleven', 's1', 15, 900, 1, 20, -5)")
            }

            val roomDb = openDesktopDatabase(dbFile)
            try {
                val store = RoomComicLocalStore(roomDb)

                // Triggers Room open + onValidateSchema across every table and index.
                val favs = store.getAllFavorites()
                assertThat(favs).hasSize(1)
                assertThat(favs[0].id).isEqualTo("g:1")
                assertThat(favs[0].title).isEmpty()
                assertThat(favs[0].savedAt).isEqualTo(0L)

                val seen = store.getSeenByWorkIds(listOf(WorkId("eleven", "s1")))
                assertThat(seen["eleven:s1"]?.title).isEmpty()
                assertThat(seen["eleven:s1"]?.firstSeenAt).isEqualTo(0L)

                val setting = store.getReaderSetting(WorkId("eleven", "s1"))
                assertThat(setting?.viewMode).isEqualTo("SCROLL")
                assertThat(setting?.readDirection).isEqualTo("LTR")
                assertThat(setting?.splitMode).isEqualTo("FIT")

                val ep = store.getReadEpisode(WorkId("eleven", "s1"), "ep1")
                assertThat(ep?.lastPage).isEqualTo(0)

                val history = store.getHistory(WorkId("eleven", "s1"))
                assertThat(history?.toonTitle).isEmpty()
                assertThat(history?.hasNew).isFalse()

                val tombs = store.getTombstonesSince(-1L)
                assertThat(tombs).hasSize(1)
                assertThat(tombs[0].entityId).isEqualTo("eleven:old")

                val downloaded = store.getDownloadedEpisode(WorkId("eleven", "s1"), "ep1")
                assertThat(downloaded?.localDirPath).isEmpty()

                val catalog = store.getCatalogEntry(WorkId("eleven", "s1"))
                assertThat(catalog?.total()).isEqualTo(EpisodeListingTotal(15, 1, 20, -5))
            } finally {
                roomDb.close()
            }

            val backupFile = File(dir, "comics8.db.pre-room.bak")
            assertThat(backupFile.isFile).isTrue()
            val backupModifiedAt = backupFile.lastModified()

            // Verify room_master_table and PRAGMA user_version = 15.
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                conn.prepare("PRAGMA user_version").use { stmt ->
                    assertThat(stmt.step()).isTrue()
                    assertThat(stmt.getLong(0)).isEqualTo(15L)
                }
                conn.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='room_master_table'").use { stmt ->
                    assertThat(stmt.step()).isTrue()
                }
            }

            // Second open must be idempotent and leave existing pre-room backup untouched.
            val reopened = openDesktopDatabase(dbFile)
            try {
                assertThat(RoomComicLocalStore(reopened).countFavorites()).isEqualTo(1)
            } finally {
                reopened.close()
            }
            assertThat(backupFile.lastModified()).isEqualTo(backupModifiedAt)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun upgradesOlderDesktopDbMissingOptionalColumnsAndResetsLegacyCatalog() = runBlocking {
        val dir = createTempDirectory("legacy-old-cols").toFile()
        val dbFile = File(dir, "comics8.db")
        try {
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                conn.execSQL("PRAGMA user_version = 1")
                conn.execSQL(
                    """
                    CREATE TABLE reader_settings (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        viewMode TEXT,
                        readDirection TEXT,
                        updatedAt INTEGER,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("INSERT INTO reader_settings VALUES ('eleven', 't1', 'SINGLE', 'RTL', 50)")
                conn.execSQL(
                    """
                    CREATE TABLE read_episodes (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        wrId TEXT NOT NULL,
                        readAt INTEGER,
                        PRIMARY KEY (sourceId, toonId, wrId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("INSERT INTO read_episodes VALUES ('eleven', 't1', 'w1', 50)")
                // Legacy pre-shape toon_catalog without lastPage/pageSize/delta.
                conn.execSQL(
                    """
                    CREATE TABLE toon_catalog (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        totalEpisodes INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                conn.execSQL("INSERT INTO toon_catalog VALUES ('eleven', 't1', 99, 50)")
            }

            val roomDb = openDesktopDatabase(dbFile)
            try {
                val store = RoomComicLocalStore(roomDb)
                val setting = store.getReaderSetting(WorkId("eleven", "t1"))
                assertThat(setting?.viewMode).isEqualTo("SINGLE")
                assertThat(setting?.splitMode).isEqualTo("FIT")

                val ep = store.getReadEpisode(WorkId("eleven", "t1"), "w1")
                assertThat(ep?.lastPage).isEqualTo(0)

                // Old shapeless catalog guess is discarded.
                assertThat(store.getCatalogEntry(WorkId("eleven", "t1"))).isNull()
            } finally {
                roomDb.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
