package com.comics8.core.app

import com.comics8.core.download.DownloadProgressState
import com.comics8.core.i18n.AppLanguage
import com.comics8.core.model.AppUpdateState
import com.comics8.core.model.BrowseTab
import com.comics8.core.model.CustomProxyConfig
import com.comics8.core.model.DownloadedToonSummary
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.model.EpisodeSortOrder
import com.comics8.core.model.NetworkSettings
import com.comics8.core.model.PageTapZone
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.SplitMode
import com.comics8.core.model.SyncState
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SearchSuggestion
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.core.storage.ReadHistoryRecord

data class ArtistPickRequest(
    val item: ToonItem,
    val entryEpisodeId: String? = item.entryEpisodeId,
)

data class AppUiState(
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
    val screen: AppScreen = AppScreen.Browse,
    val series: ToonItem? = null,
    val rawEpisodes: List<EpisodeItem> = emptyList(),
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
    val searchSuggestions: List<SearchSuggestion> = emptyList(),
    val seriesFavorited: Boolean = false,
    val currentEpisode: EpisodeItem? = null,
    val readerImages: List<String> = emptyList(),
    val imageAspectRatios: Map<Int, Float> = emptyMap(),
    val readerLoading: Boolean = false,
    val readerError: String? = null,
    val readerToastMessage: String? = null,
    val viewMode: ViewMode = ViewMode.AUTO,
    val viewportWidthDp: Int = 0,
    val viewportHeightDp: Int = 0,
    val readDirection: ReadDirection = ReadDirection.RIGHT_TO_LEFT,
    val splitMode: SplitMode = SplitMode.FIT,
    val seriesHistory: ReadHistoryRecord? = null,
    val historyItems: List<ReadHistoryRecord> = emptyList(),
    val readCounts: Map<String, Int> = emptyMap(),
    /** Counted totals keyed by work, so history rows can render a badge without guessing. */
    val catalogTotals: Map<String, EpisodeListingTotal> = emptyMap(),
    val historyLoading: Boolean = false,
    val selectedSettingsCategory: SettingsCategory? = null,
    val syncState: SyncState = SyncState(),
    val networkSettings: NetworkSettings = NetworkSettings(),
    val proxyTesting: Boolean = false,
    val proxyTestResult: String? = null,
    val proxyTestSuccess: Boolean? = null,
    val isFullscreen: Boolean = false,
    val showUpdateDialog: Boolean = false,
    val updateState: AppUpdateState = AppUpdateState(),
    val downloadSummaries: List<DownloadedToonSummary> = emptyList(),
    val downloadLoading: Boolean = false,
    val showDownloadOptions: Boolean = false,
    val downloadCatalog: List<EpisodeItem> = emptyList(),
    val downloadCatalogLoading: Boolean = false,
    val downloadedWrIds: Set<String> = emptySet(),
    val downloadProgress: DownloadProgressState = DownloadProgressState(),
    val sourceRegistry: SourceRegistry = SourceRegistry(),
    val showAddSourceSheet: Boolean = false,
    val libraryRoots: List<String> = emptyList(),
    val pendingLibraryTreePick: Boolean = false,
    val showRemoveSourceSheet: Boolean = false,
    val sourceError: String? = null,
    val packsReady: Boolean = true,
    val networkDraft: NetworkSourceConfig? = null,
    val networkTesting: Boolean = false,
    val networkTestMessage: String? = null,
    val networkTestSucceeded: Boolean = false,
    val notificationEnabled: Boolean = true,
    val notificationIntervalMinutes: Long = 60L,
    val notificationSources: Map<String, Boolean> = emptyMap(),
    val hasNotificationPermission: Boolean = true,
    val pageTapZone: PageTapZone = PageTapZone.FOLLOW_DIRECTION,
    val volumePageTurnEnabled: Boolean = false,
    val showViewerOptions: Boolean = false,
) {
    val screenWidthDp: Int get() = viewportWidthDp
    val screenHeightDp: Int get() = viewportHeightDp

    val isSearch: Boolean get() = searchQuery != null

    /** Mode that actually renders the reader; [ViewMode.AUTO] resolves from viewport and page shapes. */
    val effectiveViewMode: ViewMode
        get() = ReaderDomain.resolveEffectiveViewMode(viewMode, viewportWidthDp, viewportHeightDp, imageAspectRatios)

    val writesDownloads: Boolean
        get() {
            val id = series?.sourceId ?: activeSourceId ?: return false
            return sourceRegistry.getOrNull(id)?.writesDownloads == true
        }

    val currentEpisodeIndex: Int
        get() = currentEpisode?.let { ep -> (episodes.ifEmpty { rawEpisodes }).indexOfFirst { it.wrId == ep.wrId } } ?: -1

    val hasNextEpisode: Boolean
        get() = ReaderDomain.nextEpisode(currentEpisodeIndex, (episodes.ifEmpty { rawEpisodes }).size, episodePage, episodeSortOrder) !=
            ReaderDomain.EpisodeNavigation.None

    val hasPrevEpisode: Boolean
        get() = ReaderDomain.prevEpisode(currentEpisodeIndex, (episodes.ifEmpty { rawEpisodes }).size, episodePage, episodeLastPage, episodeSortOrder) !=
            ReaderDomain.EpisodeNavigation.None

    fun progressLabel(history: ReadHistoryRecord): String =
        sourceRegistry.formatReadProgress(
            history.sourceId,
            history.lastReadOrder,
            catalogTotals[history.workId().storageKey()],
            readCounts[history.workId().storageKey()] ?: 0,
        )
}
