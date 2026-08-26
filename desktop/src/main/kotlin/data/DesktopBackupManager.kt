package com.comics8.desktop.data

import com.comics8.core.source.WorkId
import com.comics8.core.sync.SyncWire
import org.json.JSONArray
import org.json.JSONObject

data class DesktopBackupStats(
    val favoriteCount: Int = 0,
    val historyCount: Int = 0,
    val episodeCount: Int = 0,
    val settingCount: Int = 0,
)

data class DesktopBackupResult(
    val success: Boolean,
    val message: String,
    val favoriteCount: Int = 0,
    val historyCount: Int = 0,
    val episodeCount: Int = 0,
    val settingCount: Int = 0,
)

object DesktopBackupManager {
    fun createBackupJson(database: DesktopDatabase): String {
        val favorites = database.getAllFavorites()
        val history = database.getAllHistory()
        val episodes = database.getAllReadEpisodes()
        val settings = database.getAllReaderSettings()

        val root = JSONObject()
        root.put("version", 2)
        root.put("appName", "Comics8")
        root.put("exportedAt", System.currentTimeMillis())

        val favArray = JSONArray()
        favorites.forEach { f ->
            val obj = JSONObject().apply {
                put("id", f.id)
                put("sourceId", f.sourceId)
                put("title", f.title)
                put("thumbUrl", f.thumbUrl)
                put("href", f.href)
                put("genre", f.genre)
                put("updatedAt", f.updatedAt ?: JSONObject.NULL)
                put("savedAt", f.savedAt)
            }
            favArray.put(obj)
        }
        root.put("favorites", favArray)

        val histArray = JSONArray()
        history.forEach { h ->
            val obj = JSONObject().apply {
                put("toonId", h.toonId)
                put("sourceId", h.sourceId)
                put("toonTitle", h.toonTitle)
                put("toonThumbUrl", h.toonThumbUrl)
                put("toonHref", h.toonHref)
                put("lastWrId", h.lastWrId)
                put("lastEpisodeTitle", h.lastEpisodeTitle)
                put("lastEpisodeHref", h.lastEpisodeHref)
                put("lastReadOrder", h.lastReadOrder)
                put("totalEpisodes", h.totalEpisodes)
                put("lastReadAt", h.lastReadAt)
                put("nextWrId", h.nextWrId ?: JSONObject.NULL)
                put("nextEpisodeTitle", h.nextEpisodeTitle ?: JSONObject.NULL)
                put("nextEpisodeHref", h.nextEpisodeHref ?: JSONObject.NULL)
                put("hasNew", h.hasNew)
            }
            histArray.put(obj)
        }
        root.put("history", histArray)

        val epArray = JSONArray()
        episodes.forEach { ep ->
            val obj = JSONObject().apply {
                put("toonId", ep.toonId)
                put("sourceId", ep.sourceId)
                put("wrId", ep.wrId)
                put("readAt", ep.readAt)
                put("lastPage", ep.lastPage)
            }
            epArray.put(obj)
        }
        root.put("readEpisodes", epArray)

        val settingArray = JSONArray()
        settings.forEach { s ->
            val obj = JSONObject().apply {
                put("toonId", s.toonId)
                put("sourceId", s.sourceId)
                put("viewMode", s.viewMode)
                put("readDirection", s.readDirection)
                put("splitMode", s.splitMode)
                put("updatedAt", s.updatedAt)
            }
            settingArray.put(obj)
        }
        root.put("readerSettings", settingArray)

        return root.toString(2)
    }

