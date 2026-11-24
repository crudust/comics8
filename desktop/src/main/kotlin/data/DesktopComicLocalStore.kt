package com.comics8.desktop.data

import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.source.WorkId
import com.comics8.core.storage.ComicLocalStore
import com.comics8.core.storage.DownloadedEpisodeRecord
import com.comics8.core.storage.FavoriteRecord
import com.comics8.core.storage.KeyValuePrefs
import com.comics8.core.storage.ReadEpisodeRecord
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.core.storage.ReaderSettingRecord
import com.comics8.core.storage.SeenRecord
import com.comics8.core.storage.TombstoneRecord
import com.comics8.core.storage.ToonCatalogRecord
import java.io.File
import java.util.Properties

class DesktopComicLocalStore(
    val database: DesktopDatabase,
) : ComicLocalStore {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.runInTransaction { kotlinx.coroutines.runBlocking { block() } }

    override suspend fun countFavorites(): Int = database.getBackupStats().favoriteCount
    override suspend fun getAllFavorites(): List<FavoriteRecord> = database.getAllFavorites()
    override suspend fun getFavoritesByWorkIds(workIds: List<WorkId>): List<FavoriteRecord> =
        database.getFavoritesByToonIds(workIds)
    override suspend fun getFavoritesSince(since: Long): List<FavoriteRecord> =
        database.getFavoritesSince(since)
    override suspend fun getFavorite(workId: WorkId): FavoriteRecord? = database.getFavorite(workId)
    override suspend fun hasFavorite(workId: WorkId): Boolean = database.isFavorite(workId)
    override suspend fun upsertFavorite(item: FavoriteRecord) = database.saveFavorite(item)
    override suspend fun upsertFavorites(items: List<FavoriteRecord>) = database.saveAllFavorites(items)
    override suspend fun updateFavoriteUpdatedAt(workId: WorkId, updatedAt: String) =
        database.updateFavoriteUpdatedAt(workId, updatedAt)
    override suspend fun updateFavoriteThumbUrlIfBlank(workId: WorkId, thumbUrl: String) =
        database.updateFavoriteThumbnail(workId, thumbUrl)
    override suspend fun deleteFavorite(workId: WorkId) = database.deleteFavorite(workId)

    override suspend fun countSeen(): Int = database.getSeenCount()
    override suspend fun getSeenByWorkIds(workIds: List<WorkId>): Map<String, SeenRecord> =
        database.getSeenByIds(workIds)
    override suspend fun upsertSeenAll(items: List<SeenRecord>) = database.saveAllSeen(items)

    override suspend fun countReaderSettings(): Int = database.getBackupStats().settingCount
    override suspend fun getReaderSetting(workId: WorkId): ReaderSettingRecord? =
        database.getReaderSetting(workId)
    override suspend fun getAllReaderSettings(): List<ReaderSettingRecord> =
        database.getAllReaderSettings()
    override suspend fun getReaderSettingsSince(since: Long): List<ReaderSettingRecord> =
        database.getReaderSettingsSince(since)
    override suspend fun upsertReaderSetting(setting: ReaderSettingRecord) =
        database.saveReaderSetting(setting)
    override suspend fun upsertReaderSettings(settings: List<ReaderSettingRecord>) =
        database.saveAllReaderSettings(settings)

    override suspend fun countReadEpisodes(): Int = database.getBackupStats().episodeCount
    override suspend fun getReadEpisodes(workId: WorkId): List<ReadEpisodeRecord> =
        database.getReadEpisodesByToon(workId)
    override suspend fun getAllReadEpisodes(): List<ReadEpisodeRecord> = database.getAllReadEpisodes()
    override suspend fun getReadEpisodesSince(since: Long): List<ReadEpisodeRecord> =
        database.getReadEpisodesSince(since)
    override suspend fun getReadEpisode(workId: WorkId, wrId: String): ReadEpisodeRecord? =
        database.getReadEpisode(workId, wrId)
    override suspend fun countReadEpisodesByToon(workId: WorkId): Int =
        database.countReadEpisodes(workId)
    override suspend fun countReadEpisodesByWorkIds(workIds: List<WorkId>): Map<String, Int> =
        database.countReadEpisodes(workIds)
    override suspend fun upsertReadEpisode(item: ReadEpisodeRecord) = database.markEpisodeRead(item)
    override suspend fun upsertReadEpisodes(items: List<ReadEpisodeRecord>) =
        database.markAllEpisodesRead(items)
    override suspend fun updateReadEpisodeLastPage(workId: WorkId, wrId: String, page: Int, readAt: Long) =
        database.updateLastPage(workId, wrId, page, readAt)
    override suspend fun deleteReadEpisodesByToon(workId: WorkId) =
        database.deleteReadEpisodesByToon(workId)

    override suspend fun countHistory(): Int = database.getBackupStats().historyCount
    override suspend fun getAllHistory(): List<ReadHistoryRecord> = database.getAllHistory()
    override suspend fun getHistoryBySource(sourceId: String): List<ReadHistoryRecord> =
        database.getHistoryBySource(sourceId)
    override suspend fun getHistoryByWorkIds(workIds: List<WorkId>): List<ReadHistoryRecord> =
        database.getHistoryByToonIds(workIds)
    override suspend fun getHistorySince(since: Long): List<ReadHistoryRecord> =
        database.getHistorySince(since)
    override suspend fun getHistory(workId: WorkId): ReadHistoryRecord? = database.getHistory(workId)
    override suspend fun upsertHistory(item: ReadHistoryRecord) = database.saveHistory(item)
    override suspend fun upsertHistoryAll(items: List<ReadHistoryRecord>) = database.saveAllHistory(items)
    override suspend fun deleteHistory(workId: WorkId) = database.deleteHistory(workId)
    override suspend fun clearHistoryBySource(sourceId: String) = database.clearHistoryBySource(sourceId)

    override suspend fun getDownloadedEpisodesBySource(sourceId: String): List<DownloadedEpisodeRecord> =
        database.getDownloadedEpisodesBySource(sourceId)
    override suspend fun getDownloadedEpisodesByToon(workId: WorkId): List<DownloadedEpisodeRecord> =
        database.getDownloadedEpisodesByToon(workId)
    override suspend fun getDownloadedEpisode(workId: WorkId, wrId: String): DownloadedEpisodeRecord? =
        database.getDownloadedEpisode(workId, wrId)
    override suspend fun upsertDownloadedEpisode(item: DownloadedEpisodeRecord) =
        database.saveDownloadedEpisode(item)
    override suspend fun deleteDownloadedEpisode(workId: WorkId, wrId: String) =
        database.deleteDownloadedEpisode(workId, wrId)
    override suspend fun deleteDownloadedEpisodesByToon(workId: WorkId) =
        database.deleteDownloadedEpisodesByToon(workId)

    override suspend fun getCatalogEntry(workId: WorkId): ToonCatalogRecord? =
        database.getCatalog(workId)
    override suspend fun getCatalogTotalsByWorkIds(workIds: List<WorkId>): Map<String, EpisodeListingTotal> =
        database.getCatalogTotalsByToonIds(workIds)
    override suspend fun recordCatalogTotal(workId: WorkId, total: EpisodeListingTotal?, updatedAt: Long): Boolean =
        database.recordCatalogTotal(workId, total, updatedAt)
    override suspend fun upsertCatalogEntries(entries: List<ToonCatalogRecord>) =
        database.saveAllCatalog(entries)

    override suspend fun getTombstonesSince(since: Long): List<TombstoneRecord> =
        database.getTombstonesSince(since).map { TombstoneRecord(it.entityType, it.entityId, it.deletedAt) }
    override suspend fun recordTombstone(entityType: String, entityId: String, deletedAt: Long) =
        database.recordTombstone(entityType, entityId, deletedAt)
    override suspend fun deleteTombstone(entityType: String, entityId: String) =
        database.deleteTombstone(entityType, entityId)
    override suspend fun deleteTombstonesOlderThan(cutoff: Long) =
        database.deleteTombstonesOlderThan(cutoff)
}

class DesktopPropertiesPrefs(
    private val configFile: File = File(System.getProperty("user.home"), ".comics8/sync.properties"),
) : KeyValuePrefs {
    private val properties = Properties().apply {
        if (configFile.exists()) {
            try {
                configFile.inputStream().use { load(it) }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveProperties() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.outputStream().use { properties.store(it, "Comics8 Sync Configuration") }
        } catch (_: Exception) {
        }
    }

    override fun contains(key: String): Boolean = properties.containsKey(key)

    override fun getString(key: String, defaultValue: String?): String? =
        properties.getProperty(key, defaultValue)

    override fun putString(key: String, value: String?) {
        if (value == null) {
            properties.remove(key)
        } else {
            properties.setProperty(key, value)
        }
        saveProperties()
    }
}
