package com.comics8.core.storage

import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.source.WorkId

/**
 * Platform-neutral persistence abstraction backed by Room on Android and SQLite JDBC on Desktop.
 */
interface ComicLocalStore {
    suspend fun <T> inTransaction(block: suspend () -> T): T

    // --- Favorites ---
    suspend fun countFavorites(): Int
    suspend fun getAllFavorites(): List<FavoriteRecord>
    suspend fun getFavoritesByWorkIds(workIds: List<WorkId>): List<FavoriteRecord>
    suspend fun getFavoritesSince(since: Long): List<FavoriteRecord>
    suspend fun getFavorite(workId: WorkId): FavoriteRecord?
    suspend fun hasFavorite(workId: WorkId): Boolean
    suspend fun upsertFavorite(item: FavoriteRecord)
    suspend fun upsertFavorites(items: List<FavoriteRecord>)
    suspend fun updateFavoriteUpdatedAt(workId: WorkId, updatedAt: String)
    suspend fun updateFavoriteThumbUrlIfBlank(workId: WorkId, thumbUrl: String)
    suspend fun deleteFavorite(workId: WorkId)

    // --- Seen ---
    suspend fun countSeen(): Int
    suspend fun getSeenByWorkIds(workIds: List<WorkId>): Map<String, SeenRecord>
    suspend fun upsertSeenAll(items: List<SeenRecord>)

    // --- Reader Settings ---
    suspend fun countReaderSettings(): Int
    suspend fun getReaderSetting(workId: WorkId): ReaderSettingRecord?
    suspend fun getAllReaderSettings(): List<ReaderSettingRecord>
    suspend fun getReaderSettingsSince(since: Long): List<ReaderSettingRecord>
    suspend fun upsertReaderSetting(setting: ReaderSettingRecord)
    suspend fun upsertReaderSettings(settings: List<ReaderSettingRecord>)

    // --- Read Episodes ---
    suspend fun countReadEpisodes(): Int
    suspend fun getReadEpisodes(workId: WorkId): List<ReadEpisodeRecord>
    suspend fun getAllReadEpisodes(): List<ReadEpisodeRecord>
    suspend fun getReadEpisodesSince(since: Long): List<ReadEpisodeRecord>
    suspend fun getReadEpisode(workId: WorkId, wrId: String): ReadEpisodeRecord?
    suspend fun countReadEpisodesByToon(workId: WorkId): Int
    suspend fun countReadEpisodesByWorkIds(workIds: List<WorkId>): Map<String, Int>
    suspend fun upsertReadEpisode(item: ReadEpisodeRecord)
    suspend fun upsertReadEpisodes(items: List<ReadEpisodeRecord>)
    suspend fun updateReadEpisodeLastPage(workId: WorkId, wrId: String, page: Int, readAt: Long)
    suspend fun deleteReadEpisodesByToon(workId: WorkId)

    // --- Read History ---
    suspend fun countHistory(): Int
    suspend fun getAllHistory(): List<ReadHistoryRecord>
    suspend fun getHistoryBySource(sourceId: String): List<ReadHistoryRecord>
    suspend fun getHistoryByWorkIds(workIds: List<WorkId>): List<ReadHistoryRecord>
    suspend fun getHistorySince(since: Long): List<ReadHistoryRecord>
    suspend fun getHistory(workId: WorkId): ReadHistoryRecord?
    suspend fun upsertHistory(item: ReadHistoryRecord)
    suspend fun upsertHistoryAll(items: List<ReadHistoryRecord>)
    suspend fun deleteHistory(workId: WorkId)
    suspend fun clearHistoryBySource(sourceId: String)

    // --- Downloaded Episodes ---
    suspend fun getDownloadedEpisodesBySource(sourceId: String): List<DownloadedEpisodeRecord>
    suspend fun getDownloadedEpisodesByToon(workId: WorkId): List<DownloadedEpisodeRecord>
    suspend fun getDownloadedEpisode(workId: WorkId, wrId: String): DownloadedEpisodeRecord?
    suspend fun upsertDownloadedEpisode(item: DownloadedEpisodeRecord)
    suspend fun deleteDownloadedEpisode(workId: WorkId, wrId: String)
    suspend fun deleteDownloadedEpisodesByToon(workId: WorkId)

    // --- Toon Catalog ---
    suspend fun getCatalogEntry(workId: WorkId): ToonCatalogRecord?
    suspend fun getCatalogTotalsByWorkIds(workIds: List<WorkId>): Map<String, EpisodeListingTotal>
    suspend fun recordCatalogTotal(workId: WorkId, total: EpisodeListingTotal?, updatedAt: Long): Boolean
    suspend fun upsertCatalogEntries(entries: List<ToonCatalogRecord>)

    // --- Tombstones ---
    suspend fun getTombstonesSince(since: Long): List<TombstoneRecord>
    suspend fun recordTombstone(entityType: String, entityId: String, deletedAt: Long)
    suspend fun deleteTombstone(entityType: String, entityId: String)
    suspend fun deleteTombstonesOlderThan(cutoff: Long)
}

interface KeyValuePrefs {
    fun contains(key: String): Boolean
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: defaultValue
    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    fun getInt(key: String, defaultValue: Int): Int =
        getString(key)?.toIntOrNull() ?: defaultValue
    fun putInt(key: String, value: Int) = putString(key, value.toString())
    fun getLong(key: String, defaultValue: Long): Long =
        getString(key)?.toLongOrNull() ?: defaultValue
    fun putLong(key: String, value: Long) = putString(key, value.toString())
}
