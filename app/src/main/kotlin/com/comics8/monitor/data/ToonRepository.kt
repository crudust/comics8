package com.comics8.monitor.data

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeCatalog
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.FavoriteListing
import com.comics8.core.model.ListingPage
import com.comics8.core.model.OfflineEpisodeRef
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ToonItem
import com.comics8.core.model.UpdateDates
import com.comics8.core.model.ViewMode
import com.comics8.core.network.ToonClient
import com.comics8.core.source.ComicSource
import com.comics8.core.source.LatestUpdateSelection
import com.comics8.core.source.NotificationMode
import com.comics8.core.source.ProgressDisplay
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceAccess
import com.comics8.core.source.SourceConfig
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.core.source.resolveSourceType
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
import com.comics8.monitor.data.local.countByToon
import com.comics8.monitor.data.local.delete
import com.comics8.monitor.data.local.exists
import com.comics8.monitor.data.local.get
import com.comics8.monitor.data.local.getReadEpisode
import com.comics8.monitor.data.local.getReadEpisodes
import com.comics8.monitor.data.local.getSetting
import com.comics8.monitor.data.local.updateLastPage
import com.comics8.monitor.data.local.updateUpdatedAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class ToonRepository(
    private val client: ToonClient,
    private val seenDao: SeenDao,
    private val favoriteDao: FavoriteDao,
    private val readerSettingDao: ReaderSettingDao,
    private val readEpisodeDao: ReadEpisodeDao,
    private val readHistoryDao: ReadHistoryDao,
    private val database: AppDatabase? = null,
    val sourceSettings: com.comics8.core.source.SourceSettings? = null,
    val syncManager: SyncManager? = null,
    val downloadManager: DownloadManager? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sources: SourceRegistry,
    private val isSourceEnabled: (String) -> Boolean = { false },
    private val installedIds: () -> Set<String> = { sources.knownIds() },
) {
    fun sourceRegistry(): SourceRegistry = sources

    fun source(id: String) = sources.get(id)

    fun sourceOrNull(id: String): ComicSource? = sources.getOrNull(id)

    fun allSources() = sources.all()

    fun installedSources(): List<ComicSource> {
        val ids = installedIds()
        val all = sources.all()
        val byId = all.associateBy { it.id }

        val local = byId[WorkId.LOCAL_SOURCE]

        val orderedStorage = ids.mapNotNull { id ->
            if (id != WorkId.LOCAL_SOURCE) {
                byId[id]?.takeIf { it.resolveSourceType().isStorage }
            } else null
        }
        val remainingStorage = all.filter {
            it.id in ids && it.resolveSourceType().isStorage && it.id != WorkId.LOCAL_SOURCE && it !in orderedStorage
        }

        val orderedOnline = ids.mapNotNull { id ->
            byId[id]?.takeIf { !it.resolveSourceType().isStorage }
        }
        val remainingOnline = all.filter {
            it.id in ids && !it.resolveSourceType().isStorage && it !in orderedOnline
        }

        return buildList {
            if (local != null && local.id in ids) add(local)
            addAll(orderedStorage)
            addAll(remainingStorage)
            addAll(orderedOnline)
            addAll(remainingOnline)
        }
    }

    fun activeSource(stored: String?): ComicSource? = sources.resolveActive(stored, installedIds())

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
        parsed.copy(items = enriched.distinctBy { it.wrId })
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
        EpisodePage(items = items.distinctBy { it.wrId }, currentPage = 1, lastPage = 1)
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSyncKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun syncEpisodeCounts(
        items: List<ToonItem>,
        onUpdated: ((workId: WorkId, totalEpisodes: Int, progressText: String) -> Unit)? = null,
    ) {
        if (items.isEmpty()) return
        syncScope.launch {
            val workIds = items.map { it.workId() }
            val ids = items.map { it.id }.distinct()
            val wanted = workIds.map { it.storageKey() }.toSet()
            val historyMap = try {
                if (ids.isEmpty()) emptyMap() else {
                    readHistoryDao.byToonIds(ids)
                        .filter { it.workId().storageKey() in wanted }
                        .associateBy { it.workId().storageKey() }
                }
            } catch (_: Exception) {
                emptyMap()
            }
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
                                val existing = readHistoryDao.get(target.workId())
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

        var historyChanged = false

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
                    sourceEnabled = isSourceEnabled(sourceId),
                )
            } catch (_: Exception) {
                emptyList()
            }
            if (candidates.isEmpty()) continue
            val existing = seenByWorkIds(candidates.map { it.workId() })
            val semaphore = Semaphore(3)
            val outcomes = coroutineScope {
                candidates.map { item ->
                    async {
                        semaphore.withPermit {
                            try {
                                val workId = writableWorkId(item) ?: return@withPermit null
                                val epPage = loadEpisodes(item, 1)
                                val latestEp = epPage.items.firstOrNull() ?: return@withPermit null
                                val latestWrId = latestEp.wrId
                                val notifyKey = "${workId.storageKey()}|$latestWrId"

                                if (existing[workId.storageKey()]?.notifiedKey == notifyKey) {
                                    return@withPermit null
                                }

                                val isRead = readEpisodeDao.getReadEpisode(workId, latestWrId) != null ||
                                    readHistoryDao.get(workId)?.lastWrId == latestWrId

                                val lastP = epPage.lastPage.coerceAtLeast(1)
                                val exactTotal = if (lastP <= 1) {
                                    epPage.items.size
                                } else {
                                    val pageSize = source.episodePageSize
                                    try {
                                        val lastPageRes = loadEpisodes(item, lastP)
                                        (lastP - 1) * pageSize + lastPageRes.items.size
                                    } catch (_: Exception) {
                                        (lastP - 1) * pageSize + epPage.items.size
                                    }
                                }

                                var changed = false
                                if (exactTotal > 0) {
                                    val existingHist = readHistoryDao.get(workId)
                                    if (existingHist != null) {
                                        val safeOrder = existingHist.lastReadOrder.coerceIn(0, exactTotal)
                                        val hasNew = safeOrder < exactTotal
                                        if (existingHist.totalEpisodes != exactTotal || existingHist.hasNew != hasNew) {
                                            readHistoryDao.upsert(
                                                existingHist.copy(
                                                    totalEpisodes = exactTotal,
                                                    lastReadOrder = safeOrder,
                                                    hasNew = hasNew,
                                                )
                                            )
                                            changed = true
                                        }
                                    }
                                }

                                val latestDate = latestEp.date ?: item.updatedAt
                                val prev = existing[workId.storageKey()]
                                val seen = SeenToon(
                                    sourceId = workId.sourceId,
                                    id = workId.toonId,
                                    title = item.title,
                                    updatedAt = latestDate,
                                    firstSeenAt = prev?.firstSeenAt ?: nowMs,
                                    lastSeenAt = nowMs,
                                    notifiedKey = notifyKey,
                                )
                                val unseenItem = if (!isRead) {
                                    item.copy(
                                        href = latestEp.href,
                                        updatedAt = latestDate,
                                    )
                                } else {
                                    null
                                }
                                Triple(seen, unseenItem, changed)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll()
            }
            for (outcome in outcomes) {
                if (outcome == null) continue
                updatedSeen.add(outcome.first)
                outcome.second?.let { unseen.add(it) }
                if (outcome.third) {
                    historyChanged = true
                }
            }
        }

        if (updatedSeen.isNotEmpty()) {
            seenDao.upsertAll(updatedSeen)
        }
        if (historyChanged) {
            syncManager?.triggerDebouncedPush()
        }

        return unseen
    }

    private suspend fun applyFlags(items: List<ToonItem>): List<ToonItem> {
        if (items.isEmpty()) return items
        val ids = items.map { it.id }.distinct()
        val wanted = items.map { it.workId().storageKey() }.toSet()
        val favoriteKeys = if (ids.isEmpty()) {
            emptySet()
        } else {
            favoriteDao.byToonIds(ids)
                .filter { it.workId().storageKey() in wanted }
                .map { it.workId().storageKey() }
                .toSet()
        }
        val total = seenDao.count()
        val existing = if (total == 0) emptyMap() else seenByWorkIds(items.map { it.workId() })
        val historyMap = try {
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                readHistoryDao.byToonIds(ids)
                    .filter { it.workId().storageKey() in wanted }
                    .associateBy { it.workId().storageKey() }
            }
        } catch (_: Exception) {
            emptyMap()
        }
        val readCounts = countReadEpisodesNow(
            items.map { it.workId() }.filter { historyMap.containsKey(it.storageKey()) },
        )
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
                readProgress = history?.let { h ->
                    formatReadProgress(item.sourceId, h.lastReadOrder, h.totalEpisodes, readCounts[key] ?: 0)
                },
            )
        }
    }

    suspend fun refreshProgress(items: List<ToonItem>): List<ToonItem> = withContext(Dispatchers.IO) {
        applyFlags(items)
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

    data class AppReaderSetting(
        val viewMode: ViewMode,
        val readDirection: ReadDirection,
        val splitMode: SplitMode,
    )

    suspend fun getReaderSetting(workId: WorkId): AppReaderSetting? = withContext(Dispatchers.IO) {
        val setting = readerSettingDao.getSetting(workId) ?: return@withContext null
        val viewMode = try {
            ViewMode.valueOf(setting.viewMode)
        } catch (_: Exception) {
            ViewMode.SINGLE
        }
        val direction = try {
            ReadDirection.valueOf(setting.readDirection)
        } catch (_: Exception) {
            ReadDirection.RIGHT_TO_LEFT
        }
        val splitMode = try {
            SplitMode.valueOf(setting.splitMode)
        } catch (_: Exception) {
            SplitMode.FIT
        }
        AppReaderSetting(viewMode, direction, splitMode)
    }

    suspend fun saveReaderSetting(
        workId: WorkId,
        viewMode: ViewMode,
        readDirection: ReadDirection,
        splitMode: SplitMode,
    ) = withContext(Dispatchers.IO) {
        val writable = writableId(workId) ?: return@withContext
        readerSettingDao.saveSetting(
            ReaderSetting(
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

    fun formatReadProgress(sourceId: String, lastReadOrder: Int, totalEpisodes: Int, readCount: Int): String {
        val mode = sourceSettings?.progressDisplayMode(sourceId)
        return sources.formatReadProgress(sourceId, lastReadOrder, totalEpisodes, readCount, mode)
    }

    suspend fun countReadEpisodes(workId: WorkId): Int = withContext(Dispatchers.IO) {
        readEpisodeDao.countByToon(workId)
    }

    suspend fun countReadEpisodes(workIds: List<WorkId>): Map<String, Int> = withContext(Dispatchers.IO) {
        countReadEpisodesNow(workIds)
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

    private suspend fun countReadEpisodesNow(workIds: List<WorkId>): Map<String, Int> {
        val needed = workIds.filter {
            sources.progressDisplayMode(it.sourceId, sourceSettings).requiresReadCount
        }
        if (needed.isEmpty()) return emptyMap()
        val wanted = needed.map { it.storageKey() }.toSet()
        return readEpisodeDao.countsByToonIds(needed.map { it.toonId }.distinct())
            .mapNotNull { row ->
                val key = row.storageKey()
                if (key in wanted) key to row.readCount else null
            }
            .toMap()
    }

    private fun sourceFor(item: ToonItem) = sources.get(item.sourceId)

    private fun writableWorkId(item: ToonItem): WorkId? =
        writableId(item.sourceId, item.id)

    private fun writableId(workId: WorkId): WorkId? =
        SourceAccess.writable(workId, isSourceEnabled, installedIds())

    private fun writableId(sourceId: String, toonId: String): WorkId? =
        SourceAccess.writable(sourceId, toonId, isSourceEnabled, installedIds())

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