    fun restoreBackupJson(database: DesktopDatabase, jsonString: String): DesktopBackupResult {
        return try {
            val root = JSONObject(jsonString)
            val favArray = root.optJSONArray("favorites") ?: JSONArray()
            val histArray = root.optJSONArray("history") ?: JSONArray()
            val epArray = root.optJSONArray("readEpisodes") ?: JSONArray()
            val settingArray = root.optJSONArray("readerSettings") ?: JSONArray()

            val favorites = mutableListOf<FavoriteRecord>()
            for (i in 0 until favArray.length()) {
                val obj = favArray.getJSONObject(i)
                val workId = DesktopBackupJson.workId(obj, preferId = true) ?: continue
                favorites.add(
                    FavoriteRecord(
                        sourceId = workId.sourceId,
                        id = workId.toonId,
                        title = obj.optString("title", ""),
                        thumbUrl = obj.optString("thumbUrl", ""),
                        href = obj.optString("href", ""),
                        genre = obj.optString("genre", ""),
                        updatedAt = if (obj.isNull("updatedAt")) null else obj.getString("updatedAt"),
                        savedAt = obj.optLong("savedAt", System.currentTimeMillis()),
                    ),
                )
            }

            val history = mutableListOf<ReadHistoryRecord>()
            for (i in 0 until histArray.length()) {
                val obj = histArray.getJSONObject(i)
                val workId = DesktopBackupJson.workId(obj, preferId = false) ?: continue
                history.add(
                    ReadHistoryRecord(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        toonTitle = obj.optString("toonTitle", ""),
                        toonThumbUrl = obj.optString("toonThumbUrl", ""),
                        toonHref = obj.optString("toonHref", ""),
                        lastWrId = obj.optString("lastWrId", ""),
                        lastEpisodeTitle = obj.optString("lastEpisodeTitle", ""),
                        lastEpisodeHref = obj.optString("lastEpisodeHref", ""),
                        lastReadOrder = obj.optInt("lastReadOrder", 1),
                        totalEpisodes = obj.optInt("totalEpisodes", 1),
                        lastReadAt = obj.optLong("lastReadAt", System.currentTimeMillis()),
                        nextWrId = if (obj.isNull("nextWrId")) null else obj.getString("nextWrId"),
                        nextEpisodeTitle = if (obj.isNull("nextEpisodeTitle")) null else obj.getString("nextEpisodeTitle"),
                        nextEpisodeHref = if (obj.isNull("nextEpisodeHref")) null else obj.getString("nextEpisodeHref"),
                        hasNew = obj.optBoolean("hasNew", false),
                    ),
                )
            }

            val episodes = mutableListOf<ReadEpisodeRecord>()
            for (i in 0 until epArray.length()) {
                val obj = epArray.getJSONObject(i)
                val workId = DesktopBackupJson.workId(obj, preferId = false) ?: continue
                episodes.add(
                    ReadEpisodeRecord(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        wrId = obj.optString("wrId", ""),
                        readAt = obj.optLong("readAt", System.currentTimeMillis()),
                        lastPage = obj.optInt("lastPage", 0),
                    ),
                )
            }

            val settings = mutableListOf<ReaderSettingRecord>()
            for (i in 0 until settingArray.length()) {
                val obj = settingArray.getJSONObject(i)
                val workId = DesktopBackupJson.workId(obj, preferId = false) ?: continue
                settings.add(
                    ReaderSettingRecord(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        viewMode = obj.optString("viewMode", "SINGLE"),
                        readDirection = obj.optString("readDirection", "RIGHT_TO_LEFT"),
                        splitMode = obj.optString("splitMode", "FIT"),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    ),
                )
            }

            if (favorites.isNotEmpty()) database.saveAllFavorites(favorites)
            if (history.isNotEmpty()) database.saveAllHistory(history)
            if (episodes.isNotEmpty()) database.markAllEpisodesRead(episodes)
            if (settings.isNotEmpty()) database.saveAllReaderSettings(settings)

            DesktopBackupResult(
                success = true,
                message = "복원 완료",
                favoriteCount = favorites.size,
                historyCount = history.size,
                episodeCount = episodes.size,
                settingCount = settings.size,
            )
        } catch (e: Exception) {
            DesktopBackupResult(
                success = false,
                message = "백업 파일 형식이 올바르지 않습니다: ${e.localizedMessage ?: "알 수 없는 오류"}",
            )
        }
    }

    fun getStats(database: DesktopDatabase): DesktopBackupStats {
        return DesktopBackupStats(
            favoriteCount = database.getAllFavorites().size,
            historyCount = database.getAllHistory().size,
            episodeCount = database.getAllReadEpisodes().size,
            settingCount = database.getAllReaderSettings().size,
        )
    }
}

object DesktopBackupJson {
    fun workId(obj: JSONObject, preferId: Boolean): WorkId? = SyncWire.workId(obj, preferId)
}
