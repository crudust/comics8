package com.comics8.desktop.data

import com.comics8.core.model.FavoriteListing
import com.comics8.core.network.ToonClient
import com.comics8.core.parser.ComicSite
import com.comics8.core.parser.HtmlParsers
import com.comics8.core.parser.SearchParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopToonRepository(
    private val client: ToonClient,
    private val database: DesktopDatabase,
    val syncManager: DesktopSyncManager? = null,
    val downloadManager: DesktopDownloadManager? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun loadListing(catalog: Catalog, page: Int): ListingPage = withContext(Dispatchers.IO) {
        if (catalog == Catalog.FAVORITE) {
            return@withContext loadFavoriteListing()
        }
        fetchRemoteListing(catalog, page)
    }

    private suspend fun loadFavoriteListing(): ListingPage {
        val local = database.getAllFavorites().map { it.toItem().copy(isFavorite = true) }
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
        val html = client.fetch(ComicSite.listingUrl(catalog, page))
        val parsed = HtmlParsers.parseListing(html)
        if (parsed.items.isEmpty()) {
            error("목록을 읽지 못했습니다. 사이트 구조가 바뀌었을 수 있습니다.")
        }
        return parsed.copy(items = applyFlags(parsed.items))
    }

    suspend fun loadEpisodes(item: ToonItem, page: Int): EpisodePage = withContext(Dispatchers.IO) {
        val html = client.fetch(ComicSite.seriesUrl(item.id, item.title, page))
        val parsed = HtmlParsers.parseEpisodes(html)
        if (parsed.items.isEmpty()) {
            error("회차 목록을 읽지 못했습니다.")
        }
        val firstDate = parsed.items.firstOrNull()?.date
        if (!firstDate.isNullOrBlank()) {
            try {
                database.updateFavoriteUpdatedAt(item.id, firstDate)
            } catch (_: Exception) {
            }
        }
        val readMap = database.getReadEpisodesByToon(item.id).associateBy { it.wrId }
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
        val parsed = client.fetchImages(episode.href)
        if (parsed.isEmpty()) {
            error("만화 이미지를 불러오지 못했습니다.")
        }
        parsed
    }

    suspend fun search(query: String): List<ToonItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val json = client.fetch(ComicSite.searchUrl(q))
        val items = SearchParser.parse(json)
        applyFlags(items)
    }

    suspend fun markSeen(items: List<ToonItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val nowMs = now()
        val existing = database.getSeenByIds(items.map { it.id })
        val rows = items.map { item ->
            val prev = existing[item.id]
            SeenRecord(
                id = item.id,
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
        val total = database.getSeenCount()
        val existing = if (total == 0) emptyMap() else database.getSeenByIds(items.map { it.id })
        val historyMap = database.getAllHistory().associateBy { it.toonId }

        for (item in items) {
            val uDate = item.updatedAt
            if (item.id in favoriteIds && !uDate.isNullOrBlank()) {
                try {
                    database.updateFavoriteUpdatedAt(item.id, uDate)
                } catch (_: Exception) {}
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
        database.isFavorite(id)
    }

    suspend fun toggleFavorite(item: ToonItem): Boolean = withContext(Dispatchers.IO) {
        val exists = database.isFavorite(item.id)
        val result = if (exists) {
            database.deleteFavorite(item.id)
            database.recordTombstone("FAVORITE", item.id, now())
            false
        } else {
            database.saveFavorite(
                FavoriteRecord(
                    id = item.id,
                    title = item.title,
                    thumbUrl = item.thumbUrl,
                    href = item.href,
                    genre = item.genre,
                    updatedAt = item.updatedAt,
                    savedAt = now(),
                ),
            )
            database.deleteTombstone("FAVORITE", item.id)
            true
        }
        syncManager?.triggerDebouncedPush()
        result
    }

    suspend fun getReaderSetting(toonId: String): Pair<ViewMode, ReadDirection>? = withContext(Dispatchers.IO) {
        val setting = database.getReaderSetting(toonId) ?: return@withContext null
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
        database.saveReaderSetting(
            ReaderSettingRecord(
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
        database.markEpisodeRead(
            ReadEpisodeRecord(
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
        database.updateLastPage(toonId, wrId, page)
    }

    suspend fun getReadEpisode(toonId: String, wrId: String): ReadEpisodeRecord? = withContext(Dispatchers.IO) {
        database.getReadEpisode(toonId, wrId)
    }

    suspend fun getHistory(): List<ReadHistoryRecord> = withContext(Dispatchers.IO) {
        database.getAllHistory()
    }

    suspend fun saveHistory(history: ReadHistoryRecord) = withContext(Dispatchers.IO) {
        database.saveHistory(history)
        database.deleteTombstone("HISTORY", history.toonId)
        syncManager?.triggerDebouncedPush()
    }

    suspend fun deleteHistory(toonId: String) = withContext(Dispatchers.IO) {
        database.deleteHistory(toonId)
        database.recordTombstone("HISTORY", toonId, now())
        syncManager?.triggerDebouncedPush()
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        val all = database.getAllHistory()
        all.forEach {
            database.recordTombstone("HISTORY", it.toonId, now())
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

    private fun notifyKey(item: ToonItem): String = "${item.id}|${item.updatedAt.orEmpty()}"

    private fun FavoriteRecord.toItem(): ToonItem = ToonItem(
        id = id,
        title = title,
        thumbUrl = thumbUrl,
        href = href,
        genre = genre,
        updatedAt = updatedAt,
    )
}
