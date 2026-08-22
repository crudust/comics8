package com.comics8.monitor.data

import com.comics8.core.source.WorkId
import com.comics8.core.sync.SyncWire
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.FavoriteToon
import com.comics8.monitor.data.local.ReadEpisode
import com.comics8.monitor.data.local.ReadHistory
import com.comics8.monitor.data.local.ReaderSetting
import org.json.JSONArray
import org.json.JSONObject

data class BackupStats(
    val favoriteCount: Int = 0,
    val historyCount: Int = 0,
    val episodeCount: Int = 0,
    val settingCount: Int = 0,
)

data class BackupRestoreResult(
    val success: Boolean,
    val message: String,
    val favoriteCount: Int = 0,
    val historyCount: Int = 0,
    val episodeCount: Int = 0,
    val settingCount: Int = 0,
)

object BackupManager {
    const val BACKUP_VERSION = 2

    suspend fun createBackupJson(database: AppDatabase): String {
        val favorites = database.favoriteDao().all()
        val history = database.readHistoryDao().all()
        val episodes = database.readEpisodeDao().all()
        val settings = database.readerSettingDao().all()

        val root = JSONObject()
        root.put("version", BACKUP_VERSION)
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

    suspend fun restoreBackupJson(database: AppDatabase, jsonString: String): BackupRestoreResult {
        return try {
            val root = JSONObject(jsonString)
            val favArray = root.optJSONArray("favorites") ?: JSONArray()
            val histArray = root.optJSONArray("history") ?: JSONArray()
            val epArray = root.optJSONArray("readEpisodes") ?: JSONArray()
            val settingArray = root.optJSONArray("readerSettings") ?: JSONArray()

            val favorites = mutableListOf<FavoriteToon>()
            for (i in 0 until favArray.length()) {
                val obj = favArray.getJSONObject(i)
                val workId = BackupJson.workId(obj, preferId = true) ?: continue
                favorites.add(
                    FavoriteToon(
                        sourceId = workId.sourceId,
                        id = workId.toonId,
                        title = obj.getString("title"),
                        thumbUrl = obj.optString("thumbUrl", ""),
                        href = obj.optString("href", ""),
                        genre = obj.optString("genre", ""),
                        updatedAt = if (obj.isNull("updatedAt")) null else obj.getString("updatedAt"),
                        savedAt = obj.optLong("savedAt", System.currentTimeMillis()),
                    ),
                )
            }

            val history = mutableListOf<ReadHistory>()
            for (i in 0 until histArray.length()) {
                val obj = histArray.getJSONObject(i)
                val workId = BackupJson.workId(obj, preferId = false) ?: continue
                history.add(
                    ReadHistory(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        toonTitle = obj.getString("toonTitle"),
                        toonThumbUrl = obj.optString("toonThumbUrl", ""),
                        toonHref = obj.optString("toonHref", ""),
                        lastWrId = obj.getString("lastWrId"),
                        lastEpisodeTitle = obj.getString("lastEpisodeTitle"),
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

            val episodes = mutableListOf<ReadEpisode>()
            for (i in 0 until epArray.length()) {
                val obj = epArray.getJSONObject(i)
                val workId = BackupJson.workId(obj, preferId = false) ?: continue
                episodes.add(
                    ReadEpisode(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        wrId = obj.getString("wrId"),
                        readAt = obj.optLong("readAt", System.currentTimeMillis()),
                        lastPage = obj.optInt("lastPage", 0),
                    ),
                )
            }

            val settings = mutableListOf<ReaderSetting>()
            for (i in 0 until settingArray.length()) {
                val obj = settingArray.getJSONObject(i)
                val workId = BackupJson.workId(obj, preferId = false) ?: continue
                settings.add(
                    ReaderSetting(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        viewMode = obj.optString("viewMode", "SINGLE"),
                        readDirection = obj.optString("readDirection", "RIGHT_TO_LEFT"),
                        splitMode = obj.optString("splitMode", "FIT"),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    ),
                )
            }

            if (favorites.isNotEmpty()) database.favoriteDao().upsertAll(favorites)
            if (history.isNotEmpty()) database.readHistoryDao().upsertAll(history)
            if (episodes.isNotEmpty()) database.readEpisodeDao().markReadAll(episodes)
            if (settings.isNotEmpty()) database.readerSettingDao().saveAll(settings)

            BackupRestoreResult(
                success = true,
                message = "복원 완료",
                favoriteCount = favorites.size,
                historyCount = history.size,
                episodeCount = episodes.size,
                settingCount = settings.size,
            )
        } catch (e: Exception) {
            BackupRestoreResult(
                success = false,
                message = "백업 파일 형식이 올바르지 않습니다: ${e.localizedMessage ?: "알 수 없는 오류"}",
            )
        }
    }

    suspend fun getStats(database: AppDatabase): BackupStats {
        return BackupStats(
            favoriteCount = database.favoriteDao().all().size,
            historyCount = database.readHistoryDao().all().size,
            episodeCount = database.readEpisodeDao().all().size,
            settingCount = database.readerSettingDao().all().size,
        )
    }
}

object BackupJson {
    fun workId(obj: JSONObject, preferId: Boolean): WorkId? = SyncWire.workId(obj, preferId)
}
