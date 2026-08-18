package com.comics8.monitor.data

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeCatalog
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.FavoriteListing
import com.comics8.core.model.ListingPage
import com.comics8.core.model.OfflineEpisodeRef
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ToonItem
import com.comics8.core.model.UpdateDates
import com.comics8.core.model.ViewMode
import com.comics8.core.network.ToonClient
import com.comics8.core.source.LatestUpdateSelection
import com.comics8.core.source.NotificationMode
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceAccess
import com.comics8.core.source.SourceConfig
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
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
import com.comics8.monitor.data.local.delete
import com.comics8.monitor.data.local.exists
import com.comics8.monitor.data.local.get
import com.comics8.monitor.data.local.getReadEpisode
import com.comics8.monitor.data.local.getReadEpisodes
import com.comics8.monitor.data.local.getSetting
import com.comics8.monitor.data.local.updateLastPage
import com.comics8.monitor.data.local.updateUpdatedAt
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
    private val isSourceEnabled: (String) -> Boolean = { false },
) {
    fun source(id: String) = sources.get(id)

    fun sourceOrDefault(id: String) = sources.getOrNull(id) ?: sources.default

    fun allSources() = sources.all()

    fun applySourceConfig(config: SourceConfig) {
        sources.applyConfig(config)
    }

    fun applyPreferences(languageFor: (String) -> String?) {
        sources.applyPreferences(languageFor)
    }

    suspend fun loadListing(tab: BrowseTab, page: Int): ListingPage = withContext(Dispatchers.IO) {
        when (tab) {
            is BrowseTab.Favorite -> loadFavoriteListing(tab.sourceId)
            is BrowseTab.Remote -> fetchRemoteListing(tab.sourceId, tab.catalog.id, page)
        }
    }

    private suspend fun loadFavoriteListing(sourceId: String): ListingPage {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val local = favoriteDao.all().map { it.toItem().copy(isFavorite = true) }
            .filter { it.sourceId.ifBlank { WorkId.DEFAULT_SOURCE } == sid }
        val items = FavoriteListing.assembleBySource(local) { sourceId, page ->
            val source = sources.getOrNull(sourceId)
            if (source == null || !source.favoriteUsesLatestListing) {
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
        val source = sources.get(sourceId)
        val parsed = source.loadListing(catalogId, page, client)
        if (parsed.items.isEmpty() && !source.emptyListingOk) {
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
        touchFavoriteUpdatedAt(workId, firstDate)
        val readMap = readEpisodeDao.getReadEpisodes(workId).associateBy { it.wrId }
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

    suspend fun loadAllEpisodes(item: ToonItem): List<EpisodeItem> =
        EpisodeCatalog.loadAllPages { page -> loadEpisodes(item, page) }

    suspend fun loadLocalEpisodes(item: ToonItem): EpisodePage = withContext(Dispatchers.IO) {
        val workId = item.workId()
        val rows = downloadManager?.getDownloadedEpisodes(workId).orEmpty()
        if (rows.isEmpty()) {
            return@withContext EpisodePage(emptyList(), 1, 1)
        }
        val readMap = readEpisodeDao.getReadEpisodes(workId).associateBy { it.wrId }
        val items = EpisodeCatalog.fromOffline(
            rows = rows.map { row ->
                OfflineEpisodeRef(
                    wrId = row.wrId,
                    title = row.episodeTitle,
                    href = row.episodeHref,
                    thumbUrl = row.toonThumbUrl.ifBlank { null },
                )
            },
            readAtByWrId = readMap.mapValues { it.value.readAt },
            lastPageByWrId = readMap.mapValues { it.value.lastPage },
        )
        EpisodePage(items = items, currentPage = 1, lastPage = 1)
    }

    suspend fun fetchTotalEpisodes(item: ToonItem): Int = withContext(Dispatchers.IO) {
        val firstPage = loadEpisodes(item, 1)
        if (firstPage.items.isEmpty()) return@withContext 0
        val lastP = firstPage.lastPage.coerceAtLeast(1)
        if (lastP <= 1) {
            return@withContext firstPage.items.size
        }
        val pageSize = sourceFor(item).episodePageSize
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

    suspend fun suggest(query: String, sourceId: String = WorkId.DEFAULT_SOURCE): List<com.comics8.core.source.SearchSuggestion> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            sources.get(sourceId).suggest(SearchQuery(q, language = sources.get(sourceId).searchLanguage()), client)
        }

    suspend fun search(query: String, sourceId: String = WorkId.DEFAULT_SOURCE): List<ToonItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val source = sources.get(sourceId)
            val items = source.search(SearchQuery(q, language = source.searchLanguage()), client)
            applyFlags(items)
        }

    suspend fun markSeen(items: List<ToonItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val nowMs = now()
        val existing = seenByWorkIds(items.map { it.workId() })
        val rows = items.mapNotNull { item ->
            val workId = writableWorkId(item) ?: return@mapNotNull null
            val prev = existing[workId.storageKey()]
            SeenToon(
                sourceId = workId.sourceId,
                id = workId.toonId,
                title = item.title,
                updatedAt = item.updatedAt,
                firstSeenAt = prev?.firstSeenAt ?: nowMs,
                lastSeenAt = nowMs,
                notifiedKey = prev?.notifiedKey.orEmpty(),
            )
        }
        if (rows.isNotEmpty()) seenDao.upsertAll(rows)
    }

    suspend fun checkLatestUpdates(): List<ToonItem> {
        val favorites = favoriteDao.all()
        if (favorites.isEmpty()) return emptyList()

        val unseen = mutableListOf<ToonItem>()
        val updatedSeen = mutableListOf<SeenToon>()
        val nowMs = now()

        for ((sourceId, rows) in favorites.groupBy { it.sourceId.ifBlank { WorkId.DEFAULT_SOURCE } }) {
            val source = sources.getOrNull(sourceId) ?: continue
            val candidates = try {
                val latest = if (source.notificationMode == NotificationMode.LATEST_INTERSECTION) {
                    fetchRemoteListing(sourceId, "LATEST", 1).items
                } else {
                    null
                }
                LatestUpdateSelection.candidates(
                    source = source,
                    favorites = rows.map { it.toItem() },
                    latestItems = latest,
                    sourceEnabled = sourceId == WorkId.DEFAULT_SOURCE || isSourceEnabled(sourceId),
                )
            } catch (_: Exception) {
                emptyList()
            }
            if (candidates.isEmpty()) continue
            val existing = seenByWorkIds(candidates.map { it.workId() })
            for (item in candidates) {
                try {
                    val workId = writableWorkId(item) ?: continue
                    val epPage = loadEpisodes(item, 1)
                    val latestEp = epPage.items.firstOrNull() ?: continue
                    val latestWrId = latestEp.wrId
                    val notifyKey = "${workId.storageKey()}|$latestWrId"

                    if (existing[workId.storageKey()]?.notifiedKey == notifyKey) {
                        continue
                    }

                    val isRead = readEpisodeDao.getReadEpisode(workId, latestWrId) != null ||
                        readHistoryDao.get(workId)?.lastWrId == latestWrId

                    val prev = existing[workId.storageKey()]
                    updatedSeen.add(
                        SeenToon(
                            sourceId = workId.sourceId,
                            id = workId.toonId,
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
        }

        if (updatedSeen.isNotEmpty()) {
            seenDao.upsertAll(updatedSeen)
        }

        return unseen
    }

    private suspend fun applyFlags(items: List<ToonItem>): List<ToonItem> {
        if (items.isEmpty()) return items
        val favoriteKeys = favoriteDao.all().map { it.workId().storageKey() }.toSet()
        val total = seenDao.count()
        val existing = if (total == 0) emptyMap() else seenByWorkIds(items.map { it.workId() })
        val historyMap = try {
            readHistoryDao.all().associateBy { it.workId().storageKey() }
        } catch (_: Exception) {
            emptyMap()
        }
        for (item in items) {
            if (item.workId().storageKey() in favoriteKeys) {
                touchFavoriteUpdatedAt(item.workId(), item.updatedAt)
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
        favoriteDao.exists(workId)
    }

    suspend fun toggleFavorite(item: ToonItem): Boolean? = withContext(Dispatchers.IO) {
        val workId = writableWorkId(item) ?: return@withContext null
        val exists = favoriteDao.exists(workId)
        val result = if (exists) {
            favoriteDao.delete(workId)
            database?.tombstoneDao()?.insert(
                com.comics8.monitor.data.local.Tombstone("FAVORITE", workId.storageKey(), now()),
            )
            false
        } else {
            favoriteDao.upsert(
                FavoriteToon(
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
            database?.tombstoneDao()?.delete("FAVORITE", workId.storageKey())
            true
        }
        syncManager?.triggerDebouncedPush()
        result
    }

    suspend fun getReaderSetting(workId: WorkId): Pair<ViewMode, ReadDirection>? = withContext(Dispatchers.IO) {
        val setting = readerSettingDao.getSetting(workId) ?: return@withContext null
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
        val writable = writableId(workId) ?: return@withContext
        readerSettingDao.saveSetting(
            ReaderSetting(
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
        val writable = writableId(workId) ?: return@withContext now()
        val readAt = now()
        readEpisodeDao.markRead(
            ReadEpisode(
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
        readEpisodeDao.updateLastPage(workId, wrId, page)
    }

    suspend fun getReadEpisode(workId: WorkId, wrId: String): ReadEpisode? = withContext(Dispatchers.IO) {
        readEpisodeDao.getReadEpisode(workId, wrId)
    }

    suspend fun getHistory(sourceId: String): List<ReadHistory> = withContext(Dispatchers.IO) {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        readHistoryDao.bySource(sid)
    }

    suspend fun getHistory(workId: WorkId): ReadHistory? = withContext(Dispatchers.IO) {
        readHistoryDao.get(workId)
    }

    suspend fun saveHistory(history: ReadHistory) = withContext(Dispatchers.IO) {
        val workId = writableId(history.sourceId, history.toonId) ?: return@withContext
        val stored = if (history.sourceId == workId.sourceId) history else history.copy(sourceId = workId.sourceId)
        readHistoryDao.upsert(stored)
        database?.tombstoneDao()?.delete("HISTORY", workId.storageKey())
        val nowMs = now()
        val existing = seenByWorkIds(listOf(workId))[workId.storageKey()]
        seenDao.upsertAll(
            listOf(
                SeenToon(
                    sourceId = workId.sourceId,
                    id = workId.toonId,
                    title = stored.toonTitle,
                    updatedAt = existing?.updatedAt,
                    firstSeenAt = existing?.firstSeenAt ?: nowMs,
                    lastSeenAt = nowMs,
                    notifiedKey = "${workId.storageKey()}|${stored.lastWrId}",
                )
            )
        )
        syncManager?.triggerDebouncedPush()
    }

    suspend fun deleteHistory(workId: WorkId) = withContext(Dispatchers.IO) {
        readHistoryDao.delete(workId)
        database?.tombstoneDao()?.insert(
            com.comics8.monitor.data.local.Tombstone("HISTORY", workId.storageKey(), now()),
        )
        syncManager?.triggerDebouncedPush()
    }

    suspend fun clearHistory(sourceId: String) = withContext(Dispatchers.IO) {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val rows = readHistoryDao.bySource(sid)
        rows.forEach {
            database?.tombstoneDao()?.insert(
                com.comics8.monitor.data.local.Tombstone("HISTORY", it.workId().storageKey(), now()),
            )
        }
        readHistoryDao.clearBySource(sid)
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

    private suspend fun touchFavoriteUpdatedAt(workId: WorkId, updatedAt: String?) {
        if (updatedAt.isNullOrBlank()) return
        try {
            val current = favoriteDao.get(workId) ?: return
            if (!UpdateDates.shouldReplace(current.updatedAt, updatedAt)) return
            val stored = UpdateDates.toListingDate(updatedAt) ?: updatedAt
            favoriteDao.updateUpdatedAt(workId, stored)
        } catch (_: Exception) {
        }
    }

    private suspend fun seenByWorkIds(ids: List<WorkId>): Map<String, SeenToon> {
        if (ids.isEmpty()) return emptyMap()
        val wanted = ids.map { it.storageKey() }.toSet()
        return seenDao.byToonIds(ids.map { it.toonId }.distinct())
            .filter { it.workId().storageKey() in wanted }
            .associateBy { it.workId().storageKey() }
    }

    private fun sourceFor(item: ToonItem) = sources.get(item.sourceId)

    private fun writableWorkId(item: ToonItem): WorkId? =
        writableId(item.sourceId, item.id)

    private fun writableId(workId: WorkId): WorkId? =
        SourceAccess.writable(workId, isSourceEnabled)

    private fun writableId(sourceId: String, toonId: String): WorkId? =
        SourceAccess.writable(sourceId, toonId, isSourceEnabled)

    private fun FavoriteToon.toItem(): ToonItem = ToonItem(
        id = id,
        title = title,
        thumbUrl = thumbUrl,
        href = href,
        genre = genre,
        updatedAt = updatedAt,
        sourceId = sourceId,
    )
}
