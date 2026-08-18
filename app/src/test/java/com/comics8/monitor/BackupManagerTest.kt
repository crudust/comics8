package com.comics8.monitor

import com.comics8.core.source.WorkId
import com.comics8.monitor.data.BackupJson
import com.comics8.monitor.data.BackupManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test

class BackupManagerTest {

    @Test
    fun v1BackupRestoreStillParsesWithDefaultEleven() {
        val json = """
            {
              "version": 1,
              "appName": "Comics8",
              "exportedAt": 1700000000000,
              "favorites": [
                {
                  "id": "12345",
                  "title": "원피스",
                  "thumbUrl": "https://example.com/thumb.jpg",
                  "href": "https://example.com/toon/12345",
                  "genre": "액션",
                  "updatedAt": "08.16",
                  "savedAt": 1700000000000
                }
              ],
              "history": [
                {
                  "toonId": "12345",
                  "toonTitle": "원피스",
                  "toonThumbUrl": "https://example.com/thumb.jpg",
                  "toonHref": "https://example.com/toon/12345",
                  "lastWrId": "999",
                  "lastEpisodeTitle": "원피스 100화",
                  "lastEpisodeHref": "https://example.com/ep/999",
                  "lastReadOrder": 100,
                  "totalEpisodes": 1100,
                  "lastReadAt": 1700000000000,
                  "hasNew": false
                }
              ],
              "readEpisodes": [
                {
                  "toonId": "12345",
                  "wrId": "999",
                  "readAt": 1700000000000,
                  "lastPage": 5
                }
              ],
              "readerSettings": [
                {
                  "toonId": "12345",
                  "viewMode": "DUAL",
                  "readDirection": "RIGHT_TO_LEFT",
                  "updatedAt": 1700000000000
                }
              ]
            }
        """.trimIndent()

        val root = JSONObject(json)
        assertThat(root.getInt("version")).isEqualTo(1)
        assertThat(root.getJSONArray("favorites").length()).isEqualTo(1)
        assertThat(root.getJSONArray("history").length()).isEqualTo(1)
        assertThat(root.getJSONArray("readEpisodes").length()).isEqualTo(1)
        assertThat(root.getJSONArray("readerSettings").length()).isEqualTo(1)

        val fav = root.getJSONArray("favorites").getJSONObject(0)
        assertThat(fav.getString("title")).isEqualTo("원피스")
        val favId = BackupJson.workId(fav, preferId = true)
        assertThat(favId).isEqualTo(WorkId.eleven("12345"))

        val hist = root.getJSONArray("history").getJSONObject(0)
        assertThat(hist.getInt("lastReadOrder")).isEqualTo(100)
        assertThat(hist.getInt("totalEpisodes")).isEqualTo(1100)
        assertThat(BackupJson.workId(hist, preferId = false)).isEqualTo(WorkId.eleven("12345"))

        val ep = root.getJSONArray("readEpisodes").getJSONObject(0)
        assertThat(ep.getInt("lastPage")).isEqualTo(5)
        assertThat(BackupJson.workId(ep, preferId = false)).isEqualTo(WorkId.eleven("12345"))

        val set = root.getJSONArray("readerSettings").getJSONObject(0)
        assertThat(set.getString("viewMode")).isEqualTo("DUAL")
        assertThat(BackupJson.workId(set, preferId = false)).isEqualTo(WorkId.eleven("12345"))
    }

    @Test
    fun v2InboundAcceptsIdOrToonIdAndRejectsHitomi() {
        val favByToonId = JSONObject()
            .put("toonId", "7883")
            .put("sourceId", "eleven")
        assertThat(BackupJson.workId(favByToonId, preferId = true)).isEqualTo(WorkId.eleven("7883"))

        val histById = JSONObject().put("id", "7883")
        assertThat(BackupJson.workId(histById, preferId = false)).isEqualTo(WorkId.eleven("7883"))

        val hitomi = JSONObject()
            .put("id", "99")
            .put("sourceId", "hitomi")
        assertThat(BackupJson.workId(hitomi, preferId = true)).isNull()
    }

    @Test
    fun backupVersionConstantIsV2() = runBlocking {
        assertThat(BackupManager.BACKUP_VERSION).isEqualTo(2)
    }
}
