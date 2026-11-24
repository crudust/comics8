package com.comics8.shared.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.source.WorkId
import com.comics8.core.storage.ComicLocalStore
import com.comics8.core.storage.DownloadedEpisodeRecord
import com.comics8.core.storage.FavoriteRecord
import com.comics8.core.storage.ReadEpisodeRecord
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.core.storage.ReaderSettingRecord
import com.comics8.core.storage.SeenRecord
import com.comics8.core.storage.TombstoneRecord
import com.comics8.core.storage.ToonCatalogRecord

class RoomComicLocalStore(
    private val database: AppDatabase,
) : ComicLocalStore {
    private val seenDao = database.seenDao()
    private val favoriteDao = database.favoriteDao()
    private val readerSettingDao = database.readerSettingDao()
    private val readEpisodeDao = database.readEpisodeDao()
    private val readHistoryDao = database.readHistoryDao()
    private val toonCatalogDao = database.toonCatalogDao()
    private val downloadedEpisodeDao = database.downloadedEpisodeDao()
    private val tombstoneDao = database.tombstoneDao()

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                block()
            }
        }

    // --- Favorites ---
    override suspend fun countFavorites(): Int = favoriteDao.count()
    override suspend fun getAllFavorites(): List<FavoriteRecord> = favoriteDao.all().map { it.toRecord() }
    override suspend fun getFavoritesByWorkIds(workIds: List<WorkId>): List<FavoriteRecord> {
        if (workIds.isEmpty()) return emptyList()
        val wanted = workIds.map { it.storageKey() }.toSet()
        return favoriteDao.byToonIds(workIds.map { it.toonId }.distinct())
            .filter { it.workId().storageKey() in wanted }
            .map { it.toRecord() }
    }
    override suspend fun getFavoritesSince(since: Long): List<FavoriteRecord> =
        favoriteDao.getSince(since).map { it.toRecord() }
    override suspend fun getFavorite(workId: WorkId): FavoriteRecord? = favoriteDao.get(workId)?.toRecord()
    override suspend fun hasFavorite(workId: WorkId): Boolean = favoriteDao.exists(workId)
    override suspend fun upsertFavorite(item: FavoriteRecord) = favoriteDao.upsert(item.toRoom())
    override suspend fun upsertFavorites(items: List<FavoriteRecord>) =
        favoriteDao.upsertAll(items.map { it.toRoom() })
    override suspend fun updateFavoriteUpdatedAt(workId: WorkId, updatedAt: String) =
        favoriteDao.updateUpdatedAt(workId, updatedAt)
    override suspend fun updateFavoriteThumbUrlIfBlank(workId: WorkId, thumbUrl: String) =
        favoriteDao.updateThumbUrlIfBlank(workId, thumbUrl)
    override suspend fun deleteFavorite(workId: WorkId) = favoriteDao.delete(workId)

    // --- Seen ---
    override suspend fun countSeen(): Int = seenDao.count()
    override suspend fun getSeenByWorkIds(workIds: List<WorkId>): Map<String, SeenRecord> {
        if (workIds.isEmpty()) return emptyMap()
        val wanted = workIds.map { it.storageKey() }.toSet()
        return seenDao.byToonIds(workIds.map { it.toonId }.distinct())
            .filter { it.workId().storageKey() in wanted }
            .associate { it.workId().storageKey() to it.toRecord() }
    }
    override suspend fun upsertSeenAll(items: List<SeenRecord>) =
        seenDao.upsertAll(items.map { it.toRoom() })

    // --- Reader Settings ---
    override suspend fun countReaderSettings(): Int = readerSettingDao.count()
    override suspend fun getReaderSetting(workId: WorkId): ReaderSettingRecord? =
        readerSettingDao.getSetting(workId)?.toRecord()
    override suspend fun getAllReaderSettings(): List<ReaderSettingRecord> =
        readerSettingDao.all().map { it.toRecord() }
    override suspend fun getReaderSettingsSince(since: Long): List<ReaderSettingRecord> =
        readerSettingDao.getSince(since).map { it.toRecord() }
    override suspend fun upsertReaderSetting(setting: ReaderSettingRecord) =
        readerSettingDao.saveSetting(setting.toRoom())
    override suspend fun upsertReaderSettings(settings: List<ReaderSettingRecord>) =
        readerSettingDao.saveAll(settings.map { it.toRoom() })

    // --- Read Episodes ---
    override suspend fun countReadEpisodes(): Int = readEpisodeDao.count()
    override suspend fun getReadEpisodes(workId: WorkId): List<ReadEpisodeRecord> =
        readEpisodeDao.getReadEpisodes(workId).map { it.toRecord() }
    override suspend fun getAllReadEpisodes(): List<ReadEpisodeRecord> =
        readEpisodeDao.all().map { it.toRecord() }
    override suspend fun getReadEpisodesSince(since: Long): List<ReadEpisodeRecord> =
        readEpisodeDao.getSince(since).map { it.toRecord() }
    override suspend fun getReadEpisode(workId: WorkId, wrId: String): ReadEpisodeRecord? =
        readEpisodeDao.getReadEpisode(workId, wrId)?.toRecord()
    override suspend fun countReadEpisodesByToon(workId: WorkId): Int =
        readEpisodeDao.countByToon(workId)
    override suspend fun countReadEpisodesByWorkIds(workIds: List<WorkId>): Map<String, Int> {
        if (workIds.isEmpty()) return emptyMap()
        val wanted = workIds.map { it.storageKey() }.toSet()
        return readEpisodeDao.countsByToonIds(workIds.map { it.toonId }.distinct())
            .mapNotNull { row ->
                val key = row.storageKey()
                if (key in wanted) key to row.readCount else null
            }
            .toMap()
    }
    override suspend fun upsertReadEpisode(item: ReadEpisodeRecord) =
        readEpisodeDao.markRead(item.toRoom())
    override suspend fun upsertReadEpisodes(items: List<ReadEpisodeRecord>) =
        readEpisodeDao.markReadAll(items.map { it.toRoom() })
    override suspend fun updateReadEpisodeLastPage(workId: WorkId, wrId: String, page: Int, readAt: Long) =
        readEpisodeDao.updateLastPage(workId, wrId, page, readAt)
    override suspend fun deleteReadEpisodesByToon(workId: WorkId) =
        readEpisodeDao.deleteByToonId(workId)

    // --- Read History ---
    override suspend fun countHistory(): Int = readHistoryDao.count()
    override suspend fun getAllHistory(): List<ReadHistoryRecord> =
        readHistoryDao.all().map { it.toRecord() }
    override suspend fun getHistoryBySource(sourceId: String): List<ReadHistoryRecord> =
        readHistoryDao.bySource(sourceId).map { it.toRecord() }
    override suspend fun getHistoryByWorkIds(workIds: List<WorkId>): List<ReadHistoryRecord> {
        if (workIds.isEmpty()) return emptyList()
        val wanted = workIds.map { it.storageKey() }.toSet()
        return readHistoryDao.byToonIds(workIds.map { it.toonId }.distinct())
            .filter { it.workId().storageKey() in wanted }
            .map { it.toRecord() }
    }
    override suspend fun getHistorySince(since: Long): List<ReadHistoryRecord> =
        readHistoryDao.getSince(since).map { it.toRecord() }
    override suspend fun getHistory(workId: WorkId): ReadHistoryRecord? =
        readHistoryDao.get(workId)?.toRecord()
    override suspend fun upsertHistory(item: ReadHistoryRecord) =
        readHistoryDao.upsert(item.toRoom())
    override suspend fun upsertHistoryAll(items: List<ReadHistoryRecord>) =
        readHistoryDao.upsertAll(items.map { it.toRoom() })
    override suspend fun deleteHistory(workId: WorkId) = readHistoryDao.delete(workId)
    override suspend fun clearHistoryBySource(sourceId: String) = readHistoryDao.clearBySource(sourceId)

    // --- Downloaded Episodes ---
    override suspend fun getDownloadedEpisodesBySource(sourceId: String): List<DownloadedEpisodeRecord> =
        downloadedEpisodeDao.bySource(sourceId).map { it.toRecord() }
    override suspend fun getDownloadedEpisodesByToon(workId: WorkId): List<DownloadedEpisodeRecord> =
        downloadedEpisodeDao.byToon(workId).map { it.toRecord() }
    override suspend fun getDownloadedEpisode(workId: WorkId, wrId: String): DownloadedEpisodeRecord? =
        downloadedEpisodeDao.get(workId, wrId)?.toRecord()
    override suspend fun upsertDownloadedEpisode(item: DownloadedEpisodeRecord) =
        downloadedEpisodeDao.upsert(item.toRoom())
    override suspend fun deleteDownloadedEpisode(workId: WorkId, wrId: String) =
        downloadedEpisodeDao.delete(workId, wrId)
    override suspend fun deleteDownloadedEpisodesByToon(workId: WorkId) =
        downloadedEpisodeDao.deleteByToon(workId)

    // --- Toon Catalog ---
    override suspend fun getCatalogEntry(workId: WorkId): ToonCatalogRecord? =
        toonCatalogDao.get(workId)?.toRecord()
    override suspend fun getCatalogTotalsByWorkIds(workIds: List<WorkId>): Map<String, EpisodeListingTotal> {
        if (workIds.isEmpty()) return emptyMap()
        val wanted = workIds.map { it.storageKey() }.toSet()
        return toonCatalogDao.byToonIds(workIds.map { it.toonId }.distinct())
            .filter { it.workId().storageKey() in wanted }
            .mapNotNull { entry -> entry.total()?.let { entry.workId().storageKey() to it } }
            .toMap()
    }
    override suspend fun recordCatalogTotal(workId: WorkId, total: EpisodeListingTotal?, updatedAt: Long): Boolean =
        toonCatalogDao.recordTotal(workId, total, updatedAt)
    override suspend fun upsertCatalogEntries(entries: List<ToonCatalogRecord>) {
        toonCatalogDao.upsertAllCounted(entries.map { it.toRoom() })
    }

    // --- Tombstones ---
    override suspend fun getTombstonesSince(since: Long): List<TombstoneRecord> =
        tombstoneDao.getSince(since).map { TombstoneRecord(it.entityType, it.entityId, it.deletedAt) }
    override suspend fun recordTombstone(entityType: String, entityId: String, deletedAt: Long) =
        tombstoneDao.insert(Tombstone(entityType, entityId, deletedAt))
    override suspend fun deleteTombstone(entityType: String, entityId: String) =
        tombstoneDao.delete(entityType, entityId)
    override suspend fun deleteTombstonesOlderThan(cutoff: Long) =
        tombstoneDao.deleteOlderThan(cutoff)
}

