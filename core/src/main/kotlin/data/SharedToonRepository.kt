package com.comics8.core.data

import com.comics8.core.model.BackupResult
import com.comics8.core.model.BackupStats
import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeCatalog
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.FavoriteListing
import com.comics8.core.model.ListingPage
import com.comics8.core.model.OfflineEpisodeRef
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.RepositoryTransforms
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ToonItem
import com.comics8.core.model.UpdateDates
import com.comics8.core.model.ViewMode
import com.comics8.core.network.ToonClient
import com.comics8.core.source.ComicSource
import com.comics8.core.source.LatestUpdateSelection
import com.comics8.core.source.NotificationMode
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SearchSuggestion
import com.comics8.core.source.SourceAccess
import com.comics8.core.source.SourceBindingManager
import com.comics8.core.source.SourceConfig
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.SourceSettings
import com.comics8.core.source.WorkId
import com.comics8.core.storage.ComicLocalStore
import com.comics8.core.storage.FavoriteRecord
import com.comics8.core.storage.ReadEpisodeRecord
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.core.storage.ReaderSettingRecord
import com.comics8.core.storage.SeenRecord
import com.comics8.core.storage.ToonCatalogRecord
import com.comics8.core.sync.BaseSyncManager
import com.comics8.core.sync.SourceSampleWorkWire
import com.comics8.core.sync.SourceResolveWire
import com.comics8.core.sync.SyncCatalogWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

