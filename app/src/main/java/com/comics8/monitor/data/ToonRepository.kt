package com.comics8.monitor.data

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
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun loadListing(catalog: Catalog, page: Int): ListingPage = withContext(Dispatchers.IO) {
        if (catalog == Catalog.FAVORITE) {
            val favs = favoriteDao.all().map { it.toItem().copy(isFavorite = true) }
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
        val existing = seenDao.byIds(items.map { it.id }).associateBy { it.id }
        val rows = items.map { item ->
            val prev = existing[item.id]
            SeenToon(
                id = item.id,
                title = item.title,
                updatedAt = item.updatedAt,
                firstSeenAt = prev?.firstSeenAt ?: nowMs,
                lastSeenAt = nowMs,
                notifiedKey = notifyKey(item),
            )
        }
        seenDao.upsertAll(rows)
    }

    suspend fun checkLatestUpdates(): List<ToonItem> {
        val favoriteIds = favoriteDao.ids().toSet()
        if (favoriteIds.isEmpty()) return emptyList()

        val page = loadListing(Catalog.LATEST, 1)
        val favoriteItems = page.items.filter { it.id in favoriteIds }
        if (favoriteItems.isEmpty()) {
            markSeen(page.items)
            return emptyList()
        }
        val existing = seenDao.byIds(favoriteItems.map { it.id }).associateBy { it.id }
        val unseen = favoriteItems.filter { item ->
            existing[item.id]?.notifiedKey != notifyKey(item)
        }
        unseen.forEach { item ->
            try {
                readHistoryDao.incrementTotal(item.id, 1)
            } catch (_: Exception) {
            }
        }
        markSeen(page.items)
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
        favoriteDao.exists(id)
    }

    suspend fun toggleFavorite(item: ToonItem): Boolean = withContext(Dispatchers.IO) {
        val exists = favoriteDao.exists(item.id)
        val result = if (exists) {
            favoriteDao.delete(item.id)
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
        syncManager?.triggerDebouncedPush()
    }

    suspend fun getReadEpisode(toonId: String, wrId: String): ReadEpisode? = withContext(Dispatchers.IO) {
        readEpisodeDao.getReadEpisode(toonId, wrId)
    }

    suspend fun getHistory(): List<ReadHistory> = withContext(Dispatchers.IO) {
        readHistoryDao.all()
    }

    suspend fun saveHistory(history: ReadHistory) = withContext(Dispatchers.IO) {
        readHistoryDao.upsert(history)
        syncManager?.triggerDebouncedPush()
    }

    suspend fun deleteHistory(toonId: String) = withContext(Dispatchers.IO) {
        readHistoryDao.delete(toonId)
        syncManager?.triggerDebouncedPush()
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
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