fun FavoriteToon.toRecord() = FavoriteRecord(sourceId, id, title, thumbUrl, href, genre, updatedAt, savedAt)
fun FavoriteRecord.toRoom() = FavoriteToon(sourceId, id, title, thumbUrl, href, genre, updatedAt, savedAt)

fun SeenToon.toRecord() = SeenRecord(sourceId, id, title, updatedAt, firstSeenAt, lastSeenAt, notifiedKey)
fun SeenRecord.toRoom() = SeenToon(sourceId, id, title, updatedAt, firstSeenAt, lastSeenAt, notifiedKey)

fun ReadEpisode.toRecord() = ReadEpisodeRecord(sourceId, toonId, wrId, readAt, lastPage)
fun ReadEpisodeRecord.toRoom() = ReadEpisode(sourceId, toonId, wrId, readAt, lastPage)

fun ReadHistory.toRecord() = ReadHistoryRecord(
    sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref,
    lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew,
)
fun ReadHistoryRecord.toRoom() = ReadHistory(
    sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref,
    lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew,
)

fun ReaderSetting.toRecord() = ReaderSettingRecord(sourceId, toonId, viewMode, readDirection, splitMode, updatedAt)
fun ReaderSettingRecord.toRoom() = ReaderSetting(sourceId, toonId, viewMode, readDirection, splitMode, updatedAt)

fun DownloadedEpisode.toRecord() = DownloadedEpisodeRecord(
    sourceId, toonId, wrId, toonTitle, toonThumbUrl, toonHref, episodeTitle, episodeHref,
    imageCount, totalBytes, downloadedAt, localDirPath,
)
fun DownloadedEpisodeRecord.toRoom() = DownloadedEpisode(
    sourceId, toonId, wrId, toonTitle, toonThumbUrl, toonHref, episodeTitle, episodeHref,
    imageCount, totalBytes, downloadedAt, localDirPath,
)

fun ToonCatalogEntry.toRecord() = ToonCatalogRecord(sourceId, id, totalEpisodes, updatedAt, lastPage, pageSize, delta)
fun ToonCatalogRecord.toRoom() = ToonCatalogEntry(sourceId, toonId, totalEpisodes, updatedAt, lastPage, pageSize, delta)
