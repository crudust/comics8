package com.comics8.desktop.ui

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodeSortOrder
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.desktop.data.DesktopBackupStats
import com.comics8.desktop.data.DesktopSyncState
import com.comics8.desktop.data.ReadHistoryRecord

import com.comics8.core.i18n.AppLanguage
import com.comics8.desktop.ui.settings.SettingsCategory

enum class Screen {
    Browse,
    Series,
    Reader,
    History,
    Downloads,
    Settings,
    SourceManager,
}

data class ArtistPickRequest(
    val item: ToonItem,
    val entryEpisodeId: String? = item.entryEpisodeId,
)

data class DesktopUiState(
    val appLanguage: AppLanguage = AppLanguage.AUTO,
    val tab: BrowseTab? = null,
    val browseTabs: List<BrowseTab> = emptyList(),
    val activeSourceId: String? = null,
    val installedSources: List<ComicSource> = emptyList(),
    val artistPick: ArtistPickRequest? = null,
    val highlightedEpisodeId: String? = null,
    val page: Int = 1,
    val lastPage: Int = 1,
    val items: List<ToonItem> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val refreshEpoch: Long = 0L,
    val scrollToTopTrigger: Int = 0,
    val error: String? = null,
    val screen: Screen = Screen.Browse,
    val series: ToonItem? = null,
    val episodes: List<EpisodeItem> = emptyList(),
    val episodeSortOrder: EpisodeSortOrder = EpisodeSortOrder.DEFAULT,
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
    val splitMode: SplitMode = SplitMode.FIT,
    val seriesHistory: ReadHistoryRecord? = null,
    val historyItems: List<ReadHistoryRecord> = emptyList(),
    val readCounts: Map<String, Int> = emptyMap(),
    val historyLoading: Boolean = false,
    val selectedSettingsCategory: SettingsCategory? = null,
    val showSyncDialog: Boolean = false,
    val syncState: DesktopSyncState = DesktopSyncState(),
    val networkSettings: com.comics8.core.model.NetworkSettings = com.comics8.core.model.NetworkSettings(),
    val proxyTesting: Boolean = false,
    val proxyTestResult: String? = null,
    val proxyTestSuccess: Boolean? = null,
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
    val sourceRegistry: SourceRegistry = SourceRegistry(),
    val showAddSourceSheet: Boolean = false,
    val libraryRoots: List<String> = emptyList(),
    val showRemoveSourceSheet: Boolean = false,
    val sourceError: String? = null,
    val packsReady: Boolean = false,
    val networkDraft: NetworkSourceConfig? = null,
    val networkTesting: Boolean = false,
    val networkTestMessage: String? = null,
    val networkTestSucceeded: Boolean = false,
) {
    val isSearch: Boolean get() = searchQuery != null

    val writesDownloads: Boolean
        get() {
            val id = series?.sourceId ?: activeSourceId ?: return false
            return sourceRegistry.getOrNull(id)?.writesDownloads == true
        }

    val currentEpisodeIndex: Int
        get() = currentEpisode?.let { ep -> episodes.indexOfFirst { it.wrId == ep.wrId } } ?: -1

    val hasNextEpisode: Boolean
        get() = ReaderDomain.newerEpisode(currentEpisodeIndex, episodes.size, episodePage) !=
            ReaderDomain.EpisodeNavigation.None

    val hasPrevEpisode: Boolean
        get() = ReaderDomain.olderEpisode(currentEpisodeIndex, episodes.size, episodePage, episodeLastPage) !=
            ReaderDomain.EpisodeNavigation.None

    fun progressLabel(history: ReadHistoryRecord): String =
        sourceRegistry.formatReadProgress(
            history.sourceId,
            history.lastReadOrder,
            history.totalEpisodes,
            readCounts[history.workId().storageKey()] ?: 0,
        )
}
