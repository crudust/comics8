package com.comics8.desktop.data

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeCatalog
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.FavoriteListing
import com.comics8.core.model.ListingPage
import com.comics8.core.model.OfflineEpisodeRef
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.RepositoryTransforms
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.network.ToonClient
import com.comics8.core.source.ComicSource
import com.comics8.core.source.ProgressDisplay
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceAccess
import com.comics8.core.source.SourceConfig
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel

class DesktopToonRepository(
    val client: ToonClient,
    private val database: DesktopDatabase,
    val syncManager: DesktopSyncManager? = null,
    val downloadManager: DesktopDownloadManager? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sources: SourceRegistry,
    private val isSourceEnabled: (String) -> Boolean = { false },
    private val installedIds: () -> Set<String> = { sources.knownIds() },
) : AutoCloseable {
    init {
        database.isSourceEnabled = isSourceEnabled
        database.installedIds = installedIds
    }

    fun sourceRegistry(): SourceRegistry = sources

    fun source(id: String) = sources.get(id)

    fun sourceOrNull(id: String) = sources.getOrNull(id)

    fun allSources() = sources.all()

    fun installedSources(): List<ComicSource> {
        return RepositoryTransforms.installedSources(sources.all(), installedIds())
    }

    fun activeSource(stored: String?): ComicSource? =
        sources.resolveActive(stored, installedIds())

    fun applySourceConfig(config: SourceConfig) {
        sources.applyConfig(config)
    }

    fun applyPreferences(languageFor: (String) -> String?) {
        sources.applyPreferences(languageFor)
    }

    fun setSourceWriteAccess(isEnabled: (String) -> Boolean) {
        database.isSourceEnabled = isEnabled
    }

    suspend fun loadListing(tab: BrowseTab, page: Int): ListingPage = withContext(Dispatchers.IO) {
        when (tab) {
            is BrowseTab.Favorite -> loadFavoriteListing(tab.sourceId)
            is BrowseTab.Remote -> fetchRemoteListing(tab.sourceId, tab.catalog.id, page)
        }
    }

    private suspend fun loadFavoriteListing(sourceId: String): ListingPage {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val local = database.getAllFavorites().map { it.toItem().copy(isFavorite = true) }
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
        return parsed.copy(items = applyFlags(parsed.items).distinctBy { it.listingKey() })
    }

    suspend fun loadEpisodes(item: ToonItem, page: Int): EpisodePage = withContext(Dispatchers.IO) {
        val source = sourceFor(item)
        val parsed = source.loadEpisodes(item, page, client)
        if (parsed.items.isEmpty() && !source.emptyEpisodesOk) {
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
        val readStates = readMap.mapValues { (_, read) ->
            RepositoryTransforms.EpisodeReadState(read.readAt, read.lastPage)
        }
        RepositoryTransforms.enrichEpisodePage(parsed, readStates)
    }

    suspend fun loadAllEpisodes(item: ToonItem): List<EpisodeItem> =
        EpisodeCatalog.loadAllPages { page -> loadEpisodes(item, page) }

    suspend fun loadLocalEpisodes(item: ToonItem): EpisodePage = withContext(Dispatchers.IO) {
        val workId = item.workId()
        val rows = downloadManager?.getDownloadedEpisodes(workId).orEmpty()
        if (rows.isEmpty()) {
            return@withContext EpisodePage(emptyList(), 1, 1)
        }
        val readMap = database.getReadEpisodesByToon(workId).associateBy { it.wrId }
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
        EpisodePage(items = items.distinctBy { it.wrId }, currentPage = 1, lastPage = 1)
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSyncKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun close() {
        syncScope.cancel()
    }

    fun syncEpisodeCounts(
        items: List<ToonItem>,
        onUpdated: ((workId: WorkId, totalEpisodes: Int, progressText: String) -> Unit)? = null,
    ) {
        if (items.isEmpty()) return
        syncScope.launch {
            val workIds = items.map { it.workId() }
            val historyMap = database.getHistoryByToonIds(workIds).associateBy { it.workId().storageKey() }
            val existingSeen = database.getSeenByIds(workIds)

            val targets = items.filter { item ->
                val key = item.workId().storageKey()
                historyMap.containsKey(key) && activeSyncKeys.add(key)
            }

            if (targets.isEmpty()) return@launch

            val semaphore = Semaphore(3)
            val jobs = targets.map { target ->
                async {
                    val key = target.workId().storageKey()
                    try {
                        semaphore.withPermit {
                            val exactTotal = fetchTotalEpisodes(target)
                            if (exactTotal > 0) {
                                val existing = database.getHistory(target.workId())
                                if (existing != null) {
                                    val safeOrder = existing.lastReadOrder.coerceIn(0, exactTotal)
                                    val hasNew = safeOrder < exactTotal
                                    if (existing.totalEpisodes != exactTotal || existing.hasNew != hasNew) {
                                        val updated = existing.copy(
                                            totalEpisodes = exactTotal,
                                            lastReadOrder = safeOrder,
                                            hasNew = hasNew,
                                        )
                                        saveHistory(updated)
                                        val readCounts = countReadEpisodesNow(listOf(target.workId()))
                                        val readCount = readCounts[key] ?: 0
                                        val progressText = formatReadProgress(
                                            target.sourceId,
                                            updated.lastReadOrder,
                                            updated.totalEpisodes,
                                            readCount,
                                        )
                                        onUpdated?.invoke(target.workId(), exactTotal, progressText)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        activeSyncKeys.remove(key)
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    suspend fun fetchTotalEpisodes(item: ToonItem): Int = withContext(Dispatchers.IO) {
        val firstPage = loadEpisodes(item, 1)
        val lastP = firstPage.lastPage.coerceAtLeast(1)
        if (firstPage.items.isEmpty() || lastP <= 1) {
            return@withContext RepositoryTransforms.estimateTotalEpisodes(firstPage, 1)
        }
        val pageSize = sourceFor(item).episodePageSize
        val lastPageItemCount = try {
            loadEpisodes(item, lastP).items.size
        } catch (_: Exception) {
            null
        }
        RepositoryTransforms.estimateTotalEpisodes(firstPage, pageSize, lastPageItemCount)
    }

    suspend fun loadImages(episode: EpisodeItem, workId: WorkId? = null): List<String> = withContext(Dispatchers.IO) {
        if (workId != null && downloadManager != null) {
            val localImages = downloadManager.getLocalEpisodeImages(workId, episode.wrId)
            if (!localImages.isNullOrEmpty()) {
                return@withContext localImages
            }
        }
        val resolved = workId ?: error("sourceId required")
        val sourceId = resolved.sourceId.takeIf { it.isNotBlank() } ?: error("sourceId required")
        val item = ToonItem(
            id = resolved.toonId,
            title = "",
            thumbUrl = "",
            href = episode.href,
            sourceId = sourceId,
        )
        val parsed = sourceFor(item).resolveImages(episode, item, client)
        if (parsed.isEmpty()) {
            error("만화 이미지를 불러오지 못했습니다.")
        }
        parsed
    }

    suspend fun suggest(query: String, sourceId: String): List<com.comics8.core.source.SearchSuggestion> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            sources.get(sourceId).suggest(SearchQuery(q, language = sources.get(sourceId).searchLanguage()), client)
        }

    suspend fun search(query: String, sourceId: String): List<ToonItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val source = sources.get(sourceId)
            val items = source.search(SearchQuery(q, language = source.searchLanguage()), client)
            applyFlags(items).distinctBy { it.listingKey() }
        }

    suspend fun markSeen(items: List<ToonItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val nowMs = now()
        val existing = database.getSeenByIds(items.map { it.workId() })
        val rows = items.mapNotNull { item ->
            val workId = writableId(item.sourceId, item.id) ?: return@mapNotNull null
            val prev = existing[workId.storageKey()]
            SeenRecord(
                sourceId = workId.sourceId,
                id = workId.toonId,
                title = item.title,
                updatedAt = item.updatedAt,
                firstSeenAt = prev?.firstSeenAt ?: nowMs,
                lastSeenAt = nowMs,
                notifiedKey = prev?.notifiedKey ?: notifyKey(item),
            )
        }
        database.saveAllSeen(rows)
    }

    suspend fun refreshProgress(items: List<ToonItem>): List<ToonItem> = withContext(Dispatchers.IO) {
        applyFlags(items)
    }

    private suspend fun applyFlags(items: List<ToonItem>): List<ToonItem> {
        if (items.isEmpty()) return items
        val workIds = items.map { it.workId() }
        val favoriteKeys = database.getFavoritesByToonIds(workIds).map { it.workId().storageKey() }.toSet()
        val total = database.getSeenCount()
        val existing = if (total == 0) emptyMap() else database.getSeenByIds(workIds)
        val historyMap = database.getHistoryByToonIds(workIds).associateBy { it.workId().storageKey() }
        val readCounts = countReadEpisodesNow(workIds.filter { historyMap.containsKey(it.storageKey()) })

        for (item in items) {
            val uDate = item.updatedAt
            if (item.workId().storageKey() in favoriteKeys && !uDate.isNullOrBlank()) {
                try {
                    database.updateFavoriteUpdatedAt(item.workId(), uDate)
                } catch (_: Exception) {}
            }
        }

        return RepositoryTransforms.applyListingFlags(
            items = items,
            seenCount = total,
            favoriteKeys = favoriteKeys,
            seenByKey = existing,
            historyByKey = historyMap,
            readCountsByKey = readCounts,
            seenUpdatedAt = { it.updatedAt },
        ) { item, history, readCount ->
            formatReadProgress(item.sourceId, history.lastReadOrder, history.totalEpisodes, readCount)
        }
    }

    suspend fun isFavorite(workId: WorkId): Boolean = withContext(Dispatchers.IO) {
        database.isFavorite(workId)
    }

    suspend fun toggleFavorite(item: ToonItem): Boolean? = withContext(Dispatchers.IO) {
        val workId = writableId(item.sourceId, item.id) ?: return@withContext null
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

    suspend fun getReaderSetting(workId: WorkId): RepositoryTransforms.ReaderSettingValues? = withContext(Dispatchers.IO) {
        val setting = database.getReaderSetting(workId) ?: return@withContext null
        RepositoryTransforms.parseReaderSetting(
            setting.viewMode,
            setting.readDirection,
            setting.splitMode,
        )
    }

    suspend fun saveReaderSetting(
        workId: WorkId,
        viewMode: ViewMode,
        readDirection: ReadDirection,
        splitMode: SplitMode,
    ) = withContext(Dispatchers.IO) {
        val writable = writableId(workId) ?: return@withContext
        database.saveReaderSetting(
            ReaderSettingRecord(
                sourceId = writable.sourceId,
                toonId = writable.toonId,
                viewMode = viewMode.name,
                readDirection = readDirection.name,
                splitMode = splitMode.name,
                updatedAt = now(),
            ),
        )
    }

    suspend fun markEpisodeRead(workId: WorkId, wrId: String, lastPage: Int = 0): Long = withContext(Dispatchers.IO) {
        val writable = writableId(workId) ?: return@withContext now()
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

    fun formatReadProgress(sourceId: String, lastReadOrder: Int, totalEpisodes: Int, readCount: Int): String {
        val mode = DesktopSourcePrefs.progressDisplayMode(sourceId)
        return sources.formatReadProgress(sourceId, lastReadOrder, totalEpisodes, readCount, mode)
    }

    suspend fun countReadEpisodes(workId: WorkId): Int = withContext(Dispatchers.IO) {
        database.countReadEpisodes(workId)
    }

    suspend fun countReadEpisodes(workIds: List<WorkId>): Map<String, Int> = withContext(Dispatchers.IO) {
        countReadEpisodesNow(workIds)
    }

    suspend fun getHistory(sourceId: String): List<ReadHistoryRecord> = withContext(Dispatchers.IO) {
        database.getHistoryBySource(sourceId)
    }

    suspend fun getHistory(workId: WorkId): ReadHistoryRecord? = withContext(Dispatchers.IO) {
        database.getHistory(workId)
    }

    suspend fun saveHistory(history: ReadHistoryRecord) = withContext(Dispatchers.IO) {
        val workId = writableId(history.sourceId, history.toonId) ?: return@withContext
        database.saveHistory(history)
        database.deleteTombstone("HISTORY", workId.storageKey())
        syncManager?.triggerDebouncedPush()
    }

    suspend fun deleteHistory(workId: WorkId) = withContext(Dispatchers.IO) {
        database.deleteHistory(workId)
        database.recordTombstone("HISTORY", workId.storageKey(), now())
        syncManager?.triggerDebouncedPush()
    }

    suspend fun clearHistory(sourceId: String) = withContext(Dispatchers.IO) {
        val rows = database.getHistoryBySource(sourceId)
        rows.forEach {
            database.recordTombstone("HISTORY", it.workId().storageKey(), now())
        }
        database.clearHistoryBySource(sourceId)
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

    private fun countReadEpisodesNow(workIds: List<WorkId>): Map<String, Int> {
        val needed = workIds.filter {
            sources.progressDisplayMode(it.sourceId, DesktopSourcePrefs).requiresReadCount
        }
        if (needed.isEmpty()) return emptyMap()
        return database.countReadEpisodes(needed)
    }

    private fun sourceFor(item: ToonItem) = sources.get(item.sourceId)

    private fun writableId(workId: WorkId): WorkId? =
        SourceAccess.writable(workId, isSourceEnabled, installedIds())

    private fun writableId(sourceId: String, toonId: String): WorkId? =
        SourceAccess.writable(sourceId, toonId, isSourceEnabled, installedIds())

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
