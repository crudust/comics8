package com.comics8.desktop.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.comics8.core.data.SharedBackupManager
import com.comics8.core.source.WorkId
import com.comics8.core.storage.FavoriteRecord
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.shared.db.RoomComicLocalStore
import com.comics8.shared.db.openDesktopDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class DesktopDatabaseMigrationTest {
    @Test
    fun databaseInitializesWithRoomV15Schema() = runBlocking {
        val file = File.createTempFile("comics8-init", ".db")
        try {
            val db = openDesktopDatabase(file)
            try {
                // Force Room open & schema validation.
                assertThat(RoomComicLocalStore(db).countFavorites()).isEqualTo(0)
            } finally {
                db.close()
            }
            BundledSQLiteDriver().open(file.absolutePath).use { conn ->
                conn.prepare("PRAGMA user_version").use { stmt ->
                    assertThat(stmt.step()).isTrue()
                    assertThat(stmt.getLong(0)).isEqualTo(15L)
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun foreignSourceFavoritesAndHistorySaveThroughLocalStore() = runBlocking {
        val file = File.createTempFile("comics8-hitomi", ".db")
        val db = openDesktopDatabase(file)
        val store = RoomComicLocalStore(db)
        try {
            val hitomiFav = FavoriteRecord(
                sourceId = "hitomi",
                id = "gallery:12345",
                title = "Hitomi Title",
                thumbUrl = "https://tn.hitomi.la/thumb.webp",
                href = "https://hitomi.la/reader/12345.html",
                genre = "doujinshi",
                updatedAt = "2026-08-24",
                savedAt = 1000L,
            )
            store.upsertFavorite(hitomiFav)
            assertThat(store.hasFavorite(WorkId("hitomi", "gallery:12345"))).isTrue()

            val favs = store.getAllFavorites()
            assertThat(favs).hasSize(1)
            assertThat(favs[0].sourceId).isEqualTo("hitomi")
            assertThat(favs[0].id).isEqualTo("gallery:12345")

            val hitomiHist = ReadHistoryRecord(
                sourceId = "hitomi",
                toonId = "gallery:12345",
                toonTitle = "Hitomi Title",
                toonThumbUrl = "https://tn.hitomi.la/thumb.webp",
                toonHref = "https://hitomi.la/reader/12345.html",
                lastWrId = "gallery:12345",
                lastEpisodeTitle = "Hitomi Title",
                lastEpisodeHref = "https://hitomi.la/reader/12345.html",
                lastReadOrder = 1,
                totalEpisodes = 1,
                lastReadAt = 2000L,
            )
            store.upsertHistory(hitomiHist)
            val history = store.getHistory(WorkId("hitomi", "gallery:12345"))
            assertThat(history).isNotNull()
            assertThat(history!!.toonTitle).isEqualTo("Hitomi Title")
            assertThat(history.sourceId).isEqualTo("hitomi")
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun restoreBackupJsonWithNullFieldsAndHitomiSucceeds() = runBlocking {
        val file = File.createTempFile("comics8-restore", ".db")
        val db = openDesktopDatabase(file)
        val store = RoomComicLocalStore(db)
        try {
            val json = """
                {
                  "version": 2,
                  "appName": "Comics8",
                  "exportedAt": 1700000000000,
                  "favorites": [
                    {
                      "id": "gallery:12345",
                      "sourceId": "hitomi",
                      "title": "Hitomi Null Title Test",
                      "savedAt": 1700000000000
                    }
                  ],
                  "history": [
                    {
                      "toonId": "gallery:12345",
                      "sourceId": "hitomi",
                      "toonTitle": "Hitomi Null Title Test",
                      "lastWrId": "gallery:12345",
                      "lastEpisodeTitle": null,
                      "lastEpisodeHref": null,
                      "lastReadOrder": 1,
                      "totalEpisodes": 1,
                      "lastReadAt": 1700000000000
                    }
                  ],
                  "readEpisodes": [
                    {
                      "toonId": "gallery:12345",
                      "sourceId": "hitomi",
                      "wrId": "gallery:12345",
                      "readAt": 1700000000000
                    }
                  ]
                }
            """.trimIndent()
            val result = SharedBackupManager.restoreBackupJson(store, json)
            assertThat(result.success).isTrue()
            assertThat(result.favoriteCount).isEqualTo(1)
            assertThat(result.historyCount).isEqualTo(1)
            assertThat(result.episodeCount).isEqualTo(1)

            val favs = store.getAllFavorites()
            assertThat(favs).hasSize(1)
            assertThat(favs[0].sourceId).isEqualTo("hitomi")

            val hist = store.getAllHistory()
            assertThat(hist).hasSize(1)
            assertThat(hist[0].sourceId).isEqualTo("hitomi")
            assertThat(hist[0].lastEpisodeTitle).isEmpty()
            assertThat(hist[0].lastEpisodeHref).isEmpty()

            val exported = org.json.JSONObject(SharedBackupManager.createBackupJson(store))
            assertThat(exported.getInt("version")).isEqualTo(2)
            assertThat(exported.getJSONArray("favorites").getJSONObject(0).getString("sourceId"))
                .isEqualTo("hitomi")
            assertThat(exported.getJSONArray("history").getJSONObject(0).isNull("nextWrId")).isTrue()
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun updateFavoriteThumbnailPopulatesEmptyThumbnail() = runBlocking {
        val file = File.createTempFile("comics8-fav-thumb", ".db")
        val db = openDesktopDatabase(file)
        val store = RoomComicLocalStore(db)
        try {
            val workId = WorkId("hitomi", "artist:samidare_setsuna")
            store.upsertFavorite(
                FavoriteRecord(
                    sourceId = "hitomi",
                    id = "artist:samidare_setsuna",
                    title = "Samidare Setsuna",
                    thumbUrl = "",
                    href = "https://hitomi.la/artist/samidare-all.html",
                    genre = "artist",
                    updatedAt = "10.26",
                    savedAt = 1000L,
                ),
            )
            assertThat(store.getFavorite(workId)?.thumbUrl).isEmpty()

            // Backfill thumbnail
            val thumb = "https://tn.gold-usergeneratedcontent.net/webpsmalltn/1.webp"
            store.updateFavoriteThumbUrlIfBlank(workId, thumb)
            assertThat(store.getFavorite(workId)?.thumbUrl).isEqualTo(thumb)

            // Calling again when already populated should not overwrite
            store.updateFavoriteThumbUrlIfBlank(workId, "https://tn.gold-usergeneratedcontent.net/webpsmalltn/2.webp")
            assertThat(store.getFavorite(workId)?.thumbUrl).isEqualTo(thumb)
        } finally {
            db.close()
            file.delete()
        }
    }
}
