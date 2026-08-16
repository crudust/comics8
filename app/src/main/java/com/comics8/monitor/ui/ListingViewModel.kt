package com.comics8.monitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comics8.monitor.ComicsApp
import com.comics8.monitor.data.Catalog
import com.comics8.monitor.data.EpisodeItem
import com.comics8.monitor.data.ToonItem
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
) {
    val isSearch: Boolean get() = searchQuery != null
}

sealed interface Screen {
    data object Browse : Screen
    data object Series : Screen
}

class ListingViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ComicsApp).repository
    private val _state = MutableStateFlow(ListingUiState())
    val state: StateFlow<ListingUiState> = _state.asStateFlow()

    private var listJob: Job? = null
    private var episodeJob: Job? = null

    init {
        refresh(force = true)
    }

    fun selectCatalog(catalog: Catalog) {
        if (
            catalog == _state.value.catalog &&
            _state.value.screen == Screen.Browse &&
            !_state.value.isSearch
        ) return
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
            )
        }
        runSearch(query, asRefresh = false)
    }

    fun refresh(force: Boolean = false) {
        val current = _state.value
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
            )
        }
        loadEpisodes(item, 1)
    }

    fun closeSeries() {
        episodeJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Browse,
                series = null,
                episodes = emptyList(),
                episodeError = null,
                showPageJump = false,
            )
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

    private fun humanError(prefix: String, e: Exception): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "$prefix\n$detail"
    }
}
