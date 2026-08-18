package com.comics8.desktop.data

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.FavoriteListing
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.core.source.hitomi.HitomiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopToonRepository(
    private val client: ToonClient,
    private val database: DesktopDatabase,
    val syncManager: DesktopSyncManager? = null,
    val downloadManager: DesktopDownloadManager? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sources: SourceRegistry = SourceRegistry.defaults(),
    private val hitomiEnabled: () -> Boolean = { false },
) {
    fun source(id: String) = sources.get(id)

    fun allSources() = sources.all()

    fun applyHitomiLanguage(language: String) {
        (sources.get(HitomiSource.ID) as? HitomiSource)?.language =
            language.ifBlank { HitomiSource.DEFAULT_LANGUAGE }
    }

    fun setHitomiWrites(enabled: Boolean) {
        database.allowHitomiWrites = enabled
    }

    suspend fun loadListing(tab: BrowseTab, page: Int): ListingPage = withContext(Dispatchers.IO) {
        when (tab) {
            BrowseTab.Favorite -> loadFavoriteListing()
            is BrowseTab.Remote -> fetchRemoteListing(tab.sourceId, tab.catalog.id, page)
        }
    }

    private suspend fun loadFavoriteListing(): ListingPage {
        val local = database.getAllFavorites().map { it.toItem().copy(isFavorite = true) }
        val items = FavoriteListing.assembleBySource(local) { sourceId, page ->
            if (sourceId == HitomiSource.ID) {
                null
            } else {
                fetchRemoteListing(sourceId, "LATEST", page)
            }
        }
        return ListingPage(
            items = applyFlags(items),
            currentPage = 1,
            lastPage = 1,
        )
    }

    private suspend fun fetchRemoteListing(sourceId: String, catalogId: String, page: Int): ListingPage {
        val parsed = sources.get(sourceId).loadListing(catalogId, page, client)
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
        val workId = item.workId()
        if (!firstDate.isNullOrBlank()) {
            try {
                database.updateFavoriteUpdatedAt(workId, firstDate)
            } catch (_: Exception) {
            }
        }
        val readMap = database.getReadEpisodesByToon(workId).associateBy { it.wrId }
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

    suspend fun loadImages(episode: EpisodeItem, workId: WorkId? = null): List<String> = withContext(Dispatchers.IO) {
        if (workId != null && downloadManager != null) {
            val localImages = downloadManager.getLocalEpisodeImages(workId, episode.wrId)
            if (!localImages.isNullOrEmpty()) {
                return@withContext localImages
            }
        }
        val item = ToonItem(
            id = workId?.toonId.orEmpty(),
            title = "",
            thumbUrl = "",
            href = episode.href,
            sourceId = workId?.sourceId ?: WorkId.DEFAULT_SOURCE,
        )
        val parsed = sourceFor(item).resolveImages(episode, item, client)
        if (parsed.isEmpty()) {
            error("만화 이미지를 불러오지 못했습니다.")
        }
        parsed
    }

    suspend fun search(query: String, sourceId: String = WorkId.DEFAULT_SOURCE): List<ToonItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val source = sources.get(sourceId)
            val language = (source as? HitomiSource)?.language
            val items = source.search(SearchQuery(q, language = language), client)
            applyFlags(items)
        }

    suspend fun markSeen(items: List<ToonItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val nowMs = now()
        val existing = database.getSeenByIds(items.map { it.workId() })
        val rows = items.mapNotNull { item ->
            val workId = WorkId.writable(item.sourceId, item.id, hitomiEnabled()) ?: return@mapNotNull null
            val prev = existing[workId.storageKey()]
            SeenRecord(
                sourceId = workId.sourceId,
                id = workId.toonId,
                title = item.title,
                updatedAt = item.updatedAt,
                firstSeenAt = prev?.firstSeenAt ?: nowMs,
                lastSeenAt = nowMs,
                notifiedKey = notifyKey(item),
            )
        }
        database.saveAllSeen(rows)
    }

    private suspend fun applyFlags(items: List<ToonItem>): List<ToonItem> {
        if (items.isEmpty()) return items
        val favoriteIds = database.getFavoriteIds()
        val favoriteKeys = favoriteIds.map { it.storageKey() }.toSet()
        val total = database.getSeenCount()
        val existing = if (total == 0) emptyMap() else database.getSeenByIds(items.map { it.workId() })
        val historyMap = database.getAllHistory().associateBy { it.workId().storageKey() }

        for (item in items) {
            val uDate = item.updatedAt
            if (item.workId().storageKey() in favoriteKeys && !uDate.isNullOrBlank()) {
                try {
                    database.updateFavoriteUpdatedAt(item.workId(), uDate)
                } catch (_: Exception) {}
            }
        }

        return items.map { item ->
            val key = item.workId().storageKey()
            val prev = existing[key]
            val history = historyMap[key]
            val isUpdated = prev != null && !item.updatedAt.isNullOrBlank() && prev.updatedAt != item.updatedAt
            item.copy(
                isNew = total > 0 && (prev == null || isUpdated),
                isFavorite = key in favoriteKeys,
                readProgress = history?.let { "${it.lastReadOrder}/${it.totalEpisodes}" },
            )
        }
    }

    suspend fun isFavorite(workId: WorkId): Boolean = withContext(Dispatchers.IO) {
        database.isFavorite(workId)
    }

    suspend fun toggleFavorite(item: ToonItem): Boolean? = withContext(Dispatchers.IO) {
        val workId = WorkId.writable(item.sourceId, item.id, hitomiEnabled()) ?: return@withContext null
        val exists = database.isFavorite(workId)
        val result = if (exists) {
            database.deleteFavorite(workId)
            database.recordTombstone("FAVORITE", workId.storageKey(), now())
            false
        } else {
            database.saveFavorite(
                FavoriteRecord(
                    sourceId = workId.sourceId,
                    id = workId.toonId,
                    title = item.title,
                    thumbUrl = item.thumbUrl,
                    href = item.href,
                    genre = item.genre,
                    updatedAt = item.updatedAt,
                    savedAt = now(),
                ),
            )
            database.deleteTombstone("FAVORITE", workId.storageKey())
            true
        }
        syncManager?.triggerDebouncedPush()
        result
    }

    suspend fun getReaderSetting(workId: WorkId): Pair<ViewMode, ReadDirection>? = withContext(Dispatchers.IO) {
        val setting = database.getReaderSetting(workId) ?: return@withContext null
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
        workId: WorkId,
        viewMode: ViewMode,
        readDirection: ReadDirection,
    ) = withContext(Dispatchers.IO) {
        val writable = WorkId.writable(workId.sourceId, workId.toonId, hitomiEnabled()) ?: return@withContext
        database.saveReaderSetting(
            ReaderSettingRecord(
                sourceId = writable.sourceId,
                toonId = writable.toonId,
                viewMode = viewMode.name,
                readDirection = readDirection.name,
                updatedAt = now(),
            ),
        )
        syncManager?.triggerDebouncedPush()
    }

    suspend fun markEpisodeRead(workId: WorkId, wrId: String, lastPage: Int = 0): Long = withContext(Dispatchers.IO) {
        val writable = WorkId.writable(workId.sourceId, workId.toonId, hitomiEnabled()) ?: return@withContext now()
        val readAt = now()
        database.markEpisodeRead(
            ReadEpisodeRecord(
                sourceId = writable.sourceId,
                toonId = writable.toonId,
                wrId = wrId,
                readAt = readAt,
                lastPage = lastPage,
            ),
        )
        syncManager?.triggerDebouncedPush()
        readAt
    }

    suspend fun saveEpisodePage(workId: WorkId, wrId: String, page: Int) = withContext(Dispatchers.IO) {
        database.updateLastPage(workId, wrId, page)
    }

    suspend fun getReadEpisode(workId: WorkId, wrId: String): ReadEpisodeRecord? = withContext(Dispatchers.IO) {
        database.getReadEpisode(workId, wrId)
    }

    suspend fun getHistory(): List<ReadHistoryRecord> = withContext(Dispatchers.IO) {
        database.getAllHistory()
    }

    suspend fun getHistory(workId: WorkId): ReadHistoryRecord? = withContext(Dispatchers.IO) {
        database.getHistory(workId)
    }

    suspend fun saveHistory(history: ReadHistoryRecord) = withContext(Dispatchers.IO) {
        val workId = WorkId.writable(history.sourceId, history.toonId, hitomiEnabled()) ?: return@withContext
        database.saveHistory(history)
        database.deleteTombstone("HISTORY", workId.storageKey())
        syncManager?.triggerDebouncedPush()
    }

    suspend fun deleteHistory(workId: WorkId) = withContext(Dispatchers.IO) {
        database.deleteHistory(workId)
        database.recordTombstone("HISTORY", workId.storageKey(), now())
        syncManager?.triggerDebouncedPush()
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        val all = database.getAllHistory()
        all.forEach {
            database.recordTombstone("HISTORY", it.workId().storageKey(), now())
        }
        database.clearAllHistory()
        syncManager?.triggerDebouncedPush()
    }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        DesktopBackupManager.createBackupJson(database)
    }

    suspend fun importBackupJson(jsonString: String): DesktopBackupResult = withContext(Dispatchers.IO) {
        DesktopBackupManager.restoreBackupJson(database, jsonString)
    }

    suspend fun getBackupStats(): DesktopBackupStats = withContext(Dispatchers.IO) {
        DesktopBackupManager.getStats(database)
    }

    private fun sourceFor(item: ToonItem) = sources.get(item.sourceId)

    private fun notifyKey(item: ToonItem): String = "${item.workId().storageKey()}|${item.updatedAt.orEmpty()}"

    private fun FavoriteRecord.toItem(): ToonItem = ToonItem(
        id = id,
        title = title,
        thumbUrl = thumbUrl,
        href = href,
        genre = genre,
        updatedAt = updatedAt,
        sourceId = sourceId,
    )
}
