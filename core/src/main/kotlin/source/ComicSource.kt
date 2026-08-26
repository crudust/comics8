package com.comics8.core.source

import com.comics8.core.model.ArtistRef
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem

interface ComicSource {
    val id: String
    val displayName: String
    val origin: String
    val catalogs: List<SourceCatalog>
    val defaultPolicy: RequestPolicy
    val searchPlaceholder: String get() = "제목 검색"

    val enabledByDefault: Boolean get() = false

    val kind: SourceKind get() = SourceKind.REMOTE
    val hostApiLevel: Int get() = HostApi.LEVEL
    val syncParticipates: Boolean get() = kind == SourceKind.REMOTE
    val writesDownloads: Boolean get() = kind == SourceKind.REMOTE
    val requiresHttp: Boolean get() = kind == SourceKind.REMOTE

    val notificationMode: NotificationMode get() = NotificationMode.NONE
    val episodePageSize: Int get() = 100
    val emptyListingOk: Boolean get() = kind == SourceKind.LOCAL
    val emptyEpisodesOk: Boolean get() = false
    val defaultLanguage: String? get() = null
    val favoriteUsesLatestListing: Boolean
        get() = notificationMode == NotificationMode.LATEST_INTERSECTION
    val defaultProgressDisplayMode: com.comics8.core.model.ProgressDisplayMode
        get() = when (progressDisplay) {
            ProgressDisplay.READ_COUNT -> com.comics8.core.model.ProgressDisplayMode.READ_COUNT
            else -> com.comics8.core.model.ProgressDisplayMode.LATEST_EPISODE
        }

    val progressDisplay: ProgressDisplay get() = when (defaultProgressDisplayMode) {
        com.comics8.core.model.ProgressDisplayMode.READ_COUNT -> ProgressDisplay.READ_COUNT
        else -> ProgressDisplay.LAST_READ_ORDER
    }

    fun formatReadProgress(
        lastReadOrder: Int,
        totalEpisodes: Int,
        readCount: Int,
        mode: com.comics8.core.model.ProgressDisplayMode = defaultProgressDisplayMode,
    ): String = mode.format(lastReadOrder, totalEpisodes, readCount).orEmpty()

    suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage
    suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem>
    suspend fun suggest(query: SearchQuery, http: SourceHttp): List<SearchSuggestion> = emptyList()
    suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage
    suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp): List<String>

    fun imageFallbacks(url: String): List<String> = emptyList()
    fun imageReferer(url: String): String = defaultPolicy.referer ?: origin
    fun coverUrl(toonId: String): String? = null
    fun supportsChapterNotifications(item: ToonItem): Boolean =
        notificationMode != NotificationMode.NONE

    fun ownsHost(host: String): Boolean = false
    fun useProxy(url: String): Boolean = kind == SourceKind.REMOTE

    fun resolveParent(
        item: ToonItem,
        choice: ArtistRef,
        entryEpisodeId: String? = item.entryEpisodeId,
    ): ToonItem? = null

    fun notificationCandidates(favorites: List<ToonItem>): List<ToonItem> =
        when (notificationMode) {
            NotificationMode.NONE -> emptyList()
            NotificationMode.LATEST_INTERSECTION,
            NotificationMode.PER_FAVORITE,
            -> favorites
        }

    fun applyConfig(config: SourceConfig) {}

    fun searchLanguage(): String? = null
}
