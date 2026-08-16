package com.comics8.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopToonRepository(
    private val client: ToonClient,
    private val database: DesktopDatabase,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun loadListing(catalog: Catalog, page: Int): ListingPage = withContext(Dispatchers.IO) {
        if (catalog == Catalog.FAVORITE) {
            val favs = database.getAllFavorites().map { it.toItem().copy(isFavorite = true) }
            return@withContext ListingPage(
                items = applyFlags(favs),
                currentPage = 1,
                lastPage = 1,
            )
        }
        val html = client.fetch(ComicSite.listingUrl(catalog, page))
        val parsed = HtmlParsers.parseListing(html)
        if (parsed.items.isEmpty()) {
            error("목록을 읽지 못했습니다. 사이트 구조가 바뀌었을 수 있습니다.")
        }
        parsed.copy(items = applyFlags(parsed.items))
    }

    suspend fun loadEpisodes(item: ToonItem, page: Int): EpisodePage = withContext(Dispatchers.IO) {
        val html = client.fetch(ComicSite.seriesUrl(item.id, item.title, page))
        val parsed = HtmlParsers.parseEpisodes(html)
        if (parsed.items.isEmpty()) {
            error("회차 목록을 읽지 못했습니다.")
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

    suspend fun loadImages(episode: EpisodeItem): List<String> = withContext(Dispatchers.IO) {
        val html = client.fetch(episode.href)
        val parsed = HtmlParsers.parseImages(html, ComicSite.ORIGIN)
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

        return items.map { item ->
            val prev = existing[item.id]
            val history = historyMap[item.id]
            item.copy(
                isNew = total > 0 && prev == null,
                isFavorite = item.id in favoriteIds,
                readProgress = history?.let { "${it.lastReadOrder}/${it.totalEpisodes}" },
            )
        }
    }

    suspend fun isFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        database.isFavorite(id)
    }

    suspend fun toggleFavorite(item: ToonItem): Boolean = withContext(Dispatchers.IO) {
        if (database.isFavorite(item.id)) {
            database.deleteFavorite(item.id)
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
            true
        }
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
    }

    suspend fun deleteHistory(toonId: String) = withContext(Dispatchers.IO) {
        database.deleteHistory(toonId)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        database.clearAllHistory()
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
