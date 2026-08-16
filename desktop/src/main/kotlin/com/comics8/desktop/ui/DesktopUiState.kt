package com.comics8.desktop.ui

import com.comics8.desktop.data.Catalog
import com.comics8.desktop.data.DesktopBackupStats
import com.comics8.desktop.data.DesktopSyncState
import com.comics8.desktop.data.EpisodeItem
import com.comics8.desktop.data.ReadDirection
import com.comics8.desktop.data.ReadHistoryRecord
import com.comics8.desktop.data.ToonItem
import com.comics8.desktop.data.ViewMode

enum class Screen {
    Browse,
    Series,
    Reader,
    History,
}

data class DesktopUiState(
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
    val seriesHistory: ReadHistoryRecord? = null,
    val historyItems: List<ReadHistoryRecord> = emptyList(),
    val historyLoading: Boolean = false,
    val showSyncDialog: Boolean = false,
    val syncState: DesktopSyncState = DesktopSyncState(),
    val isFullscreen: Boolean = false,
) {
    val isSearch: Boolean get() = searchQuery != null

    val currentEpisodeIndex: Int
        get() = currentEpisode?.let { ep -> episodes.indexOfFirst { it.wrId == ep.wrId } } ?: -1

    val hasNextEpisode: Boolean
        get() = (currentEpisodeIndex in 1 until episodes.size) || (currentEpisodeIndex == 0 && episodePage > 1)

    val hasPrevEpisode: Boolean
        get() = (currentEpisodeIndex in 0 until (episodes.size - 1)) || (currentEpisodeIndex == episodes.size - 1 && episodePage < episodeLastPage)
}
