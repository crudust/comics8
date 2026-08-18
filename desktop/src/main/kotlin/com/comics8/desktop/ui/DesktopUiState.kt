package com.comics8.desktop.ui

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.source.WorkId
import com.comics8.desktop.data.DesktopBackupStats
import com.comics8.desktop.data.DesktopSyncState
import com.comics8.desktop.data.ReadHistoryRecord

enum class Screen {
    Browse,
    Series,
    Reader,
    History,
    Downloads,
}

data class ArtistPickRequest(
    val item: ToonItem,
    val entryEpisodeId: String? = item.entryEpisodeId,
)

data class DesktopUiState(
    val tab: BrowseTab = BrowseTab.Remote(
        WorkId.DEFAULT_SOURCE,
        com.comics8.core.source.SourceCatalog("LATEST", "최신", paginated = true),
    ),
    val browseTabs: List<BrowseTab> = emptyList(),
    val activeSourceId: String = WorkId.DEFAULT_SOURCE,
    val artistPick: ArtistPickRequest? = null,
    val highlightedEpisodeId: String? = null,
    val page: Int = 1,
    val lastPage: Int = 1,
    val items: List<ToonItem> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val scrollToTopTrigger: Int = 0,
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
    val searchSuggestions: List<com.comics8.core.source.SearchSuggestion> = emptyList(),
    val seriesFavorited: Boolean = false,
    val currentEpisode: EpisodeItem? = null,
    val readerImages: List<String> = emptyList(),
    val imageAspectRatios: Map<Int, Float> = emptyMap(),
    val readerLoading: Boolean = false,
    val readerError: String? = null,
    val viewMode: ViewMode = ViewMode.DUAL,
    val readDirection: ReadDirection = ReadDirection.RIGHT_TO_LEFT,
    val seriesHistory: ReadHistoryRecord? = null,
    val historyItems: List<ReadHistoryRecord> = emptyList(),
    val historyLoading: Boolean = false,
    val showSyncDialog: Boolean = false,
    val syncState: DesktopSyncState = DesktopSyncState(),
    val isFullscreen: Boolean = false,
    val showUpdateDialog: Boolean = false,
    val updateState: com.comics8.core.model.AppUpdateState = com.comics8.core.model.AppUpdateState(),
    val downloadSummaries: List<com.comics8.core.model.DownloadedToonSummary> = emptyList(),
    val downloadLoading: Boolean = false,
    val showDownloadOptions: Boolean = false,
    val downloadCatalog: List<EpisodeItem> = emptyList(),
    val downloadCatalogLoading: Boolean = false,
    val downloadedWrIds: Set<String> = emptySet(),
    val downloadProgress: com.comics8.desktop.data.DesktopDownloadProgressState = com.comics8.desktop.data.DesktopDownloadProgressState(),
) {
    val isSearch: Boolean get() = searchQuery != null

    val currentEpisodeIndex: Int
        get() = currentEpisode?.let { ep -> episodes.indexOfFirst { it.wrId == ep.wrId } } ?: -1

    val hasNextEpisode: Boolean
        get() = (currentEpisodeIndex in 1 until episodes.size) || (currentEpisodeIndex == 0 && episodePage > 1)

    val hasPrevEpisode: Boolean
        get() = (currentEpisodeIndex in 0 until (episodes.size - 1)) || (currentEpisodeIndex == episodes.size - 1 && episodePage < episodeLastPage)
}