open class SharedToonRepository(
    val client: ToonClient,
    protected val store: ComicLocalStore,
    val sourceSettings: SourceSettings? = null,
    open val syncManager: BaseSyncManager? = null,
    open val downloadManager: SharedDownloadManager? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    protected val sources: SourceRegistry,
    private val isSourceEnabled: (String) -> Boolean = { false },
    private val installedIds: () -> Set<String> = { sources.knownIds() },
    val bindingManager: SourceBindingManager = SourceBindingManager.global,
    private val seenNotifiedKeyFallback: (ToonItem) -> String = { "" },
    private val upsertSeenOnSaveHistory: Boolean = true,
) : AutoCloseable {
    private val activeResolvingSources = ConcurrentHashMap.newKeySet<String>()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSyncKeys = ConcurrentHashMap.newKeySet<String>()

    fun sourceRegistry(): SourceRegistry = sources

    fun source(id: String) = sources.get(id)

    fun sourceOrNull(id: String): ComicSource? = sources.getOrNull(id)

    fun allSources() = sources.all()

    fun installedSources(): List<ComicSource> =
        RepositoryTransforms.installedSources(sources.all(), installedIds())

    fun activeSource(stored: String?): ComicSource? =
        sources.resolveActive(stored, installedIds())

    fun applySourceConfig(config: SourceConfig) {
        sources.applyConfig(config)
    }

    fun applyPreferences(languageFor: (String) -> String?) {
        sources.applyPreferences(languageFor)
    }

    suspend fun saveCatalogTotal(
        workId: WorkId,
        total: EpisodeListingTotal?,
        updatedAt: Long = now(),
    ) = withContext(Dispatchers.IO) {
        if (total == null || !total.isValid) return@withContext
        val changed = store.recordCatalogTotal(workId, total, updatedAt)
        val sync = syncManager
        if (changed && sync != null && sources.syncParticipates(workId.sourceId)) {
            try {
                val cSid = bindingManager.getCanonicalSourceId(workId.sourceId)
                sync.reportCatalog(
                    listOf(
                        SyncCatalogWire(
                            sourceId = cSid,
                            toonId = workId.toonId,
                            totalEpisodes = total.count,
                            updatedAt = updatedAt,
                            lastPage = total.lastPage,
                            pageSize = total.pageSize,
                            delta = total.delta,
                        ),
                    ),
                )
            } catch (_: Exception) {
            }
        }
    }

    suspend fun getCatalogTotal(workId: WorkId): EpisodeListingTotal? = withContext(Dispatchers.IO) {
        store.getCatalogEntry(workId)?.total()
    }

    suspend fun getCatalogTotals(workIds: List<WorkId>): Map<String, EpisodeListingTotal> =
        withContext(Dispatchers.IO) {
            if (workIds.isEmpty()) return@withContext emptyMap()
            store.getCatalogTotalsByWorkIds(workIds)
        }

    suspend fun loadListing(tab: BrowseTab, page: Int): ListingPage = withContext(Dispatchers.IO) {
        when (tab) {
            is BrowseTab.Favorite -> loadFavoriteListing(tab.sourceId)
            is BrowseTab.Remote -> fetchRemoteListing(tab.sourceId, tab.catalog.id, page)
        }
    }

    private suspend fun loadFavoriteListing(sourceId: String): ListingPage {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val local = store.getAllFavorites().map { it.toItem().copy(isFavorite = true) }
            .filter { it.sourceId.ifBlank { WorkId.DEFAULT_SOURCE } == sid }
        val blankItems = local.filter { it.thumbUrl.isBlank() }
        val enrichedLocal = if (blankItems.isEmpty()) {
            local
        } else {
            val historyMap = store.getHistoryByWorkIds(blankItems.map { it.workId() })
                .associateBy { it.workId().storageKey() }
            local.map { fav ->
                if (fav.thumbUrl.isBlank()) {
                    val histThumb = historyMap[fav.workId().storageKey()]?.toonThumbUrl
                    if (!histThumb.isNullOrBlank()) {
                        touchFavoriteThumbnail(fav.workId(), histThumb)
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
        touchFavoriteUpdatedAt(workId, firstDate)
        touchFavoriteThumbnail(workId, firstThumb)
        val readMap = store.getReadEpisodes(workId).associateBy { it.wrId }
        val readStates = readMap.mapValues { (_, read) ->
            RepositoryTransforms.EpisodeReadState(read.readAt, read.lastPage)
        }
        RepositoryTransforms.enrichEpisodePage(parsed, readStates)
    }

    suspend fun loadAllEpisodes(item: ToonItem): List<EpisodeItem> =
        EpisodeCatalog.loadAllPages { page -> loadEpisodes(item, page) }

    suspend fun loadLocalEpisodes(item: ToonItem): EpisodePage = withContext(Dispatchers.IO) {
        val workId = item.workId()
        val rows = downloadManager?.getDownloadedEpisodeRecords(workId).orEmpty()
        if (rows.isEmpty()) {
            return@withContext EpisodePage(emptyList(), 1, 1)
        }
        val readMap = store.getReadEpisodes(workId).associateBy { it.wrId }
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

    override fun close() {
        syncScope.cancel()
    }

    fun syncEpisodeCounts(
        items: List<ToonItem>,
        onThumbnailResolved: ((workId: WorkId, thumbUrl: String) -> Unit)? = null,
        onUpdated: ((workId: WorkId, total: EpisodeListingTotal, progressText: String) -> Unit)? = null,
    ): Job? {
        if (items.isEmpty()) return null
        return syncScope.launch {
            val workIds = items.map { it.workId() }
            val historyMap = try {
                store.getHistoryByWorkIds(workIds).associateBy { it.workId().storageKey() }.toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }
            val catalogTotals = try {
                store.getCatalogTotalsByWorkIds(workIds).toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }
            val readCountsMap = try {
                countReadEpisodesNow(workIds)
            } catch (_: Exception) {
                emptyMap()
            }

            val discoveredUpdates = Collections.synchronizedList(mutableListOf<SyncCatalogWire>())
            val sync = syncManager

            val sampleItem = items.firstOrNull()
            if (sampleItem != null && sync != null && !bindingManager.hasBinding(sampleItem.sourceId) && activeResolvingSources.add(sampleItem.sourceId)) {
                try {
                    val srcObj = try { sources.getOrNull(sampleItem.sourceId) } catch (_: Exception) { null }
                    val originHost = srcObj?.origin.orEmpty()
                    val host = SourceRegistry.hostOf(originHost).orEmpty()
                    val sampleIds = items.map { it.id }.take(50)
                    val sampleTitles = items.map { it.title }.take(50)
                    val sampleWorks = items.take(30).map {
                        SourceSampleWorkWire(
                            id = it.id,
                            title = it.title,
                            href = it.href,
                            thumbUrl = it.thumbUrl,
                        )
                    }
                    val res = sync.resolveSource(
                        SourceResolveWire(
                            clientSourceId = sampleItem.sourceId,
                            originHost = host,
                            sampleToonIds = sampleIds,
                            sampleTitles = sampleTitles,
                            displayName = srcObj?.displayName.orEmpty(),
                            catalogs = srcObj?.catalogs?.map { "${it.id}:${it.label}" }.orEmpty(),
                            episodePageSize = srcObj?.episodePageSize ?: 0,
                            sampleWorks = sampleWorks,
                        ),
                    )
                    if (res != null) {
                        bindingManager.bind(sampleItem.sourceId, res.canonicalSourceId)
                    }
                } catch (_: Exception) {
                } finally {
                    activeResolvingSources.remove(sampleItem.sourceId)
                }
            }

            val missingFromLocal = workIds.filter { (catalogTotals[it.storageKey()]?.count ?: 0) == 0 }
            if (missingFromLocal.isNotEmpty() && sync != null) {
                try {
                    val canonicalQueries = missingFromLocal.map {
                        val cSid = bindingManager.getCanonicalSourceId(it.sourceId)
                        if (cSid != it.sourceId) WorkId(cSid, it.toonId) else it
                    }
                    val remoteEntries = sync.fetchCatalogBatch(canonicalQueries)
                    if (remoteEntries.isNotEmpty()) {
                        val catalogRecords = remoteEntries.mapNotNull { wire ->
                            wire.total()?.let { counted ->
                                val localSid = sampleItem?.sourceId ?: bindingManager.getLocalSourceId(wire.sourceId)
                                ToonCatalogRecord.of(
                                    WorkId(localSid, wire.toonId),
                                    counted,
                                    wire.updatedAt,
                                )
                            }
                        }
                        store.upsertCatalogEntries(catalogRecords)
                        for (record in catalogRecords) {
                            val key = record.workId().storageKey()
                            val counted = record.total() ?: continue
                            val prev = catalogTotals[key]
                            catalogTotals[key] = counted
                            val hist = historyMap[key]
                            val merged = RepositoryTransforms.historyProgress(hist?.lastReadOrder ?: 0, counted)
                            if (hist != null && (hist.hasNew != merged.hasNew || hist.lastReadOrder != merged.lastReadOrder)) {
                                val updatedHist = hist.copy(
                                    lastReadOrder = merged.lastReadOrder,
                                    hasNew = merged.hasNew,
                                    totalEpisodes = counted.count,
                                )
                                saveHistoryRecord(updatedHist)
                                historyMap[key] = updatedHist
                            }
                            if (prev == null || prev.count != counted.count) {
                                val progressText = formatReadProgress(
                                    record.sourceId,
                                    historyMap[key]?.lastReadOrder ?: 0,
                                    counted,
                                    readCountsMap[key] ?: 0,
                                )
                                onUpdated?.invoke(record.workId(), counted, progressText)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val targets = items.filter { item ->
                val key = item.workId().storageKey()
                val shouldSync = RepositoryTransforms.needsEpisodeSync(
                    knownTotal = catalogTotals[key],
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
                                val stillNeeded = RepositoryTransforms.needsEpisodeSync(
                                    knownTotal = catalogTotals[key],
                                    lastReadOrder = historyMap[key]?.lastReadOrder ?: 0,
                                    thumbUrl = target.thumbUrl,
                                    isFavorite = target.isFavorite,
                                )
                                if (encounteredUpToDate && !stillNeeded) {
                                    return@withPermit
                                }
                                val metadata = fetchEpisodeMetadata(target)
                                val counted = metadata.total
                                val thumb = metadata.thumbUrl
                                if (!thumb.isNullOrBlank()) {
                                    onThumbnailResolved?.invoke(target.workId(), thumb)
                                }
                                if (counted != null) {
                                    val prev = catalogTotals[key]
                                    val isIncreased = prev == null || counted.count > prev.count
                                    val isChanged = prev != counted
                                    val updatedAt = now()
                                    store.recordCatalogTotal(target.workId(), counted, updatedAt)
                                    catalogTotals[key] = counted

                                    if (isChanged) {
                                        discoveredUpdates.add(
                                            SyncCatalogWire(
                                                sourceId = target.sourceId,
                                                toonId = target.id,
                                                totalEpisodes = counted.count,
                                                updatedAt = updatedAt,
                                                lastPage = counted.lastPage,
                                                pageSize = counted.pageSize,
                                                delta = counted.delta,
                                            ),
                                        )
                                    }
                                    if (!isIncreased && isListing && !target.isNew && historyMap[key]?.hasNew != true) {
                                        encounteredUpToDate = true
                                    }

                                    val existing = store.getHistory(target.workId())
                                    val merged = RepositoryTransforms.historyProgress(existing?.lastReadOrder ?: 0, counted)
                                    if (existing != null &&
                                        (existing.totalEpisodes != counted.count || existing.hasNew != merged.hasNew)
                                    ) {
                                        val updated = existing.copy(
                                            totalEpisodes = counted.count,
                                            lastReadOrder = merged.lastReadOrder,
                                            hasNew = merged.hasNew,
                                        )
                                        saveHistoryRecord(updated)
                                        historyMap[key] = updated
                                    }
                                    val progressText = formatReadProgress(
                                        target.sourceId,
                                        merged.lastReadOrder,
                                        counted,
                                        readCountsMap[key] ?: 0,
                                    )
                                    if (progressText.isNotBlank()) {
                                        onUpdated?.invoke(target.workId(), counted, progressText)
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

            if (discoveredUpdates.isNotEmpty() && sync != null) {
                try {
                    val canonicalUpdates = discoveredUpdates.map {
                        val cSid = bindingManager.getCanonicalSourceId(it.sourceId)
                        if (cSid != it.sourceId) it.copy(sourceId = cSid) else it
                    }
                    sync.reportCatalog(canonicalUpdates)
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun fetchEpisodeMetadata(
        item: ToonItem,
        knownPage: EpisodePage? = null,
    ): RepositoryTransforms.EpisodeMetadata = withContext(Dispatchers.IO) {
        val pageSize = sourceFor(item).episodePageSize
        val needThumb = item.thumbUrl.isBlank()
        if (knownPage != null && knownPage.lastPage <= 1 && knownPage.currentPage <= 1) {
            val singleTotal = RepositoryTransforms.totalFromPage(knownPage, pageSize, null)
            val thumb = if (!needThumb) null else knownPage.items.firstOrNull()?.thumbUrl?.ifBlank { null }
            return@withContext RepositoryTransforms.EpisodeMetadata(singleTotal, thumb)
        }
        val known = store.getCatalogEntry(item.workId())?.total()
        val loaded = HashMap<Int, EpisodePage>()
        if (knownPage != null) loaded[knownPage.currentPage] = knownPage
        suspend fun page(number: Int): EpisodePage =
            loaded[number] ?: loadEpisodes(item, number).also { loaded[number] = it }
        fun thumbFrom(number: Int): String? =
            if (!needThumb) null else loaded[number]?.items?.firstOrNull()?.thumbUrl?.ifBlank { null }

        val shapeStillMatches = knownPage == null || (known != null && known.describes(knownPage.lastPage, pageSize))
        if (known != null && known.isValid && shapeStillMatches) {
            val tailPage = known.lastPage.coerceAtLeast(1)
            val tail = page(tailPage)
            val reused = RepositoryTransforms.totalFromPage(tail, pageSize, known)
            if (reused != null) {
                if (needThumb && tailPage != 1) page(1)
                return@withContext RepositoryTransforms.EpisodeMetadata(reused, thumbFrom(tailPage) ?: thumbFrom(1))
            }
        }

        val counted = RepositoryTransforms.countEpisodeListing(pageSize) { number -> page(number) }
        RepositoryTransforms.EpisodeMetadata(counted, thumbFrom(1))
    }

    suspend fun loadImages(episode: EpisodeItem, workId: WorkId? = null): List<String> = withContext(Dispatchers.IO) {
        if (workId != null && downloadManager != null) {
            val localImages = downloadManager!!.getLocalEpisodeImages(workId, episode.wrId)
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

    suspend fun suggest(query: String, sourceId: String): List<SearchSuggestion> =
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
        val existing = store.getSeenByWorkIds(items.map { it.workId() })
        val rows = items.mapNotNull { item ->
            val workId = writableWorkId(item) ?: return@mapNotNull null
            val prev = existing[workId.storageKey()]
            SeenRecord(
                sourceId = workId.sourceId,
                id = workId.toonId,
                title = item.title,
                updatedAt = item.updatedAt,
                firstSeenAt = prev?.firstSeenAt ?: nowMs,
                lastSeenAt = nowMs,
                notifiedKey = prev?.notifiedKey ?: seenNotifiedKeyFallback(item),
            )
        }
        if (rows.isNotEmpty()) store.upsertSeenAll(rows)
    }

    suspend fun checkLatestUpdates(): List<ToonItem> {
        val favorites = store.getAllFavorites()
        if (favorites.isEmpty()) return emptyList()

        val unseen = mutableListOf<ToonItem>()
        val updatedSeen = mutableListOf<SeenRecord>()
        val nowMs = now()
        var historyChanged = false

        for ((sourceId, rows) in favorites.groupBy { it.sourceId.ifBlank { WorkId.DEFAULT_SOURCE } }) {
            val source = sources.getOrNull(sourceId) ?: continue
            val notifEnabled = sourceSettings?.isNotificationEnabled(sourceId) ?: true
            if (!notifEnabled) continue
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
                    notificationEnabled = notifEnabled,
                )
            } catch (_: Exception) {
                emptyList()
            }
            if (candidates.isEmpty()) continue
            val existing = store.getSeenByWorkIds(candidates.map { it.workId() })
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

                                val workHistory = store.getHistory(workId)
                                val isRead = store.getReadEpisode(workId, latestWrId) != null ||
                                    workHistory?.lastWrId == latestWrId

                                val pageSize = source.episodePageSize
                                val storedTotal = store.getCatalogEntry(workId)?.total()
                                val countedTotal = RepositoryTransforms.totalFromPage(epPage, pageSize, storedTotal)
                                    ?: storedTotal?.takeIf { it.describes(epPage.lastPage, pageSize) }
                                if (countedTotal != null && countedTotal != storedTotal) {
                                    store.recordCatalogTotal(workId, countedTotal, nowMs)
                                }

                                var changed = false
                                if (countedTotal != null && workHistory != null) {
                                    val merged = RepositoryTransforms.historyProgress(workHistory.lastReadOrder, countedTotal)
                                    if (workHistory.totalEpisodes != countedTotal.count || workHistory.hasNew != merged.hasNew) {
                                        store.upsertHistory(
                                            workHistory.copy(
                                                totalEpisodes = countedTotal.count,
                                                lastReadOrder = merged.lastReadOrder,
                                                hasNew = merged.hasNew,
                                            ),
                                        )
                                        changed = true
                                    }
                                }

                                val latestDate = latestEp.date ?: item.updatedAt
                                val prev = existing[workId.storageKey()]
                                val seen = SeenRecord(
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
            store.upsertSeenAll(updatedSeen)
        }
        if (historyChanged) {
            syncManager?.triggerDebouncedPush()
        }

        return unseen
    }

    private suspend fun applyFlags(items: List<ToonItem>, touchFavorites: Boolean = true): List<ToonItem> {
        if (items.isEmpty()) return items
        val workIds = items.map { it.workId() }
        val favoriteKeys = store.getFavoritesByWorkIds(workIds).map { it.workId().storageKey() }.toSet()
        val total = store.countSeen()
        val existing = if (total == 0) emptyMap() else store.getSeenByWorkIds(workIds)
        val historyMap = try {
            store.getHistoryByWorkIds(workIds).associateBy { it.workId().storageKey() }
        } catch (_: Exception) {
            emptyMap()
        }
        val readCounts = countReadEpisodesNow(workIds.filter { historyMap.containsKey(it.storageKey()) })
        val catalogTotals = try {
            store.getCatalogTotalsByWorkIds(workIds)
        } catch (_: Exception) {
            emptyMap()
        }
        if (touchFavorites) {
            for (item in items) {
                if (item.workId().storageKey() in favoriteKeys) {
                    touchFavoriteUpdatedAt(item.workId(), item.updatedAt)
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
            totalsByKey = catalogTotals,
            seenUpdatedAt = { it.updatedAt },
        ) { item, history, countedTotal, readCount ->
            formatReadProgress(item.sourceId, history?.lastReadOrder ?: 0, countedTotal, readCount)
        }
    }

    suspend fun refreshProgress(items: List<ToonItem>): List<ToonItem> = withContext(Dispatchers.IO) {
        applyFlags(items)
    }

    suspend fun isFavorite(workId: WorkId): Boolean = withContext(Dispatchers.IO) {
        store.hasFavorite(workId)
    }

    suspend fun toggleFavorite(item: ToonItem): Boolean? = withContext(Dispatchers.IO) {
        val workId = writableWorkId(item) ?: return@withContext null
        val exists = store.hasFavorite(workId)
        val result = if (exists) {
            store.deleteFavorite(workId)
            store.recordTombstone("FAVORITE", workId.storageKey(), now())
            false
        } else {
            val effectiveThumb = item.thumbUrl.ifBlank {
                store.getHistory(workId)?.toonThumbUrl.orEmpty()
            }
            store.upsertFavorite(
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
            store.deleteTombstone("FAVORITE", workId.storageKey())
            true
        }
        syncManager?.triggerDebouncedPush()
        result
    }

    suspend fun getReaderSetting(workId: WorkId): RepositoryTransforms.ReaderSettingValues? =
        withContext(Dispatchers.IO) {
            val setting = store.getReaderSetting(workId) ?: return@withContext null
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
        store.upsertReaderSetting(
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

    suspend fun markEpisodeRead(workId: WorkId, wrId: String, lastPage: Int = 0): Long =
        withContext(Dispatchers.IO) {
            val writable = writableId(workId) ?: return@withContext now()
            val readAt = now()
            store.upsertReadEpisode(
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
        store.updateReadEpisodeLastPage(workId, wrId, page, now())
    }

    suspend fun getReadEpisodeRecord(workId: WorkId, wrId: String): ReadEpisodeRecord? =
        withContext(Dispatchers.IO) {
            store.getReadEpisode(workId, wrId)
        }

    fun formatReadProgress(
        sourceId: String,
        lastReadOrder: Int,
        total: EpisodeListingTotal?,
        readCount: Int,
    ): String {
        val mode = sourceSettings?.progressDisplayMode(sourceId)
        return sources.formatReadProgress(sourceId, lastReadOrder, total, readCount, mode)
    }

    suspend fun countReadEpisodes(workId: WorkId): Int = withContext(Dispatchers.IO) {
        store.countReadEpisodesByToon(workId)
    }

    suspend fun countReadEpisodes(workIds: List<WorkId>): Map<String, Int> = withContext(Dispatchers.IO) {
        countReadEpisodesNow(workIds)
    }

    suspend fun getHistoryRecords(sourceId: String): List<ReadHistoryRecord> = withContext(Dispatchers.IO) {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val rows = store.getHistoryBySource(sid)
        if (rows.isEmpty()) return@withContext emptyList()
        val catMap = store.getCatalogTotalsByWorkIds(rows.map { it.workId() })
        rows.map { row -> row.withMergedTotal(catMap[row.workId().storageKey()]) }
    }

    suspend fun getHistoryRecord(workId: WorkId): ReadHistoryRecord? = withContext(Dispatchers.IO) {
        val row = store.getHistory(workId) ?: return@withContext null
        row.withMergedTotal(store.getCatalogEntry(workId)?.total())
    }

    private fun ReadHistoryRecord.withMergedTotal(catalogTotal: EpisodeListingTotal?): ReadHistoryRecord {
        val merged = RepositoryTransforms.historyProgress(lastReadOrder, catalogTotal)
        val count = catalogTotal?.count ?: totalEpisodes
        return if (count == totalEpisodes && merged.hasNew == hasNew && merged.lastReadOrder == lastReadOrder) {
            this
        } else {
            copy(
                totalEpisodes = count,
                lastReadOrder = merged.lastReadOrder,
                hasNew = merged.hasNew,
            )
        }
    }

    suspend fun saveHistoryRecord(history: ReadHistoryRecord) = withContext(Dispatchers.IO) {
        val workId = writableId(history.sourceId, history.toonId) ?: return@withContext
        val stored = if (history.sourceId == workId.sourceId) history else history.copy(sourceId = workId.sourceId)
        store.upsertHistory(stored)
        store.deleteTombstone("HISTORY", workId.storageKey())
        if (upsertSeenOnSaveHistory) {
            val nowMs = now()
            val existing = store.getSeenByWorkIds(listOf(workId))[workId.storageKey()]
            val safeNotifiedKey = existing?.notifiedKey?.takeIf { it.isNotBlank() }
                ?: "${workId.storageKey()}|${stored.lastWrId}"
            store.upsertSeenAll(
                listOf(
                    SeenRecord(
                        sourceId = workId.sourceId,
                        id = workId.toonId,
                        title = stored.toonTitle,
                        updatedAt = existing?.updatedAt,
                        firstSeenAt = existing?.firstSeenAt ?: nowMs,
                        lastSeenAt = nowMs,
                        notifiedKey = safeNotifiedKey,
                    ),
                ),
            )
        }
        syncManager?.triggerDebouncedPush()
    }

    suspend fun deleteHistory(workId: WorkId) = withContext(Dispatchers.IO) {
        store.deleteHistory(workId)
        store.recordTombstone("HISTORY", workId.storageKey(), now())
        syncManager?.triggerDebouncedPush()
    }

    suspend fun clearHistory(sourceId: String) = withContext(Dispatchers.IO) {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val rows = store.getHistoryBySource(sid)
        rows.forEach {
            store.recordTombstone("HISTORY", it.workId().storageKey(), now())
        }
        store.clearHistoryBySource(sid)
        syncManager?.triggerDebouncedPush()
    }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        SharedBackupManager.createBackupJson(store, now)
    }

    suspend fun importBackupJson(jsonString: String): BackupResult = withContext(Dispatchers.IO) {
        SharedBackupManager.restoreBackupJson(store, jsonString)
    }

    suspend fun getBackupStats(): BackupStats = withContext(Dispatchers.IO) {
        SharedBackupManager.getStats(store)
    }

    private suspend fun touchFavoriteUpdatedAt(workId: WorkId, updatedAt: String?) {
        if (updatedAt.isNullOrBlank()) return
        try {
            val current = store.getFavorite(workId) ?: return
            if (!UpdateDates.shouldReplace(current.updatedAt, updatedAt)) return
            store.updateFavoriteUpdatedAt(workId, updatedAt.trim())
        } catch (_: Exception) {
        }
    }

    private suspend fun touchFavoriteThumbnail(workId: WorkId, thumbUrl: String?) {
        if (thumbUrl.isNullOrBlank()) return
        try {
            store.updateFavoriteThumbUrlIfBlank(workId, thumbUrl)
        } catch (_: Exception) {
        }
    }

    private suspend fun countReadEpisodesNow(workIds: List<WorkId>): Map<String, Int> {
        val needed = workIds.filter {
            sources.progressDisplayMode(it.sourceId, sourceSettings).requiresReadCount(it.sourceId)
        }
        if (needed.isEmpty()) return emptyMap()
        return store.countReadEpisodesByWorkIds(needed)
    }

    private fun sourceFor(item: ToonItem) = sources.get(item.sourceId)

    private fun writableWorkId(item: ToonItem): WorkId? =
        writableId(item.sourceId, item.id)

    private fun writableId(workId: WorkId): WorkId? =
        SourceAccess.writable(workId, isSourceEnabled, installedIds())

    private fun writableId(sourceId: String, toonId: String): WorkId? =
        SourceAccess.writable(sourceId, toonId, isSourceEnabled, installedIds())

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
