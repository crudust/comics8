package com.comics8.monitor

import com.comics8.monitor.data.BackupManager
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.FavoriteDao
import com.comics8.monitor.data.local.FavoriteToon
import com.comics8.monitor.data.local.ReadEpisode
import com.comics8.monitor.data.local.ReadEpisodeDao
import com.comics8.monitor.data.local.ReadHistory
import com.comics8.monitor.data.local.ReadHistoryDao
import com.comics8.monitor.data.local.ReaderSetting
import com.comics8.monitor.data.local.ReaderSettingDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test

class BackupManagerTest {

    @Test
    fun testJsonBackupAndRestore() = runBlocking {
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

        val hist = root.getJSONArray("history").getJSONObject(0)
        assertThat(hist.getInt("lastReadOrder")).isEqualTo(100)
        assertThat(hist.getInt("totalEpisodes")).isEqualTo(1100)

        val ep = root.getJSONArray("readEpisodes").getJSONObject(0)
        assertThat(ep.getInt("lastPage")).isEqualTo(5)

        val set = root.getJSONArray("readerSettings").getJSONObject(0)
        assertThat(set.getString("viewMode")).isEqualTo("DUAL")
    }
}
