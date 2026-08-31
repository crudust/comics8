package com.comics8.desktop.data

import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class DesktopDatabaseMigrationTest {
    @Test
    fun databaseInitializesWithVersionOne() {
        val file = File.createTempFile("comics8-init", ".db")
        file.deleteOnExit()
        val db = DesktopDatabase(file)
        try {
            assertThat(db.userVersion()).isEqualTo(1)
        } finally {
            db.close()
        }
    }

    @Test
    fun foreignSourceFavoritesAndHistorySaveWhenSourceEnabled() {
        val file = File.createTempFile("comics8-hitomi", ".db")
        file.deleteOnExit()
        val db = DesktopDatabase(file).apply {
            isSourceEnabled = { it in setOf("hitomi", "eleven") }
            installedIds = { setOf("hitomi", "eleven") }
        }
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
            db.saveFavorite(hitomiFav)
            assertThat(db.isFavorite(WorkId("hitomi", "gallery:12345"))).isTrue()

            val favs = db.getAllFavorites()
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
            db.saveHistory(hitomiHist)
            val history = db.getHistory(WorkId("hitomi", "gallery:12345"))
            assertThat(history).isNotNull()
            assertThat(history!!.toonTitle).isEqualTo("Hitomi Title")
            assertThat(history.sourceId).isEqualTo("hitomi")
        } finally {
            db.close()
        }
    }

    @Test
    fun restoreBackupJsonWithNullFieldsAndHitomiSucceeds() {
        val file = File.createTempFile("comics8-restore", ".db")
        file.deleteOnExit()
        val db = DesktopDatabase(file).apply {
            isSourceEnabled = { true }
            installedIds = { setOf("hitomi", "eleven") }
        }
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
            val result = DesktopBackupManager.restoreBackupJson(db, json)
            assertThat(result.success).isTrue()
            assertThat(result.favoriteCount).isEqualTo(1)
            assertThat(result.historyCount).isEqualTo(1)
            assertThat(result.episodeCount).isEqualTo(1)

            val favs = db.getAllFavorites()
            assertThat(favs).hasSize(1)
            assertThat(favs[0].sourceId).isEqualTo("hitomi")

            val hist = db.getAllHistory()
            assertThat(hist).hasSize(1)
            assertThat(hist[0].sourceId).isEqualTo("hitomi")
            assertThat(hist[0].lastEpisodeTitle).isEmpty()
            assertThat(hist[0].lastEpisodeHref).isEmpty()

            val exported = org.json.JSONObject(DesktopBackupManager.createBackupJson(db))
            assertThat(exported.getInt("version")).isEqualTo(2)
            assertThat(exported.getJSONArray("favorites").getJSONObject(0).getString("sourceId"))
                .isEqualTo("hitomi")
            assertThat(exported.getJSONArray("history").getJSONObject(0).isNull("nextWrId")).isTrue()
        } finally {
            db.close()
        }
    }
}
