package com.comics8.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comics8.monitor.ComicsApp
import com.comics8.monitor.data.Catalog
import com.comics8.monitor.data.EpisodeItem
import com.comics8.monitor.data.ReadDirection
import com.comics8.monitor.data.ToonItem
import com.comics8.monitor.data.ViewMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListingUiState(
    val catalog: Catalog = Catalog.LATEST,
    val page: Int = 1,
    val lastPage: Int = 1,
    val items: List<ToonItem> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val screen: Screen = Screen.Browse,
    val series: ToonItem? = null,
    val episodes: List<EpisodeItem> = emptyList(),
    val episodePage: Int = 1,
    val episodeLastPage: Int = 1,
    val episodeLoading: Boolean = false,
    val episodeError: String? = null,
    val showPageJump: Boolean = false,
    val searchInput: String = "",
    val searchQuery: String? = null,
    val searchExpanded: Boolean = false,
    val seriesFavorited: Boolean = false,
    val currentEpisode: EpisodeItem? = null,
    val readerImages: List<String> = emptyList(),
    val imageAspectRatios: Map<Int, Float> = emptyMap(),
    val readerLoading: Boolean = false,
    val readerError: String? = null,
    val viewMode: ViewMode = ViewMode.SCROLL,
    val readDirection: ReadDirection = ReadDirection.RIGHT_TO_LEFT,
) {
    val isSearch: Boolean get() = searchQuery != null

    val currentEpisodeIndex: Int
        get() = currentEpisode?.let { ep -> episodes.indexOfFirst { it.wrId == ep.wrId } } ?: -1

    val hasNextEpisode: Boolean
        get() = currentEpisodeIndex in 1 until episodes.size

    val hasPrevEpisode: Boolean
        get() = currentEpisodeIndex in 0 until (episodes.size - 1)
}

sealed interface Screen {
    data object Browse : Screen
    data object Series : Screen
    data object Reader : Screen
}

class ListingViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ComicsApp).repository
    private val _state = MutableStateFlow(ListingUiState())
    val state: StateFlow<ListingUiState> = _state.asStateFlow()

    private var listJob: Job? = null
    private var episodeJob: Job? = null
    private var readerJob: Job? = null

    init {
        refresh(force = true)
    }

    fun selectCatalog(catalog: Catalog) {
        if (
            catalog == _state.value.catalog &&
            _state.value.screen == Screen.Browse &&
            !_state.value.isSearch
        ) return
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update {
            it.copy(
                catalog = catalog,
                page = 1,
                lastPage = 1,
                items = emptyList(),
                screen = Screen.Browse,
                series = null,
                episodes = emptyList(),
                error = null,
                searchQuery = null,
                searchExpanded = false,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
            )
        }
        loadPage(1, replace = true)
    }

    fun toggleSearchBar() {
        val current = _state.value
        if (current.searchExpanded) {
            if (current.isSearch) {
                selectCatalog(current.catalog)
            } else {
                _state.update { it.copy(searchExpanded = false, searchInput = "") }
            }
        } else {
            _state.update { it.copy(searchExpanded = true) }
        }
    }

    fun updateSearchInput(value: String) {
        _state.update { it.copy(searchInput = value) }
    }

    fun submitSearch() {
        val query = _state.value.searchInput.trim()
        if (query.isEmpty()) return
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update {
            it.copy(
                searchQuery = query,
                searchInput = query,
                screen = Screen.Browse,
                series = null,
                episodes = emptyList(),
                items = emptyList(),
                error = null,
                page = 1,
                lastPage = 1,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
            )
        }
        runSearch(query, asRefresh = false)
    }

    fun refresh(force: Boolean = false) {
        val current = _state.value
        if (current.screen == Screen.Reader) {
            val episode = current.currentEpisode ?: return
            loadReaderImages(episode, asRefresh = true)
            return
        }
        if (current.screen == Screen.Series) {
            val series = current.series ?: return
            loadEpisodes(series, current.episodePage, asRefresh = true)
            return
        }
        if (current.isSearch) {
            val query = current.searchQuery ?: return
            runSearch(query, asRefresh = current.items.isNotEmpty())
            return
        }
        loadPage(if (force) 1 else current.page, replace = true, asRefresh = !force && current.items.isNotEmpty())
    }

    fun goToPage(page: Int) {
        val current = _state.value
        val target = page.coerceIn(1, current.lastPage.coerceAtLeast(1))
        if (!current.catalog.paginated) return
        loadPage(target, replace = true)
    }

    fun nextPage() {
        val current = _state.value
        if (current.page < current.lastPage) goToPage(current.page + 1)
    }

    fun prevPage() {
        val current = _state.value
        if (current.page > 1) goToPage(current.page - 1)
    }

    fun togglePageJump(show: Boolean) {
        _state.update { it.copy(showPageJump = show) }
    }

    fun openSeries(item: ToonItem) {
        _state.update {
            it.copy(
                screen = Screen.Series,
                series = item,
                episodes = emptyList(),
                episodePage = 1,
                episodeLastPage = 1,
                episodeError = null,
                seriesFavorited = false,
            )
        }
        loadEpisodes(item, 1)
        viewModelScope.launch {
            val favorited = repository.isFavorite(item.id)
            val savedSetting = repository.getReaderSetting(item.id)
            _state.update { current ->
                if (current.series?.id == item.id) {
                    current.copy(
                        seriesFavorited = favorited,
                        viewMode = savedSetting?.first ?: current.viewMode,
                        readDirection = savedSetting?.second ?: current.readDirection,
                    )
                } else {
                    current
                }
            }
        }
    }

    fun toggleFavorite() {
        val series = _state.value.series ?: return
        toggleFavorite(series)
    }

    fun toggleFavorite(item: ToonItem) {
        viewModelScope.launch {
            val favorited = repository.toggleFavorite(item)
            _state.update { current ->
                val updated = current.items.map { row ->
                    if (row.id == item.id) row.copy(isFavorite = favorited) else row
                }
                val visible = if (
                    !favorited &&
                    current.catalog == Catalog.FAVORITE &&
                    current.screen == Screen.Browse &&
                    !current.isSearch
                ) {
                    updated.filter { it.id != item.id }
                } else {
                    updated
                }
                current.copy(
                    seriesFavorited = if (current.series?.id == item.id) favorited else current.seriesFavorited,
                    items = visible,
                )
            }
        }
    }

    fun closeSeries() {
        readerJob?.cancel()
        episodeJob?.cancel()
        val catalog = _state.value.catalog
        val searching = _state.value.isSearch
        _state.update {
            it.copy(
                screen = Screen.Browse,
                series = null,
                episodes = emptyList(),
                episodeError = null,
                showPageJump = false,
                seriesFavorited = false,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
            )
        }
        if (catalog == Catalog.FAVORITE && !searching) {
            loadPage(1, replace = true)
        }
    }

    fun openEpisode(episode: EpisodeItem) {
        val series = _state.value.series
        val nowMs = System.currentTimeMillis()
        _state.update { current ->
            val updatedEpisodes = current.episodes.map {
                if (it.wrId == episode.wrId) it.copy(isRead = true, readAt = nowMs) else it
            }
            current.copy(
                screen = Screen.Reader,
                currentEpisode = episode.copy(isRead = true, readAt = nowMs),
                episodes = updatedEpisodes,
                readerImages = emptyList(),
                readerLoading = true,
                readerError = null,
            )
        }
        loadReaderImages(episode)
        if (series != null) {
            viewModelScope.launch {
                repository.markEpisodeRead(series.id, episode.wrId, episode.lastReadPage)
            }
        }
    }

    fun savePage(page: Int) {
        val current = _state.value
        val series = current.series ?: return
        val episode = current.currentEpisode ?: return
        val totalImages = current.readerImages.size
        val isLastPage = totalImages > 0 && page >= totalImages - 1
        val targetPage = if (isLastPage) 0 else page.coerceAtLeast(0)

        _state.update { state ->
            val updatedEpisode = state.currentEpisode?.copy(lastReadPage = targetPage)
            val updatedEpisodes = state.episodes.map {
                if (it.wrId == episode.wrId) it.copy(lastReadPage = targetPage) else it
            }
            state.copy(
                currentEpisode = updatedEpisode,
                episodes = updatedEpisodes,
            )
        }
        viewModelScope.launch {
            repository.saveEpisodePage(series.id, episode.wrId, targetPage)
        }
    }

    fun closeReader() {
        readerJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Series,
                currentEpisode = null,
                readerImages = emptyList(),
                readerLoading = false,
                readerError = null,
            )
        }
    }

    fun openNextEpisode() {
        val current = _state.value
        val series = current.series
        val currentEp = current.currentEpisode
        if (series != null && currentEp != null) {
            viewModelScope.launch {
                repository.saveEpisodePage(series.id, currentEp.wrId, 0)
            }
        }
        val idx = current.currentEpisodeIndex
        if (idx in 1 until current.episodes.size) {
            openEpisode(current.episodes[idx - 1])
        }
    }

    fun openPrevEpisode() {
        val current = _state.value
        val idx = current.currentEpisodeIndex
        if (idx in 0 until (current.episodes.size - 1)) {
            openEpisode(current.episodes[idx + 1])
        }
    }

    fun setViewMode(mode: ViewMode) {
        _state.update { it.copy(viewMode = mode) }
        val series = _state.value.series ?: return
        val dir = _state.value.readDirection
        viewModelScope.launch {
            repository.saveReaderSetting(series.id, mode, dir)
        }
    }

    fun setReadDirection(direction: ReadDirection) {
        _state.update { it.copy(readDirection = direction) }
        val series = _state.value.series ?: return
        val mode = _state.value.viewMode
        viewModelScope.launch {
            repository.saveReaderSetting(series.id, mode, direction)
        }
    }

    fun goToEpisodePage(page: Int) {
        val current = _state.value
        val series = current.series ?: return
        val target = page.coerceIn(1, current.episodeLastPage.coerceAtLeast(1))
        loadEpisodes(series, target)
    }

    private fun runSearch(query: String, asRefresh: Boolean) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = it.items.isEmpty() || !asRefresh,
                    refreshing = asRefresh,
                    error = null,
                    showPageJump = false,
                )
            }
            try {
                val items = repository.search(query)
                repository.markSeen(items)
                _state.update {
                    it.copy(
                        items = items,
                        page = 1,
                        lastPage = 1,
                        loading = false,
                        refreshing = false,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = humanError("검색에 실패했습니다.", e),
                    )
                }
            }
        }
    }

    private fun loadPage(page: Int, replace: Boolean, asRefresh: Boolean = false) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = it.items.isEmpty() || replace && !asRefresh,
                    refreshing = asRefresh,
                    error = null,
                    page = page,
                    showPageJump = false,
                )
            }
            try {
                val result = repository.loadListing(_state.value.catalog, page)
                repository.markSeen(result.items)
                _state.update {
                    it.copy(
                        items = result.items,
                        page = result.currentPage,
                        lastPage = result.lastPage,
                        loading = false,
                        refreshing = false,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = humanError("목록을 불러오지 못했습니다.", e),
                    )
                }
            }
        }
    }

    private fun loadEpisodes(item: ToonItem, page: Int, asRefresh: Boolean = false) {
        episodeJob?.cancel()
        episodeJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    episodeLoading = it.episodes.isEmpty() || !asRefresh,
                    episodeError = null,
                    episodePage = page,
                    refreshing = asRefresh,
                    showPageJump = false,
                )
            }
            try {
                val result = repository.loadEpisodes(item, page)
                _state.update {
                    it.copy(
                        episodes = result.items,
                        episodePage = result.currentPage,
                        episodeLastPage = result.lastPage,
                        episodeLoading = false,
                        refreshing = false,
                        episodeError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        episodeLoading = false,
                        refreshing = false,
                        episodeError = humanError("회차를 불러오지 못했습니다.", e),
                    )
                }
            }
        }
    }

    private fun loadReaderImages(episode: EpisodeItem, asRefresh: Boolean = false) {
        readerJob?.cancel()
        readerJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    readerLoading = it.readerImages.isEmpty() || !asRefresh,
                    readerError = null,
                    refreshing = asRefresh,
                )
            }
            try {
                val images = repository.loadImages(episode)
                _state.update {
                    it.copy(
                        readerImages = images,
                        imageAspectRatios = emptyMap(),
                        readerLoading = false,
                        refreshing = false,
                        readerError = null,
                    )
                }
                prefetchImageDimensions(images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        readerLoading = false,
                        refreshing = false,
                        readerError = humanError("만화 이미지를 불러오지 못했습니다.", e),
                    )
                }
            }
        }
    }

    fun recordImageAspectRatio(index: Int, width: Int, height: Int) {
        if (height <= 0 || width <= 0) return
        val ratio = width.toFloat() / height.toFloat()
        _state.update { current ->
            if (current.imageAspectRatios[index] == ratio) current
            else current.copy(imageAspectRatios = current.imageAspectRatios + (index to ratio))
        }
    }

    private fun prefetchImageDimensions(images: List<String>) {
        val context = getApplication<Application>()
        val imageLoader = coil.Coil.imageLoader(context)
        viewModelScope.launch {
            images.forEachIndexed { index, url ->
                val request = coil.request.ImageRequest.Builder(context)
                    .data(url)
                    .size(coil.size.Size.ORIGINAL)
                    .target(
                        onSuccess = { drawable ->
                            val w = drawable.intrinsicWidth
                            val h = drawable.intrinsicHeight
                            if (w > 0 && h > 0) {
                                recordImageAspectRatio(index, w, h)
                            }
                        }
                    )
                    .build()
                imageLoader.enqueue(request)
            }
        }
    }

    private fun humanError(prefix: String, e: Exception): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "$prefix\n$detail"
    }
}
