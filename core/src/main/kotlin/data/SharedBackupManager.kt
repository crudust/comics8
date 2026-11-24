package com.comics8.core.data

import com.comics8.core.backup.BackupEpisodeWire
import com.comics8.core.backup.BackupFavoriteWire
import com.comics8.core.backup.BackupHistoryWire
import com.comics8.core.backup.BackupReaderSettingWire
import com.comics8.core.backup.BackupWire
import com.comics8.core.backup.BackupWireCodec
import com.comics8.core.model.BackupResult
import com.comics8.core.model.BackupStats
import com.comics8.core.storage.ComicLocalStore
import com.comics8.core.storage.FavoriteRecord
import com.comics8.core.storage.ReadEpisodeRecord
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.core.storage.ReaderSettingRecord

object SharedBackupManager {
    const val BACKUP_VERSION = BackupWireCodec.VERSION

    suspend fun createBackupJson(
        store: ComicLocalStore,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): String {
        val favorites = store.getAllFavorites()
        val history = store.getAllHistory()
        val episodes = store.getAllReadEpisodes()
        val settings = store.getAllReaderSettings()

        return BackupWireCodec.encode(
            BackupWire(
                exportedAt = nowMillis(),
                favorites = favorites.map { f ->
                    BackupFavoriteWire(
                        sourceId = f.sourceId,
                        id = f.id,
                        title = f.title,
                        thumbUrl = f.thumbUrl,
                        href = f.href,
                        genre = f.genre,
                        updatedAt = f.updatedAt,
                        savedAt = f.savedAt,
                    )
                },
                history = history.map { h ->
                    BackupHistoryWire(
                        sourceId = h.sourceId,
                        toonId = h.toonId,
                        toonTitle = h.toonTitle,
                        toonThumbUrl = h.toonThumbUrl,
                        toonHref = h.toonHref,
                        lastWrId = h.lastWrId,
                        lastEpisodeTitle = h.lastEpisodeTitle,
                        lastEpisodeHref = h.lastEpisodeHref,
                        lastReadOrder = h.lastReadOrder,
                        totalEpisodes = h.totalEpisodes,
                        lastReadAt = h.lastReadAt,
                        nextWrId = h.nextWrId,
                        nextEpisodeTitle = h.nextEpisodeTitle,
                        nextEpisodeHref = h.nextEpisodeHref,
                        hasNew = h.hasNew,
                    )
                },
                readEpisodes = episodes.map { ep ->
                    BackupEpisodeWire(
                        sourceId = ep.sourceId,
                        toonId = ep.toonId,
                        wrId = ep.wrId,
                        readAt = ep.readAt,
                        lastPage = ep.lastPage,
                    )
                },
                readerSettings = settings.map { s ->
                    BackupReaderSettingWire(
                        sourceId = s.sourceId,
                        toonId = s.toonId,
                        viewMode = s.viewMode,
                        readDirection = s.readDirection,
                        splitMode = s.splitMode,
                        updatedAt = s.updatedAt,
                    )
                },
            ),
        )
    }

    suspend fun restoreBackupJson(store: ComicLocalStore, jsonString: String): BackupResult {
        return try {
            val backup = BackupWireCodec.decode(jsonString)
            val favorites = backup.favorites.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = true)
                    ?: return@mapNotNull null
                FavoriteRecord(
                    sourceId = workId.sourceId,
                    id = workId.toonId,
                    title = requireNotNull(item.title) { "Missing title" },
                    thumbUrl = item.thumbUrl,
                    href = item.href,
                    genre = item.genre,
                    updatedAt = item.updatedAt,
                    savedAt = item.savedAt,
                )
            }

            val history = backup.history.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false)
                    ?: return@mapNotNull null
                ReadHistoryRecord(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    toonTitle = requireNotNull(item.toonTitle) { "Missing toonTitle" },
                    toonThumbUrl = item.toonThumbUrl,
                    toonHref = item.toonHref,
                    lastWrId = requireNotNull(item.lastWrId) { "Missing lastWrId" },
                    lastEpisodeTitle = item.lastEpisodeTitle.orEmpty(),
                    lastEpisodeHref = item.lastEpisodeHref,
                    lastReadOrder = item.lastReadOrder,
                    totalEpisodes = item.totalEpisodes,
                    lastReadAt = item.lastReadAt,
                    nextWrId = item.nextWrId,
                    nextEpisodeTitle = item.nextEpisodeTitle,
                    nextEpisodeHref = item.nextEpisodeHref,
                    hasNew = item.hasNew,
                )
            }

            val episodes = backup.readEpisodes.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false)
                    ?: return@mapNotNull null
                ReadEpisodeRecord(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    wrId = requireNotNull(item.wrId) { "Missing wrId" },
                    readAt = item.readAt,
                    lastPage = item.lastPage,
                )
            }

            val settings = backup.readerSettings.mapNotNull { item ->
                val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false)
                    ?: return@mapNotNull null
                ReaderSettingRecord(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    viewMode = item.viewMode,
                    readDirection = item.readDirection,
                    splitMode = item.splitMode,
                    updatedAt = item.updatedAt,
                )
            }

            store.inTransaction {
                if (favorites.isNotEmpty()) store.upsertFavorites(favorites)
                if (history.isNotEmpty()) store.upsertHistoryAll(history)
                if (episodes.isNotEmpty()) store.upsertReadEpisodes(episodes)
                if (settings.isNotEmpty()) store.upsertReaderSettings(settings)
            }

            BackupResult(
                success = true,
                message = "복원 완료",
                favoriteCount = favorites.size,
                historyCount = history.size,
                episodeCount = episodes.size,
                settingCount = settings.size,
            )
        } catch (e: Exception) {
            BackupResult(
                success = false,
                message = "백업 파일 형식이 올바르지 않습니다: ${e.localizedMessage ?: "알 수 없는 오류"}",
            )
        }
    }

    suspend fun getStats(store: ComicLocalStore): BackupStats {
        return BackupStats(
            favoriteCount = store.countFavorites(),
            historyCount = store.countHistory(),
            episodeCount = store.countReadEpisodes(),
            settingCount = store.countReaderSettings(),
        )
    }
}
