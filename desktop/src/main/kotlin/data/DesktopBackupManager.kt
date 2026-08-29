package com.comics8.desktop.data

import com.comics8.core.backup.BackupEpisodeWire
import com.comics8.core.backup.BackupFavoriteWire
import com.comics8.core.backup.BackupHistoryWire
import com.comics8.core.backup.BackupReaderSettingWire
import com.comics8.core.backup.BackupWire
import com.comics8.core.backup.BackupWireCodec
import com.comics8.core.model.BackupResult
import com.comics8.core.model.BackupStats
import com.comics8.core.source.WorkId
import org.json.JSONObject

typealias DesktopBackupStats = BackupStats
typealias DesktopBackupResult = BackupResult

object DesktopBackupManager {
    fun createBackupJson(database: DesktopDatabase): String {
        val favorites = database.getAllFavorites()
        val history = database.getAllHistory()
        val episodes = database.getAllReadEpisodes()
        val settings = database.getAllReaderSettings()

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

    fun restoreBackupJson(database: DesktopDatabase, jsonString: String): DesktopBackupResult {
        return try {
            val backup = BackupWireCodec.decode(jsonString)
            val favorites = backup.favorites.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = true) ?: return@mapNotNull null
                    FavoriteRecord(
                        sourceId = workId.sourceId,
                        id = workId.toonId,
                        title = item.title.orEmpty(), thumbUrl = item.thumbUrl,
                        href = item.href, genre = item.genre,
                        updatedAt = item.updatedAt, savedAt = item.savedAt,
                    )
            }

            val history = backup.history.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false) ?: return@mapNotNull null
                    ReadHistoryRecord(
                        sourceId = workId.sourceId, toonId = workId.toonId,
                        toonTitle = item.toonTitle.orEmpty(), toonThumbUrl = item.toonThumbUrl,
                        toonHref = item.toonHref, lastWrId = item.lastWrId.orEmpty(),
                        lastEpisodeTitle = item.lastEpisodeTitle.orEmpty(), lastEpisodeHref = item.lastEpisodeHref,
                        lastReadOrder = item.lastReadOrder, totalEpisodes = item.totalEpisodes,
                        lastReadAt = item.lastReadAt, nextWrId = item.nextWrId,
                        nextEpisodeTitle = item.nextEpisodeTitle, nextEpisodeHref = item.nextEpisodeHref,
                        hasNew = item.hasNew,
                    )
            }

            val episodes = backup.readEpisodes.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false) ?: return@mapNotNull null
                    ReadEpisodeRecord(
                        sourceId = workId.sourceId, toonId = workId.toonId,
                        wrId = item.wrId.orEmpty(), readAt = item.readAt, lastPage = item.lastPage,
                    )
            }

            val settings = backup.readerSettings.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false) ?: return@mapNotNull null
                    ReaderSettingRecord(
                        sourceId = workId.sourceId, toonId = workId.toonId,
                        viewMode = item.viewMode, readDirection = item.readDirection,
                        splitMode = item.splitMode, updatedAt = item.updatedAt,
                    )
            }

            database.restoreBackup(favorites, history, episodes, settings)

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
        return database.getBackupStats()
    }
}

object DesktopBackupJson {
    fun workId(obj: JSONObject, preferId: Boolean): WorkId? = BackupWireCodec.workId(
        sourceId = if (obj.isNull("sourceId")) null else obj.optString("sourceId"),
        id = if (obj.isNull("id")) null else obj.optString("id"),
        toonId = if (obj.isNull("toonId")) null else obj.optString("toonId"),
        preferId = preferId,
    )
}
