package com.comics8.desktop.data

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeCatalog
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.EpisodeListingSnapshot
import com.comics8.core.model.EpisodeTotal
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
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceAccess
import com.comics8.core.source.SourceBindingManager
import com.comics8.core.source.SourceConfig
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.core.sync.SourceResolveWire
import com.comics8.core.sync.SyncCatalogWire
import java.io.File
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
    val bindingManager: SourceBindingManager = SourceBindingManager.desktopDefault(),
) : AutoCloseable {
    private val activeResolvingSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        val blankItems = local.filter { it.thumbUrl.isBlank() }
        val enrichedLocal = if (blankItems.isEmpty()) {
            local
        } else {
            val historyMap = database.getHistoryByToonIds(blankItems.map { it.workId() })
                .associateBy { it.workId().storageKey() }
            local.map { fav ->
                if (fav.thumbUrl.isBlank()) {
                    val histThumb = historyMap[fav.workId().storageKey()]?.toonThumbUrl
                    if (!histThumb.isNullOrBlank()) {
                        database.updateFavoriteThumbnail(fav.workId(), histThumb)
                        fav.copy(thumbUrl = histThumb)
                    } else fav
                } else fav
            }
        }
        val items = FavoriteListing.assemble(enrichedLocal)
        return ListingPage(
            items = applyFlags(items, touchFavorites = false),
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
        val firstEp = parsed.items.firstOrNull()
        val firstDate = firstEp?.date
        val firstThumb = firstEp?.thumbUrl
        val workId = item.workId()
        if (!firstDate.isNullOrBlank()) {
            try {
                database.updateFavoriteUpdatedAt(workId, firstDate)
            } catch (_: Exception) {
            }
        }
        if (!firstThumb.isNullOrBlank()) {
            try {
                database.updateFavoriteThumbnail(workId, firstThumb)
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
        onThumbnailResolved: ((workId: WorkId, thumbUrl: String) -> Unit)? = null,
        onUpdated: ((workId: WorkId, totalEpisodes: Int, progressText: String) -> Unit)? = null,
    ) {
        if (items.isEmpty()) return
        syncScope.launch {
            val workIds = items.map { it.workId() }
            val historyMap = database.getHistoryByToonIds(workIds).associateBy { it.workId().storageKey() }.toMutableMap()
            val catalogTotals = database.getCatalogTotalsByToonIds(workIds).toMutableMap()

            val discoveredUpdates = java.util.Collections.synchronizedList(mutableListOf<SyncCatalogWire>())

            // 0. Auto-Resolve Source Identity if not yet bound
            val sampleItem = items.firstOrNull()
            if (sampleItem != null && syncManager != null && !bindingManager.hasBinding(sampleItem.sourceId) && activeResolvingSources.add(sampleItem.sourceId)) {
                try {
                    val originHost = try { sources.getOrNull(sampleItem.sourceId)?.origin.orEmpty() } catch (_: Exception) { "" }
                    val host = SourceRegistry.hostOf(originHost).orEmpty()
                    val sampleIds = items.map { it.id }.take(50)
                    val sampleTitles = items.map { it.title }.take(50)
                    val res = syncManager.resolveSource(
                        SourceResolveWire(
                            clientSourceId = sampleItem.sourceId,
                            originHost = host,
                            sampleToonIds = sampleIds,
                            sampleTitles = sampleTitles,
                        )
                    )
                    if (res != null) {
                        bindingManager.bind(sampleItem.sourceId, res.canonicalSourceId)
                    }
                } catch (_: Exception) {
                } finally {
                    activeResolvingSources.remove(sampleItem.sourceId)
                }
            }

            // 1. Server-First: Batch query global catalog for items missing from local DB
            val missingFromLocal = workIds.filter { (catalogTotals[it.storageKey()]?.count ?: 0) == 0 }
            if (missingFromLocal.isNotEmpty() && syncManager != null) {
                try {
                    val canonicalQueries = missingFromLocal.map {
                        val cSid = bindingManager.getCanonicalSourceId(it.sourceId)
                        if (cSid != it.sourceId) WorkId(cSid, it.toonId) else it
                    }
                    val remoteEntries = syncManager.fetchCatalogBatch(canonicalQueries)
                    if (remoteEntries.isNotEmpty()) {
                        val catalogRecords = remoteEntries.map {
                            ToonCatalogRecord(
                                sourceId = sampleItem?.sourceId ?: it.sourceId,
                                toonId = it.toonId,
                                totalEpisodes = it.totalEpisodes,
                                updatedAt = it.updatedAt,
                                exact = it.exact,
                                lastPage = it.lastPage,
                                lastPageItemCount = it.lastPageItemCount,
                            )
                        }
                        database.saveAllCatalog(catalogRecords)
                        for (record in catalogRecords) {
                            val key = record.workId().storageKey()
                            val prev = catalogTotals[key] ?: EpisodeTotal.Unknown
                            val prevHist = historyMap[key]?.totalEpisodes ?: 0
                            val applied = RepositoryTransforms.applyCatalogTotal(
                                known = prev,
                                storedHistoryTotal = prevHist,
                                observation = record.total(),
                                history = historyMap[key],
                                readHistoryOrder = { it.lastReadOrder },
                                readHistoryTotal = { it.totalEpisodes },
                                readHistoryHasNew = { it.hasNew },
                                copyHistory = { hist, total, order, hasNew ->
                                    hist.copy(totalEpisodes = total, lastReadOrder = order, hasNew = hasNew)
                                },
                            )
                            val effectiveTotal = applied.total
                            if (effectiveTotal.count > prev.count || effectiveTotal.isExact != prev.isExact) {
                                catalogTotals[key] = effectiveTotal
                                applied.history?.takeIf { applied.changed }?.let { updatedHist ->
                                    saveHistory(updatedHist)
                                    historyMap[key] = updatedHist
                                }
                                val progressText = formatReadProgress(
                                    record.sourceId,
                                    applied.history?.lastReadOrder ?: 0,
                                    effectiveTotal,
                                    countReadEpisodesNow(listOf(record.workId()))[key] ?: 0,
                                )
                                onUpdated?.invoke(record.workId(), effectiveTotal.count, progressText)
                            }
                            if (prevHist > record.totalEpisodes) {
                                discoveredUpdates.add(
                                    SyncCatalogWire(
                                        sourceId = record.sourceId,
                                        toonId = record.toonId,
                                        totalEpisodes = prevHist,
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // 2. Incremental Web Scraping (Fallback for newly updated or explicitly requested items)
            val targets = items.filter { item ->
                val key = item.workId().storageKey()
                val shouldSync = RepositoryTransforms.needsEpisodeSync(
                    knownTotal = catalogTotals[key] ?: EpisodeTotal.Unknown,
                    lastReadOrder = historyMap[key]?.lastReadOrder ?: 0,
                    thumbUrl = item.thumbUrl,
                    isFavorite = item.isFavorite,
                )
                shouldSync && activeSyncKeys.add(key)
            }

            if (targets.isNotEmpty()) {
                val isListing = items.size > 1
                var encounteredUpToDate = false

                val semaphore = Semaphore(3)
                val jobs = targets.map { target ->
                    async {
                        val key = target.workId().storageKey()
                        try {
                            semaphore.withPermit {
                                // Once an up-to-date entry is met, the remaining listing rows are older
                                // still; only works with a real reason (unread / no cover / favorite)
                                // are worth the request.
                                val stillNeeded = RepositoryTransforms.needsEpisodeSync(
                                    knownTotal = catalogTotals[key] ?: EpisodeTotal.Unknown,
                                    lastReadOrder = historyMap[key]?.lastReadOrder ?: 0,
                                    thumbUrl = target.thumbUrl,
                                    isFavorite = target.isFavorite,
                                )
                                if (encounteredUpToDate && !stillNeeded) {
                                    return@withPermit
                                }
                                val metadata = fetchEpisodeMetadata(target)
                                val observedTotal = metadata.total
                                val snapshot = metadata.snapshot
                                val thumb = metadata.thumbUrl
                                if (!thumb.isNullOrBlank()) {
                                    onThumbnailResolved?.invoke(target.workId(), thumb)
                                }
                                if (observedTotal.count > 0) {
                                    val prev = catalogTotals[key] ?: EpisodeTotal.Unknown
                                    val isIncreased = observedTotal.count > prev.count
                                    val effectiveTotal = EpisodeTotal.merge(prev, observedTotal)
                                    database.recordCatalogTotal(
                                        target.workId(),
                                        observedTotal,
                                        snapshot = snapshot,
                                    )
                                    catalogTotals[key] = effectiveTotal

                                    if (isIncreased) {
                                        discoveredUpdates.add(
                                            SyncCatalogWire(
                                                sourceId = target.sourceId,
                                                toonId = target.id,
                                                totalEpisodes = observedTotal.count,
                                                updatedAt = System.currentTimeMillis(),
                                                exact = observedTotal.isExact,
                                            )
                                        )
                                    } else {
                                        if (isListing && !target.isNew && historyMap[key]?.hasNew != true) {
                                            encounteredUpToDate = true
                                        }
                                    }

                                    // Fresh read on purpose: this path REPLACEs the whole history row,
                                    // so writing back a map snapshot could roll back a lastReadOrder
                                    // that a concurrent reader update already committed.
                                    val existing = database.getHistory(target.workId())
                                    if (existing != null) {
                                        val merged = RepositoryTransforms.mergeHistoryProgress(
                                            existing.lastReadOrder,
                                            existing.totalEpisodes,
                                            effectiveTotal,
                                        )
                                        if (existing.totalEpisodes != merged.total.count || existing.hasNew != merged.hasNew) {
                                            val updated = existing.copy(
                                                totalEpisodes = merged.total.count,
                                                lastReadOrder = merged.lastReadOrder,
                                                hasNew = merged.hasNew,
                                            )
                                            saveHistory(updated)
                                            historyMap[key] = updated
                                            val readCounts = countReadEpisodesNow(listOf(target.workId()))
                                            val readCount = readCounts[key] ?: 0
                                            val progressText = formatReadProgress(
                                                target.sourceId,
                                                updated.lastReadOrder,
                                                merged.total,
                                                readCount,
                                            )
                                            onUpdated?.invoke(target.workId(), merged.total.count, progressText)
                                        }
                                    } else {
                                        val progressText = formatReadProgress(
                                            target.sourceId,
                                            0,
                                            effectiveTotal,
                                            0,
                                        )
                                        if (progressText.isNotBlank()) {
                                            onUpdated?.invoke(target.workId(), effectiveTotal.count, progressText)
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

            // 3. Instant crowdsource report to server
            if (discoveredUpdates.isNotEmpty() && syncManager != null) {
                try {
                    val canonicalUpdates = discoveredUpdates.map {
                        val cSid = bindingManager.getCanonicalSourceId(it.sourceId)
                        if (cSid != it.sourceId) it.copy(sourceId = cSid) else it
                    }
                    syncManager.reportCatalog(canonicalUpdates)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Resolves the episode total for one work.
     *
     * The normal path is a single page request combined with the stored listing shape. A full sweep
     * is only run when the shape is unknown or the reported page count changed, which is what keeps
     * every regular refresh at one request without ever multiplying an incomplete page up to a
     * page-sized guess.
     */
    suspend fun fetchEpisodeMetadata(item: ToonItem): RepositoryTransforms.EpisodeMetadata =
        withContext(Dispatchers.IO) {
            val pageSize = sourceFor(item).episodePageSize
            val firstPage = loadEpisodes(item, 1)
            val thumb = firstPage.items.firstOrNull()?.thumbUrl?.ifBlank { null }
            // One catalog read: the row carries both the total and the verified shape.
            val storedEntry = database.getCatalog(item.workId())
            val stored = storedEntry?.total() ?: EpisodeTotal.Unknown
            val storedShape = storedEntry?.snapshot()

            // Fast path: one page plus a verified shape proves the total.
            val quick = RepositoryTransforms.totalFromPage(firstPage, pageSize, storedShape)
            if (quick.isExact) {
                return@withContext RepositoryTransforms.EpisodeMetadata(quick, thumb, storedShape)
            }

            // No verified shape, or the site changed the page count: prove it with one full sweep.
            val swept = RepositoryTransforms.verifyEpisodeListing(pageSize) { page ->
                if (page == 1) firstPage else loadEpisodes(item, page)
            }
            RepositoryTransforms.EpisodeMetadata.fromSweep(swept, stored = stored, thumbUrl = thumb)
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

    /**
     * Persists an observed total. Only [EpisodeTotal.Exact] observations reach the shared catalog:
     * a lower bound is local certainty, not a fact other devices should inherit.
     */
    suspend fun saveCatalogTotal(
        workId: WorkId,
        total: EpisodeTotal,
        updatedAt: Long = now(),
        snapshot: EpisodeListingSnapshot? = null,
    ) = withContext(Dispatchers.IO) {
        if (total.count <= 0) return@withContext
        database.recordCatalogTotal(workId, total, updatedAt, snapshot)
        if (syncManager != null && total.isExact && sources.syncParticipates(workId.sourceId)) {
            try {
                val cSid = bindingManager.getCanonicalSourceId(workId.sourceId)
                syncManager.reportCatalog(
                    listOf(
                        SyncCatalogWire(
                            sourceId = cSid,
                            toonId = workId.toonId,
                            totalEpisodes = total.count,
                            updatedAt = updatedAt,
                            exact = true,
                            lastPage = snapshot?.lastPage ?: 0,
                            lastPageItemCount = snapshot?.lastPageItemCount ?: -1,
                        )
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun applyFlags(items: List<ToonItem>, touchFavorites: Boolean = true): List<ToonItem> {
        if (items.isEmpty()) return items
        val workIds = items.map { it.workId() }
        val favoriteKeys = database.getFavoritesByToonIds(workIds).map { it.workId().storageKey() }.toSet()
        val total = database.getSeenCount()
        val existing = if (total == 0) emptyMap() else database.getSeenByIds(workIds)
        val historyMap = database.getHistoryByToonIds(workIds).associateBy { it.workId().storageKey() }
        val catalogTotals = database.getCatalogTotalsByToonIds(workIds)
        val readCounts = countReadEpisodesNow(workIds.filter { historyMap.containsKey(it.storageKey()) })

        if (touchFavorites) {
            for (item in items) {
                val uDate = item.updatedAt
                if (item.workId().storageKey() in favoriteKeys && !uDate.isNullOrBlank()) {
                    try {
                        database.updateFavoriteUpdatedAt(item.workId(), uDate)
                    } catch (_: Exception) {}
                }
            }
        }

        return RepositoryTransforms.applyListingFlags(
            items = items,
            seenCount = total,
            favoriteKeys = favoriteKeys,
            seenByKey = existing,
            historyByKey = historyMap,
            readCountsByKey = readCounts,
            catalogTotalsByKey = catalogTotals,
            seenUpdatedAt = { it.updatedAt },
        ) { item, history, readCount, catalogTotal ->
            val total = EpisodeTotal.merge(
                catalogTotal,
                EpisodeTotal.fromStored(history?.totalEpisodes ?: 0),
            )
            formatReadProgress(item.sourceId, history?.lastReadOrder ?: 0, total, readCount)
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
            val effectiveThumb = item.thumbUrl.ifBlank {
                database.getHistory(workId)?.toonThumbUrl.orEmpty()
            }
            database.saveFavorite(
                FavoriteRecord(
                    sourceId = workId.sourceId,
                    id = workId.toonId,
                    title = item.title,
                    thumbUrl = effectiveThumb,
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

    fun formatReadProgress(sourceId: String, lastReadOrder: Int, total: EpisodeTotal, readCount: Int): String {
        val mode = DesktopSourcePrefs.progressDisplayMode(sourceId)
        return sources.formatReadProgress(sourceId, lastReadOrder, total, readCount, mode)
    }

    suspend fun countReadEpisodes(workId: WorkId): Int = withContext(Dispatchers.IO) {
        database.countReadEpisodes(workId)
    }

    suspend fun countReadEpisodes(workIds: List<WorkId>): Map<String, Int> = withContext(Dispatchers.IO) {
        countReadEpisodesNow(workIds)
    }

    suspend fun getHistory(sourceId: String): List<ReadHistoryRecord> = withContext(Dispatchers.IO) {
        val rows = database.getHistoryBySource(sourceId)
        if (rows.isEmpty()) return@withContext emptyList()
        val workIds = rows.map { it.workId() }
        val catalogTotals = database.getCatalogTotalsByToonIds(workIds)
        rows.map { row ->
            row.withMergedTotal(catalogTotals[row.workId().storageKey()] ?: EpisodeTotal.Unknown)
        }
    }

    suspend fun getHistory(workId: WorkId): ReadHistoryRecord? = withContext(Dispatchers.IO) {
        val row = database.getHistory(workId) ?: return@withContext null
        row.withMergedTotal(database.getCatalog(workId)?.total() ?: EpisodeTotal.Unknown)
    }

    suspend fun getCatalogTotal(workId: WorkId): EpisodeTotal = withContext(Dispatchers.IO) {
        database.getCatalog(workId)?.total() ?: EpisodeTotal.Unknown
    }

    private fun ReadHistoryRecord.withMergedTotal(catalogTotal: EpisodeTotal): ReadHistoryRecord {
        val merged = RepositoryTransforms.mergeHistoryProgress(lastReadOrder, totalEpisodes, catalogTotal)
        return if (merged.total.count == totalEpisodes && merged.hasNew == hasNew && merged.lastReadOrder == lastReadOrder) {
            this
        } else {
            copy(
                totalEpisodes = merged.total.count,
                lastReadOrder = merged.lastReadOrder,
                hasNew = merged.hasNew,
            )
        }
    }

    suspend fun saveHistory(history: ReadHistoryRecord) = withContext(Dispatchers.IO) {
        val workId = writableId(history.sourceId, history.toonId) ?: return@withContext
        database.saveHistory(history)
        if (history.totalEpisodes > 0) {
            // History only carries a count, never its exactness, so it merges as a lower bound.
            database.recordCatalogTotal(workId, EpisodeTotal.AtLeast(history.totalEpisodes), history.lastReadAt)
        }
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
            sources.progressDisplayMode(it.sourceId, DesktopSourcePrefs).requiresReadCount(it.sourceId)
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
