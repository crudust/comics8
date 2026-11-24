package com.comics8.core.app

import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.ArtistRef
import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.RepositoryTransforms
import com.comics8.core.model.ToonItem
import com.comics8.core.model.sortedWithOrder
import com.comics8.core.source.SearchSuggestion
import com.comics8.core.source.WorkId
import com.comics8.core.source.network.NetworkSourceRuntime
import com.comics8.core.storage.ReadHistoryRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class BrowseSearchCoordinator(
    private val controller: SharedAppController,
) {
    private val state get() = controller._state
    private val repository get() = controller.repository
    private val sourceSettings get() = controller.sourceSettings
    private val platform get() = controller.platform
    private val scope get() = controller.scope

    private var loadJob: Job? = null
    private var episodeJob: Job? = null
    private var suggestJob: Job? = null

    private val catalogCache = mutableMapOf<BrowseTab, List<ToonItem>>()
    private val catalogPages = mutableMapOf<BrowseTab, Pair<Int, Int>>()

    var lastViewedSeries: ToonItem? = null
    var lastViewedEpisodePage: Int = 1
    var previousScreenBeforeSeries: AppScreen = AppScreen.Browse

    val isLoadActive: Boolean get() = loadJob?.isActive == true

    fun cancelEpisodeJob() {
        episodeJob?.cancel()
    }

    fun close() {
        loadJob?.cancel()
        episodeJob?.cancel()
        suggestJob?.cancel()
    }

    fun updateCachedItems(tab: BrowseTab, items: List<ToonItem>) {
        catalogCache[tab] = items
    }

    fun clearCatalogCaches() {
        catalogCache.clear()
        catalogPages.clear()
    }

    fun clearCatalogCache(sourceId: String) {
        val stale = catalogCache.keys.filter { browseSourceId(it) == sourceId }
        stale.forEach {
            catalogCache.remove(it)
            catalogPages.remove(it)
        }
    }

    private fun browseSourceId(tab: BrowseTab): String = when (tab) {
        is BrowseTab.Favorite -> tab.sourceId
        is BrowseTab.Remote -> tab.sourceId
    }

    fun selectTab(tab: BrowseTab, force: Boolean = false) {
        if (
            !force &&
            tab == state.value.tab &&
            state.value.screen == AppScreen.Browse &&
            !state.value.isSearch
        ) {
            return
        }
        loadJob?.cancel()
        episodeJob?.cancel()
        controller.readerSession.cancelReaderJob()
        val cached = catalogCache[tab]
        val (cachedPage, cachedLastPage) = catalogPages[tab] ?: Pair(1, 1)
        state.update {
            it.copy(
                tab = tab,
                page = cachedPage,
                lastPage = cachedLastPage,
                items = cached ?: emptyList(),
                loading = cached == null,
                screen = AppScreen.Browse,
                series = null,
                rawEpisodes = emptyList(),
                episodes = emptyList(),
                error = null,
                searchQuery = null,
                searchInput = if (platform.collapseSearchClearsActiveSearch) it.searchInput else "",
                searchExpanded = false,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
            )
        }
        if (cached == null) {
            loadPage(cachedPage, replace = true)
        } else {
            scope.launch {
                val fresh = repository.refreshProgress(cached)
                catalogCache[tab] = fresh
                if (state.value.tab == tab && state.value.screen == AppScreen.Browse && !state.value.isSearch) {
                    state.update { it.copy(items = fresh) }
                }
                triggerEpisodeCountSync(fresh)
            }
        }
    }

    fun onListingOpen(item: ToonItem) {
        if (item.artistChoices.size >= 2) {
            state.update { it.copy(artistPick = ArtistPickRequest(item)) }
        } else {
            openSeries(item)
        }
    }

    fun openArtistPicker(item: ToonItem, entryEpisodeId: String? = item.entryEpisodeId) {
        if (item.artistChoices.size < 2) return
        state.update { it.copy(artistPick = ArtistPickRequest(item, entryEpisodeId)) }
    }

    fun dismissArtistPick() {
        state.update { it.copy(artistPick = null) }
    }

    fun pickArtist(artist: ArtistRef) {
        val request = state.value.artistPick ?: return
        dismissArtistPick()
        val parent = repository.sourceOrNull(request.item.sourceId)
            ?.resolveParent(request.item, artist, request.entryEpisodeId)
        if (parent != null) {
            openSeries(parent)
        }
    }

    fun toggleSearchBar() {
        val current = state.value
        if (current.searchExpanded) {
            if (platform.collapseSearchClearsActiveSearch && current.isSearch) {
                current.tab?.let { selectTab(it) }
            } else {
                state.update { it.copy(searchExpanded = false, searchInput = "", searchSuggestions = emptyList()) }
            }
        } else {
            state.update { it.copy(searchExpanded = true) }
        }
    }

    fun updateSearchInput(value: String) {
        state.update { it.copy(searchInput = value) }
        requestSuggestions(value)
    }

    fun applySearchSuggestion(suggestion: SearchSuggestion) {
        updateSearchInput(suggestion.applyTo(state.value.searchInput))
    }

    private fun requestSuggestions(value: String) {
        suggestJob?.cancel()
        val sourceId = state.value.activeSourceId
        if (value.isBlank() || sourceId == null) {
            state.update { it.copy(searchSuggestions = emptyList()) }
            return
        }
        suggestJob = scope.launch {
            delay(200)
            try {
                val items = repository.suggest(value, sourceId)
                state.update { current ->
                    if (current.searchInput == value) current.copy(searchSuggestions = items) else current
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.update { it.copy(searchSuggestions = emptyList()) }
            }
        }
    }

    fun submitSearch(query: String = state.value.searchInput) {
        if (state.value.activeSourceId == null) return
        val q = query.trim()
        if (q.isEmpty()) return
        controller.readerSession.cancelReaderJob()
        episodeJob?.cancel()
        state.update {
            it.copy(
                searchQuery = q,
                searchInput = q,
                searchSuggestions = emptyList(),
                screen = AppScreen.Browse,
                series = null,
                rawEpisodes = emptyList(),
                episodes = emptyList(),
                items = emptyList(),
                page = 1,
                lastPage = 1,
                error = null,
                loading = true,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
            )
        }
        runSearch(q, asRefresh = false)
    }

    fun refresh(force: Boolean = false) {
        controller.syncBackupUpdate.checkForUpdate()
        platform.clearImageFailures()
        if (!force && state.value.screen == AppScreen.Reader && !platform.refreshReaderUsesAsRefresh) {
            controller.readerSession.refreshReaderImages()
            return
        }
        state.update { it.copy(refreshEpoch = it.refreshEpoch + 1) }
        if (state.value.activeSourceId == null) return
        if (force) {
            clearCatalogCaches()
        }
        val current = state.value
        if (current.screen == AppScreen.Reader) {
            val episode = current.currentEpisode ?: return
            controller.readerSession.loadReaderImages(episode, asRefresh = true)
            return
        }
        if (current.screen == AppScreen.Series) {
            val series = current.series ?: return
            loadEpisodes(series, current.episodePage, asRefresh = platform.refreshReaderUsesAsRefresh)
            return
        }
        if (current.screen == AppScreen.Browse) {
            syncOnBrowseRefresh()
        }
        if (current.isSearch) {
            val q = current.searchQuery.orEmpty()
            if (q.isNotEmpty()) runSearch(q, asRefresh = platform.refreshReaderUsesAsRefresh && current.items.isNotEmpty())
            return
        }
        if (!force && !platform.refreshReaderUsesAsRefresh) {
            current.tab?.let { catalogCache.remove(it) }
        }
        val targetPage = if (force) 1 else current.page
        val asRefresh = if (platform.refreshReaderUsesAsRefresh) (!force && current.items.isNotEmpty()) else true
        loadPage(targetPage, replace = true, asRefresh = asRefresh)
    }

    fun syncOnBrowseRefresh() {
        val sm = repository.syncManager ?: return
        scope.launch {
            val res = sm.syncIfIdle() ?: return@launch
            if (res.success && (res.favoritesCount > 0 || res.historyCount > 0 || res.episodesCount > 0)) {
                val now = state.value
                if (now.screen == AppScreen.Browse && !now.isSearch) {
                    loadPage(page = now.page, replace = true)
                }
            }
        }
    }

    fun goToPage(page: Int) {
        val current = state.value
        if (current.tab?.paginated != true) return
        val target = ReaderDomain.clampPage(page, current.lastPage)
        state.update {
            it.copy(
                items = emptyList(),
                loading = true,
                error = null,
                page = target,
                showPageJump = false,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
            )
        }
        loadPage(target, replace = true)
    }

    fun nextPage() {
        val current = state.value
        if (current.page < current.lastPage) goToPage(current.page + 1)
    }

    fun prevPage() {
        val current = state.value
        if (current.page > 1) goToPage(current.page - 1)
    }

    fun nextEpisodePage() {
        val current = state.value
        if (current.episodePage < current.episodeLastPage) goToEpisodePage(current.episodePage + 1)
    }

    fun prevEpisodePage() {
        val current = state.value
        if (current.episodePage > 1) goToEpisodePage(current.episodePage - 1)
    }

    fun togglePageJump(show: Boolean) {
        state.update { it.copy(showPageJump = show) }
    }

    fun openSeries(item: ToonItem) {
        val currentScreen = state.value.screen
        if (currentScreen != AppScreen.Series && currentScreen != AppScreen.Reader) {
            previousScreenBeforeSeries = currentScreen
        }
        lastViewedSeries = item
        platform.cancelPendingImagePreviews()
        platform.clearImageMemory(ImageCacheRole.EPISODE)
        episodeJob?.cancel()
        val sortOrder = sourceSettings.episodeSortOrder(item.sourceId)
        state.update {
            it.copy(
                screen = AppScreen.Series,
                series = item,
                seriesHistory = null,
                rawEpisodes = emptyList(),
                episodes = emptyList(),
                episodeSortOrder = sortOrder,
                episodePage = 1,
                episodeLastPage = 1,
                episodeError = null,
                seriesFavorited = false,
                highlightedEpisodeId = item.entryEpisodeId,
                viewMode = if (platform.persistGlobalReaderDefaults) controller.resolveTargetViewMode() else it.viewMode,
                readDirection = if (platform.persistGlobalReaderDefaults) controller.globalReadDirection else it.readDirection,
                downloadedWrIds = emptySet(),
            )
        }
        loadEpisodes(item, 1)
        scope.launch {
            val favorited = repository.isFavorite(item.workId())
            val history = repository.getHistoryRecord(item.workId())
            val readCount = controller.countIfNeeded(item.workId())
            val catalogTotal = repository.getCatalogTotal(item.workId())
            val itemKey = item.workId().storageKey()
            state.update { current ->
                val nextTotals = if (catalogTotal != null && current.catalogTotals[itemKey] == null) {
                    current.catalogTotals + (itemKey to catalogTotal)
                } else {
                    current.catalogTotals
                }
                if (current.series?.workId() == item.workId()) {
                    current.copy(
                        seriesFavorited = favorited,
                        seriesHistory = history,
                        readCounts = current.readCounts.withCount(item.workId(), readCount),
                        catalogTotals = nextTotals,
                    )
                } else {
                    current.copy(catalogTotals = nextTotals)
                }
            }
        }
    }

    fun toggleFavorite() {
        val series = state.value.series ?: return
        toggleFavorite(series)
    }

    fun toggleFavorite(item: ToonItem) {
        scope.launch {
            val favorited = repository.toggleFavorite(item) ?: return@launch
            state.update { current ->
                val updated = current.items.map { row ->
                    if (row.workId() == item.workId()) row.copy(isFavorite = favorited) else row
                }
                val visible = if (
                    !favorited &&
                    current.tab is BrowseTab.Favorite &&
                    current.screen == AppScreen.Browse &&
                    !current.isSearch
                ) {
                    updated.filter { it.workId() != item.workId() }
                } else {
                    updated
                }
                if (current.tab !is BrowseTab.Favorite) {
                    catalogCache.remove(BrowseTab.Favorite(item.sourceId.ifBlank { WorkId.DEFAULT_SOURCE }))
                }
                if (!current.isSearch) {
                    current.tab?.let { catalogCache[it] = visible }
                }
                current.copy(
                    seriesFavorited = if (current.series?.workId() == item.workId()) favorited else current.seriesFavorited,
                    items = visible,
                )
            }
        }
    }

    fun resumeSeries() {
        val current = state.value
        current.series ?: return
        val hist = current.seriesHistory ?: run {
            openFirstEpisode()
            return
        }
        controller.historyDownloads.continueHistoryEpisode(hist)
    }

    fun openFirstEpisode() {
        val current = state.value
        val series = current.series ?: return
        val lastPage = current.episodeLastPage
        val raw = current.rawEpisodes.ifEmpty { current.episodes }
        val seriesKey = series.workId().storageKey()
        if (lastPage <= 1) {
            val firstEp = raw.lastOrNull()
            if (firstEp != null) {
                controller.readerSession.openEpisode(firstEp)
                return
            }
        }
        scope.launch {
            try {
                val metadata = repository.fetchEpisodeMetadata(series)
                repository.saveCatalogTotal(series.workId(), metadata.total)

                val result = repository.loadEpisodes(series, lastPage.coerceAtLeast(1))
                state.update {
                    it.copy(
                        rawEpisodes = result.items,
                        episodes = result.items.sortedWithOrder(it.episodeSortOrder),
                        episodePage = result.currentPage,
                        episodeLastPage = result.lastPage,
                        catalogTotals = metadata.total?.let { t -> it.catalogTotals + (seriesKey to t) } ?: it.catalogTotals,
                    )
                }
                result.items.lastOrNull()?.let(controller.readerSession::openEpisode)
            } catch (_: Exception) {
            }
        }
    }

    fun closeSeries() {
        platform.cancelPendingImagePreviews()
        platform.clearImageMemory(ImageCacheRole.EPISODE)
        controller.readerSession.cancelReaderJob()
        episodeJob?.cancel()
        val current = state.value
        current.series?.sourceId?.let { NetworkSourceRuntime.disconnect(it) }
        lastViewedSeries = current.series
        lastViewedEpisodePage = current.episodePage
        val tab = current.tab
        val searching = current.isSearch
        state.update {
            it.copy(
                screen = AppScreen.Browse,
                series = null,
                seriesHistory = null,
                rawEpisodes = emptyList(),
                episodes = emptyList(),
                episodeError = null,
                showPageJump = false,
                seriesFavorited = false,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
                highlightedEpisodeId = null,
            )
        }
        if (tab != null && !searching) {
            val cached = catalogCache[tab]
            if (cached != null) {
                scope.launch {
                    val fresh = repository.refreshProgress(cached)
                    catalogCache[tab] = fresh
                    if (state.value.tab == tab && state.value.screen == AppScreen.Browse && !state.value.isSearch) {
                        state.update { it.copy(items = fresh) }
                    }
                }
            } else if (tab is BrowseTab.Favorite) {
                loadPage(1, replace = true)
            }
        }
    }

    fun goToEpisodePage(page: Int) {
        val current = state.value
        val series = current.series ?: return
        val target = ReaderDomain.clampPage(page, current.episodeLastPage)
        loadEpisodes(series, target)
    }

    fun loadPage(page: Int, replace: Boolean = false, asRefresh: Boolean = false) {
        val tab = state.value.tab ?: return
        val targetPage = page.coerceAtLeast(1)
        if (!replace && loadJob?.isActive == true && state.value.page == targetPage && state.value.loading) {
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            state.update {
                it.copy(
                    loading = if (asRefresh) (it.items.isEmpty() || !asRefresh) else (if (replace) it.items.isEmpty() else true),
                    refreshing = asRefresh || (replace && it.items.isNotEmpty() && !platform.refreshReaderUsesAsRefresh),
                    error = null,
                    page = targetPage,
                    showPageJump = false,
                )
            }
            try {
                val result = repository.loadListing(tab, targetPage)
                if (result.items.isNotEmpty() && (platform.markSeenOnFavoriteTab || tab !is BrowseTab.Favorite)) {
                    repository.markSeen(result.items)
                }
                catalogCache[tab] = result.items
                catalogPages[tab] = Pair(result.currentPage, result.lastPage)
                state.update {
                    it.copy(
                        items = result.items,
                        page = result.currentPage,
                        lastPage = result.lastPage,
                        loading = false,
                        refreshing = false,
                        error = null,
                        scrollToTopTrigger = it.scrollToTopTrigger + 1,
                    )
                }
                triggerEpisodeCountSync(result.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = humanError("목록을 불러오지 못했습니다.", e),
                    )
                }
            }
        }
    }

    fun loadEpisodes(item: ToonItem, page: Int, asRefresh: Boolean = false) {
        episodeJob?.cancel()
        episodeJob = scope.launch {
            val local = if (page <= 1) {
                repository.loadLocalEpisodes(item)
            } else {
                EpisodePage(emptyList(), page, 1)
            }
            val hasLocal = local.items.isNotEmpty()
            val sortOrder = sourceSettings.episodeSortOrder(item.sourceId)
            state.update {
                it.copy(
                    episodeSortOrder = sortOrder,
                    episodeLoading = !hasLocal && (it.episodes.isEmpty() || !asRefresh),
                    episodeError = null,
                    episodePage = if (hasLocal) 1 else page,
                    episodeLastPage = if (hasLocal) 1 else it.episodeLastPage,
                    rawEpisodes = if (hasLocal) local.items else it.rawEpisodes,
                    episodes = if (hasLocal) local.items.sortedWithOrder(sortOrder) else it.episodes,
                    refreshing = asRefresh,
                    showPageJump = false,
                )
            }
            if (hasLocal) {
                controller.historyDownloads.refreshDownloadedWrIds()
            }
            try {
                val result = repository.loadEpisodes(item, page)
                state.update {
                    it.copy(
                        rawEpisodes = result.items,
                        episodes = result.items.sortedWithOrder(it.episodeSortOrder),
                        episodePage = result.currentPage,
                        episodeLastPage = result.lastPage,
                        episodeLoading = false,
                        refreshing = false,
                        episodeError = null,
                        scrollToTopTrigger = it.scrollToTopTrigger + 1,
                    )
                }
                controller.historyDownloads.refreshDownloadedWrIds()
                if (result.items.isNotEmpty()) {
                    val firstThumb = result.items.firstOrNull()?.thumbUrl
                    if (!firstThumb.isNullOrBlank() && item.thumbUrl.isBlank()) {
                        updateThumbnailIfBlank(item.workId(), firstThumb)
                    }
                    val metadata = repository.fetchEpisodeMetadata(item, knownPage = result)
                    metadata.total?.let { t ->
                        state.update { it.copy(catalogTotals = it.catalogTotals + (item.workId().storageKey() to t)) }
                    }
                    updateHistoryAndProgress(item, metadata.total)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val keepLocal = hasLocal || state.value.episodes.isNotEmpty()
                state.update {
                    it.copy(
                        episodeLoading = false,
                        refreshing = false,
                        episodeError = if (keepLocal) null else humanError("회차를 불러오지 못했습니다.", e),
                    )
                }
            }
        }
    }

    private fun runSearch(query: String, asRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            state.update {
                it.copy(
                    loading = it.items.isEmpty() || !asRefresh,
                    refreshing = asRefresh,
                    error = null,
                    showPageJump = false,
                )
            }
            try {
                val sourceId = state.value.activeSourceId ?: return@launch
                val items = repository.search(query, sourceId)
                repository.markSeen(items)
                state.update {
                    it.copy(
                        items = items,
                        page = 1,
                        lastPage = 1,
                        loading = false,
                        refreshing = false,
                        error = null,
                        scrollToTopTrigger = it.scrollToTopTrigger + 1,
                    )
                }
                triggerEpisodeCountSync(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = humanError("검색에 실패했습니다.", e),
                    )
                }
            }
        }
    }

    private suspend fun updateHistoryAndProgress(item: ToonItem, counted: EpisodeListingTotal?) {
        val total = counted?.takeIf { it.isValid } ?: return
        repository.saveCatalogTotal(item.workId(), total)
        val rawEpisodes = state.value.rawEpisodes.ifEmpty { state.value.episodes }
        val latestRead = rawEpisodes.firstOrNull { it.isRead }
        val seriesKey = item.workId().storageKey()
        val derivedPosition = if (latestRead != null) {
            ReaderDomain.episodePosition(
                episodeIds = rawEpisodes.map { it.wrId },
                currentEpisodeId = latestRead.wrId,
                currentPage = state.value.episodePage,
                lastPage = state.value.episodeLastPage,
                knownTotal = state.value.catalogTotals[seriesKey] ?: total,
                pageSize = repository.sourceOrNull(item.sourceId)?.episodePageSize ?: 100,
            )
        } else {
            null
        }
        val derivedOrder = derivedPosition?.readOrder

        val existing = repository.getHistoryRecord(item.workId())
        if (existing == null) {
            if (latestRead != null && derivedOrder != null) {
                val nextEp = derivedPosition.nextEpisodeIndex?.let { rawEpisodes.getOrNull(it) }
                val progress = RepositoryTransforms.historyProgress(derivedOrder, total)
                val created = ReadHistoryRecord(
                    sourceId = item.sourceId,
                    toonId = item.id,
                    toonTitle = item.title,
                    toonThumbUrl = item.thumbUrl,
                    toonHref = item.href,
                    lastWrId = latestRead.wrId,
                    lastEpisodeTitle = latestRead.title,
                    lastEpisodeHref = latestRead.href,
                    lastReadOrder = progress.lastReadOrder,
                    totalEpisodes = total.count,
                    lastReadAt = latestRead.readAt ?: System.currentTimeMillis(),
                    nextWrId = nextEp?.wrId,
                    nextEpisodeTitle = nextEp?.title,
                    nextEpisodeHref = nextEp?.href,
                    hasNew = progress.hasNew,
                )
                repository.saveHistoryRecord(created)
                val (progressText, readCount) = controller.listingProgress(
                    item.sourceId,
                    created.lastReadOrder,
                    total,
                    item.workId(),
                )
                state.update { curr ->
                    val updatedItems = curr.items.map { row ->
                        if (row.workId() == item.workId()) row.copy(readProgress = progressText) else row
                    }
                    curr.copy(
                        items = updatedItems,
                        seriesHistory = if (curr.series?.workId() == item.workId()) created else curr.seriesHistory,
                        readCounts = curr.readCounts.withCount(item.workId(), readCount),
                    )
                }
            } else {
                val (progressText, _) = controller.listingProgress(
                    item.sourceId,
                    0,
                    total,
                    item.workId(),
                )
                if (progressText.isNotBlank()) {
                    state.update { curr ->
                        val updatedItems = curr.items.map { row ->
                            if (row.workId() == item.workId()) row.copy(readProgress = progressText) else row
                        }
                        curr.copy(items = updatedItems)
                    }
                }
            }
            return
        }

        val mergedProgress = RepositoryTransforms.historyProgress(existing.lastReadOrder, total)
        val candidateOrder = mergedProgress.lastReadOrder
        val shouldElevate = derivedOrder != null && derivedOrder > candidateOrder
        val safeOrder = if (shouldElevate) derivedOrder else candidateOrder
        val hasNew = if (shouldElevate) total.needsRefresh(safeOrder) else mergedProgress.hasNew

        val updatedWrId = if (shouldElevate && latestRead != null) latestRead.wrId else existing.lastWrId
        val updatedTitle = if (shouldElevate && latestRead != null) latestRead.title else existing.lastEpisodeTitle
        val updatedHref = if (shouldElevate && latestRead != null) latestRead.href else existing.lastEpisodeHref
        val updatedReadAt = if (shouldElevate && latestRead != null) {
            maxOf(existing.lastReadAt, latestRead.readAt ?: 0L)
        } else {
            existing.lastReadAt
        }
        val nextIndex = if (shouldElevate) derivedPosition.nextEpisodeIndex else null
        val updatedNextWrId = if (nextIndex != null) {
            rawEpisodes.getOrNull(nextIndex)?.wrId ?: existing.nextWrId
        } else {
            existing.nextWrId
        }
        val updatedNextTitle = if (nextIndex != null) {
            rawEpisodes.getOrNull(nextIndex)?.title ?: existing.nextEpisodeTitle
        } else {
            existing.nextEpisodeTitle
        }
        val updatedNextHref = if (nextIndex != null) {
            rawEpisodes.getOrNull(nextIndex)?.href ?: existing.nextEpisodeHref
        } else {
            existing.nextEpisodeHref
        }

        if (existing.totalEpisodes != total.count ||
            existing.lastReadOrder != safeOrder ||
            existing.hasNew != hasNew ||
            existing.lastWrId != updatedWrId ||
            existing.lastReadAt != updatedReadAt
        ) {
            val updated = existing.copy(
                totalEpisodes = total.count,
                lastReadOrder = safeOrder,
                hasNew = hasNew,
                lastWrId = updatedWrId,
                lastEpisodeTitle = updatedTitle,
                lastEpisodeHref = updatedHref,
                lastReadAt = updatedReadAt,
                nextWrId = updatedNextWrId,
                nextEpisodeTitle = updatedNextTitle,
                nextEpisodeHref = updatedNextHref,
            )
            repository.saveHistoryRecord(updated)
            val (progressText, readCount) = controller.listingProgress(
                item.sourceId,
                updated.lastReadOrder,
                total,
                item.workId(),
            )
            state.update { curr ->
                val updatedItems = curr.items.map { row ->
                    if (row.workId() == item.workId()) row.copy(readProgress = progressText) else row
                }
                curr.copy(
                    items = updatedItems,
                    seriesHistory = if (curr.series?.workId() == item.workId()) updated else curr.seriesHistory,
                    readCounts = curr.readCounts.withCount(item.workId(), readCount),
                )
            }
        }
    }

    private fun updateThumbnailIfBlank(workId: WorkId, thumbUrl: String) {
        if (thumbUrl.isBlank()) return
        state.update { curr ->
            val updatedItems = curr.items.map { row ->
                if (row.workId() == workId && row.thumbUrl.isBlank()) {
                    row.copy(thumbUrl = thumbUrl)
                } else {
                    row
                }
            }
            if (!curr.isSearch) {
                curr.tab?.let { tab ->
                    catalogCache[tab]?.let { cached ->
                        catalogCache[tab] = cached.map { row ->
                            if (row.workId() == workId && row.thumbUrl.isBlank()) {
                                row.copy(thumbUrl = thumbUrl)
                            } else {
                                row
                            }
                        }
                    }
                }
            }
            val updatedSeries = if (curr.series?.workId() == workId && curr.series.thumbUrl.isBlank()) {
                curr.series.copy(thumbUrl = thumbUrl)
            } else {
                curr.series
            }
            curr.copy(items = updatedItems, series = updatedSeries)
        }
    }

    private fun triggerEpisodeCountSync(items: List<ToonItem>) {
        repository.syncEpisodeCounts(
            items,
            onThumbnailResolved = ::updateThumbnailIfBlank,
            onUpdated = { workId, total, progressText ->
                state.update { curr ->
                    val updatedItems = curr.items.map { row ->
                        if (row.workId() == workId) row.copy(readProgress = progressText) else row
                    }
                    if (!curr.isSearch) {
                        curr.tab?.let { tab ->
                            catalogCache[tab]?.let { cached ->
                                catalogCache[tab] = cached.map { row ->
                                    if (row.workId() == workId) row.copy(readProgress = progressText) else row
                                }
                            }
                        }
                    }
                    curr.copy(
                        items = updatedItems,
                        catalogTotals = curr.catalogTotals + (workId.storageKey() to total),
                        seriesHistory = if (curr.series?.workId() == workId) {
                            curr.seriesHistory?.let { currHist ->
                                val merged = RepositoryTransforms.historyProgress(currHist.lastReadOrder, total)
                                currHist.copy(
                                    totalEpisodes = total.count,
                                    lastReadOrder = merged.lastReadOrder,
                                    hasNew = merged.hasNew,
                                )
                            }
                        } else {
                            curr.seriesHistory
                        },
                    )
                }
            },
        )
    }

    fun refreshSeriesFavorited(series: ToonItem) {
        scope.launch {
            val favorited = repository.isFavorite(series.workId())
            state.update { current ->
                if (current.series?.workId() == series.workId()) current.copy(seriesFavorited = favorited) else current
            }
        }
    }
}
