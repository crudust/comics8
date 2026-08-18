package com.comics8.monitor.data

import com.comics8.core.model.FavoriteListing
import com.comics8.core.model.UpdateDates
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceRegistry
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.FavoriteDao
import com.comics8.monitor.data.local.FavoriteToon
import com.comics8.monitor.data.local.ReadEpisode
import com.comics8.monitor.data.local.ReadEpisodeDao
import com.comics8.monitor.data.local.ReadHistory
import com.comics8.monitor.data.local.ReadHistoryDao
import com.comics8.monitor.data.local.ReaderSetting
import com.comics8.monitor.data.local.ReaderSettingDao
import com.comics8.monitor.data.local.SeenDao
import com.comics8.monitor.data.local.SeenToon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ToonRepository(
    private val client: ToonClient,
    private val seenDao: SeenDao,
    private val favoriteDao: FavoriteDao,
    private val readerSettingDao: ReaderSettingDao,
    private val readEpisodeDao: ReadEpisodeDao,
    private val readHistoryDao: ReadHistoryDao,
    private val database: AppDatabase? = null,
    val syncManager: SyncManager? = null,
    val downloadManager: DownloadManager? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sources: SourceRegistry = SourceRegistry.defaults(),
) {
    suspend fun loadListing(catalog: Catalog, page: Int): ListingPage = withContext(Dispatchers.IO) {
        if (catalog == Catalog.FAVORITE) {
            return@withContext loadFavoriteListing()
        }
        fetchRemoteListing(catalog, page)
    }

    private suspend fun loadFavoriteListing(): ListingPage {
        val local = favoriteDao.all().map { it.toItem().copy(isFavorite = true) }
        val items = FavoriteListing.assemble(local) { page ->
            fetchRemoteListing(Catalog.LATEST, page)
        }
        return ListingPage(
            items = applyFlags(items),
            currentPage = 1,
            lastPage = 1,
        )
    }

    private suspend fun fetchRemoteListing(catalog: Catalog, page: Int): ListingPage {
        val parsed = sources.default.loadListing(catalog.name, page, client)
        if (parsed.items.isEmpty()) {
            error("목록을 읽지 못했습니다. 사이트 구조가 바뀌었을 수 있습니다.")
        }
        return parsed.copy(items = applyFlags(parsed.items))
    }

    suspend fun loadEpisodes(item: ToonItem, page: Int): EpisodePage = withContext(Dispatchers.IO) {
        val parsed = sourceFor(item).loadEpisodes(item, page, client)
        if (parsed.items.isEmpty()) {
            error("회차 목록을 읽지 못했습니다.")
        }
        val firstDate = parsed.items.firstOrNull()?.date
        touchFavoriteUpdatedAt(item.id, firstDate)
        val readMap = readEpisodeDao.getReadEpisodes(item.id).associateBy { it.wrId }
        val enriched = parsed.items.map { ep ->
            val read = readMap[ep.wrId]
            if (read != null) {
                ep.copy(isRead = true, readAt = read.readAt, lastReadPage = read.lastPage)
            } else {
                ep
            }
        }
        parsed.copy(items = enriched)
    }

    suspend fun fetchTotalEpisodes(item: ToonItem): Int = withContext(Dispatchers.IO) {
        val firstPage = loadEpisodes(item, 1)
        if (firstPage.items.isEmpty()) return@withContext 0
        val lastP = firstPage.lastPage.coerceAtLeast(1)
        if (lastP <= 1) {
            return@withContext firstPage.items.size
        }
        val pageSize = 100
        try {
            val lastPageRes = loadEpisodes(item, lastP)
            (lastP - 1) * pageSize + lastPageRes.items.size
        } catch (_: Exception) {
            (lastP - 1) * pageSize + firstPage.items.size
        }
    }

    suspend fun loadImages(episode: EpisodeItem, toonId: String? = null): List<String> = withContext(Dispatchers.IO) {
        if (!toonId.isNullOrBlank() && downloadManager != null) {
            val localImages = downloadManager.getLocalEpisodeImages(toonId, episode.wrId)
            if (!localImages.isNullOrEmpty()) {
                return@withContext localImages
            }
        }
        val item = ToonItem(
            id = toonId.orEmpty(),
            title = "",
            thumbUrl = "",
            href = episode.href,
        )
        val parsed = sourceFor(item).resolveImages(episode, item, client)
        if (parsed.isEmpty()) {
            error("만화 이미지를 불러오지 못했습니다.")
        }
        parsed
    }

    suspend fun search(query: String): List<ToonItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val items = sources.default.search(SearchQuery(q), client)
        applyFlags(items)
    }

    suspend fun markSeen(items: List<ToonItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val nowMs = now()
        val existing = seenDao.byIds(items.map { it.id }).associateBy { it.id }
        val rows = items.map { item ->
            val prev = existing[item.id]
            SeenToon(
                id = item.id,
                title = item.title,
                updatedAt = item.updatedAt,
                firstSeenAt = prev?.firstSeenAt ?: nowMs,
                lastSeenAt = nowMs,
                notifiedKey = prev?.notifiedKey.orEmpty(),
            )
        }
        seenDao.upsertAll(rows)
    }

    suspend fun checkLatestUpdates(): List<ToonItem> {
        val favoriteIds = favoriteDao.ids().toSet()
        if (favoriteIds.isEmpty()) return emptyList()

        val page = loadListing(Catalog.LATEST, 1)
        val candidates = page.items.filter { it.id in favoriteIds }
        if (candidates.isEmpty()) return emptyList()

        val existing = seenDao.byIds(candidates.map { it.id }).associateBy { it.id }
        val unseen = mutableListOf<ToonItem>()
        val updatedSeen = mutableListOf<SeenToon>()
        val nowMs = now()

        for (item in candidates) {
            try {
                val epPage = loadEpisodes(item, 1)
                val latestEp = epPage.items.firstOrNull() ?: continue
                val latestWrId = latestEp.wrId
                val notifyKey = "${item.id}|$latestWrId"

                if (existing[item.id]?.notifiedKey == notifyKey) {
                    continue
                }

                val isRead = readEpisodeDao.getReadEpisode(item.id, latestWrId) != null ||
                    readHistoryDao.get(item.id)?.lastWrId == latestWrId

                val prev = existing[item.id]
                updatedSeen.add(
                    SeenToon(
                        id = item.id,
                        title = item.title,
                        updatedAt = item.updatedAt,
                        firstSeenAt = prev?.firstSeenAt ?: nowMs,
                        lastSeenAt = nowMs,
                        notifiedKey = notifyKey,
                    )
                )

                if (!isRead) {
                    unseen.add(
                        item.copy(
                            href = latestEp.href,
                            updatedAt = latestEp.date ?: item.updatedAt,
                        )
                    )
                }
            } catch (_: Exception) {
                // Ignore transient network errors on individual comic episode check
            }
        }

        if (updatedSeen.isNotEmpty()) {
            seenDao.upsertAll(updatedSeen)
        }

        return unseen
    }

    private suspend fun applyFlags(items: List<ToonItem>): List<ToonItem> {
        if (items.isEmpty()) return items
        val favoriteIds = favoriteDao.ids().toSet()
        val total = seenDao.count()
        val existing = if (total == 0) emptyMap() else {
            seenDao.byIds(items.map { it.id }).associateBy { it.id }
        }
        val historyMap = try {
            readHistoryDao.all().associateBy { it.toonId }
        } catch (_: Exception) {
            emptyMap()
        }
        for (item in items) {
            if (item.id in favoriteIds) {
                touchFavoriteUpdatedAt(item.id, item.updatedAt)
            }
        }
        return items.map { item ->
            val prev = existing[item.id]
            val history = historyMap[item.id]
            val isUpdated = prev != null && !item.updatedAt.isNullOrBlank() && prev.updatedAt != item.updatedAt
            item.copy(
                isNew = total > 0 && (prev == null || isUpdated),
                isFavorite = item.id in favoriteIds,
                readProgress = history?.let { "${it.lastReadOrder}/${it.totalEpisodes}" },
            )
        }
    }

    suspend fun isFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        favoriteDao.exists(id)
    }

    suspend fun toggleFavorite(item: ToonItem): Boolean = withContext(Dispatchers.IO) {
        val exists = favoriteDao.exists(item.id)
        val result = if (exists) {
            favoriteDao.delete(item.id)
            database?.tombstoneDao()?.insert(com.comics8.monitor.data.local.Tombstone("FAVORITE", item.id, now()))
            false
        } else {
            favoriteDao.upsert(
                FavoriteToon(
                    id = item.id,
                    title = item.title,
                    thumbUrl = item.thumbUrl,
                    href = item.href,
                    genre = item.genre,
                    updatedAt = item.updatedAt,
                    savedAt = now(),
                ),
            )
            database?.tombstoneDao()?.delete("FAVORITE", item.id)
            true
        }
        syncManager?.triggerDebouncedPush()
        result
    }

    suspend fun getReaderSetting(toonId: String): Pair<ViewMode, ReadDirection>? = withContext(Dispatchers.IO) {
        val setting = readerSettingDao.getSetting(toonId) ?: return@withContext null
        val viewMode = try {
            ViewMode.valueOf(setting.viewMode)
        } catch (_: Exception) {
            ViewMode.SCROLL
        }
        val direction = try {
            ReadDirection.valueOf(setting.readDirection)
        } catch (_: Exception) {
            ReadDirection.RIGHT_TO_LEFT
        }
        Pair(viewMode, direction)
    }

    suspend fun saveReaderSetting(
        toonId: String,
        viewMode: ViewMode,
        readDirection: ReadDirection,
    ) = withContext(Dispatchers.IO) {
        readerSettingDao.saveSetting(
            ReaderSetting(
                toonId = toonId,
                viewMode = viewMode.name,
                readDirection = readDirection.name,
                updatedAt = now(),
            ),
        )
        syncManager?.triggerDebouncedPush()
    }

    suspend fun markEpisodeRead(toonId: String, wrId: String, lastPage: Int = 0): Long = withContext(Dispatchers.IO) {
        val readAt = now()
        readEpisodeDao.markRead(
            ReadEpisode(
                toonId = toonId,
                wrId = wrId,
                readAt = readAt,
                lastPage = lastPage,
            ),
        )
        syncManager?.triggerDebouncedPush()
        readAt
    }

    suspend fun saveEpisodePage(toonId: String, wrId: String, page: Int) = withContext(Dispatchers.IO) {
        readEpisodeDao.updateLastPage(toonId, wrId, page)
    }

    suspend fun getReadEpisode(toonId: String, wrId: String): ReadEpisode? = withContext(Dispatchers.IO) {
        readEpisodeDao.getReadEpisode(toonId, wrId)
    }

    suspend fun getHistory(): List<ReadHistory> = withContext(Dispatchers.IO) {
        readHistoryDao.all()
    }

    suspend fun saveHistory(history: ReadHistory) = withContext(Dispatchers.IO) {
        readHistoryDao.upsert(history)
        database?.tombstoneDao()?.delete("HISTORY", history.toonId)
        val nowMs = now()
        val existing = seenDao.byIds(listOf(history.toonId)).firstOrNull()
        seenDao.upsertAll(
            listOf(
                SeenToon(
                    id = history.toonId,
                    title = history.toonTitle,
                    updatedAt = existing?.updatedAt,
                    firstSeenAt = existing?.firstSeenAt ?: nowMs,
                    lastSeenAt = nowMs,
                    notifiedKey = "${history.toonId}|${history.lastWrId}",
                )
            )
        )
        syncManager?.triggerDebouncedPush()
    }

    suspend fun deleteHistory(toonId: String) = withContext(Dispatchers.IO) {
        readHistoryDao.delete(toonId)
        database?.tombstoneDao()?.insert(com.comics8.monitor.data.local.Tombstone("HISTORY", toonId, now()))
        syncManager?.triggerDebouncedPush()
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        val all = readHistoryDao.all()
        all.forEach {
            database?.tombstoneDao()?.insert(com.comics8.monitor.data.local.Tombstone("HISTORY", it.toonId, now()))
        }
        readHistoryDao.clearAll()
        syncManager?.triggerDebouncedPush()
    }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val db = database ?: error("데이터베이스를 찾을 수 없습니다.")
        BackupManager.createBackupJson(db)
    }

    suspend fun importBackupJson(jsonString: String): BackupRestoreResult = withContext(Dispatchers.IO) {
        val db = database ?: error("데이터베이스를 찾을 수 없습니다.")
        BackupManager.restoreBackupJson(db, jsonString)
    }

    suspend fun getBackupStats(): BackupStats = withContext(Dispatchers.IO) {
        val db = database ?: return@withContext BackupStats()
        BackupManager.getStats(db)
    }

    private suspend fun touchFavoriteUpdatedAt(id: String, updatedAt: String?) {
        if (updatedAt.isNullOrBlank()) return
        try {
            val current = favoriteDao.get(id) ?: return
            if (!UpdateDates.shouldReplace(current.updatedAt, updatedAt)) return
            val stored = UpdateDates.toListingDate(updatedAt) ?: updatedAt
            favoriteDao.updateUpdatedAt(id, stored)
        } catch (_: Exception) {
        }
    }

    private fun sourceFor(item: ToonItem) = sources.get(item.sourceId)

    private fun notifyKey(item: ToonItem): String = "${item.id}|${item.updatedAt.orEmpty()}"

    private fun FavoriteToon.toItem(): ToonItem = ToonItem(
        id = id,
        title = title,
        thumbUrl = thumbUrl,
        href = href,
        genre = genre,
        updatedAt = updatedAt,
    )
}
