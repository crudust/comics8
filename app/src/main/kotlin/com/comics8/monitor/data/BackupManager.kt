package com.comics8.monitor.data

import com.comics8.core.backup.BackupEpisodeWire
import com.comics8.core.backup.BackupFavoriteWire
import com.comics8.core.backup.BackupHistoryWire
import com.comics8.core.backup.BackupReaderSettingWire
import com.comics8.core.backup.BackupWire
import com.comics8.core.backup.BackupWireCodec
import com.comics8.core.model.BackupResult
import com.comics8.core.model.BackupStats as CoreBackupStats
import com.comics8.core.source.WorkId
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.FavoriteToon
import com.comics8.monitor.data.local.ReadEpisode
import com.comics8.monitor.data.local.ReadHistory
import com.comics8.monitor.data.local.ReaderSetting
import androidx.room.withTransaction
import org.json.JSONObject

typealias BackupStats = CoreBackupStats
typealias BackupRestoreResult = BackupResult

object BackupManager {
    const val BACKUP_VERSION = BackupWireCodec.VERSION

    suspend fun createBackupJson(database: AppDatabase): String {
        val favorites = database.favoriteDao().all()
        val history = database.readHistoryDao().all()
        val episodes = database.readEpisodeDao().all()
        val settings = database.readerSettingDao().all()

        return BackupWireCodec.encode(
            BackupWire(
                exportedAt = System.currentTimeMillis(),
                favorites = favorites.map { f ->
                    BackupFavoriteWire(f.sourceId, f.id, title = f.title, thumbUrl = f.thumbUrl, href = f.href, genre = f.genre, updatedAt = f.updatedAt, savedAt = f.savedAt)
                },
                history = history.map { h ->
                    BackupHistoryWire(h.sourceId, h.toonId, toonTitle = h.toonTitle, toonThumbUrl = h.toonThumbUrl, toonHref = h.toonHref, lastWrId = h.lastWrId, lastEpisodeTitle = h.lastEpisodeTitle, lastEpisodeHref = h.lastEpisodeHref, lastReadOrder = h.lastReadOrder, totalEpisodes = h.totalEpisodes, lastReadAt = h.lastReadAt, nextWrId = h.nextWrId, nextEpisodeTitle = h.nextEpisodeTitle, nextEpisodeHref = h.nextEpisodeHref, hasNew = h.hasNew)
                },
                readEpisodes = episodes.map { ep ->
                    BackupEpisodeWire(ep.sourceId, ep.toonId, wrId = ep.wrId, readAt = ep.readAt, lastPage = ep.lastPage)
                },
                readerSettings = settings.map { s ->
                    BackupReaderSettingWire(s.sourceId, s.toonId, viewMode = s.viewMode, readDirection = s.readDirection, splitMode = s.splitMode, updatedAt = s.updatedAt)
                },
            )
        )
    }

    suspend fun restoreBackupJson(database: AppDatabase, jsonString: String): BackupRestoreResult {
        return try {
            val backup = BackupWireCodec.decode(jsonString)
            val favorites = backup.favorites.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = true) ?: return@mapNotNull null
                    FavoriteToon(
                        sourceId = workId.sourceId,
                        id = workId.toonId,
                        title = requireNotNull(item.title) { "Missing title" },
                        thumbUrl = item.thumbUrl, href = item.href, genre = item.genre,
                        updatedAt = item.updatedAt, savedAt = item.savedAt,
                    )
            }

            val history = backup.history.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false) ?: return@mapNotNull null
                    ReadHistory(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        toonTitle = requireNotNull(item.toonTitle) { "Missing toonTitle" },
                        toonThumbUrl = item.toonThumbUrl, toonHref = item.toonHref,
                        lastWrId = requireNotNull(item.lastWrId) { "Missing lastWrId" },
                        lastEpisodeTitle = requireNotNull(item.lastEpisodeTitle) { "Missing lastEpisodeTitle" },
                        lastEpisodeHref = item.lastEpisodeHref, lastReadOrder = item.lastReadOrder,
                        totalEpisodes = item.totalEpisodes, lastReadAt = item.lastReadAt,
                        nextWrId = item.nextWrId, nextEpisodeTitle = item.nextEpisodeTitle,
                        nextEpisodeHref = item.nextEpisodeHref, hasNew = item.hasNew,
                    )
            }

            val episodes = backup.readEpisodes.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false) ?: return@mapNotNull null
                    ReadEpisode(
                        sourceId = workId.sourceId, toonId = workId.toonId,
                        wrId = requireNotNull(item.wrId) { "Missing wrId" },
                        readAt = item.readAt, lastPage = item.lastPage,
                    )
            }

            val settings = backup.readerSettings.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false) ?: return@mapNotNull null
                    ReaderSetting(
                        sourceId = workId.sourceId, toonId = workId.toonId,
                        viewMode = item.viewMode, readDirection = item.readDirection,
                        splitMode = item.splitMode, updatedAt = item.updatedAt,
                    )
            }

            database.withTransaction {
                if (favorites.isNotEmpty()) database.favoriteDao().upsertAll(favorites)
                if (history.isNotEmpty()) database.readHistoryDao().upsertAll(history)
                if (episodes.isNotEmpty()) database.readEpisodeDao().markReadAll(episodes)
                if (settings.isNotEmpty()) database.readerSettingDao().saveAll(settings)
            }

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
            favoriteCount = database.favoriteDao().count(),
            historyCount = database.readHistoryDao().count(),
            episodeCount = database.readEpisodeDao().count(),
            settingCount = database.readerSettingDao().count(),
        )
    }
}

object BackupJson {
    fun workId(obj: JSONObject, preferId: Boolean): WorkId? = BackupWireCodec.workId(
        sourceId = if (obj.isNull("sourceId")) null else obj.optString("sourceId"),
        id = if (obj.isNull("id")) null else obj.optString("id"),
        toonId = if (obj.isNull("toonId")) null else obj.optString("toonId"),
        preferId = preferId,
    )
}
