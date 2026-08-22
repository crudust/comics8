package com.comics8.core.source

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem

internal class StubComicSource(
    override val id: String,
    override val displayName: String = id,
    override val origin: String = "https://$id.example",
    override val catalogs: List<SourceCatalog> = listOf(
        SourceCatalog("LATEST", "최신", paginated = true),
    ),
    override val defaultPolicy: RequestPolicy = RequestPolicy(userAgent = "test"),
    override val searchPlaceholder: String = "제목 검색",
    override val notificationMode: NotificationMode = NotificationMode.NONE,
    override val progressDisplay: ProgressDisplay = ProgressDisplay.LAST_READ_ORDER,
    override val defaultLanguage: String? = null,
    override val hostApiLevel: Int = HostApi.LEVEL,
    private val ownedHost: (String) -> Boolean = { false },
    private val proxy: Boolean = true,
    private val fallbacks: (String) -> List<String> = { emptyList() },
    private val candidates: (List<ToonItem>) -> List<ToonItem> = { it },
    private val images: List<String> = emptyList(),
) : ComicSource {
    var language: String? = defaultLanguage
        private set

    override fun searchLanguage(): String? = language

    override fun applyConfig(config: SourceConfig) {
        config.language?.let { language = it.ifBlank { defaultLanguage } }
    }

    override fun ownsHost(host: String): Boolean {
        val key = host.lowercase().trim().trim('.')
        if (key.isEmpty()) return false
        return ownedHost(key)
    }

    override fun useProxy(url: String): Boolean = proxy

    override fun imageFallbacks(url: String): List<String> = fallbacks(url)

    override fun notificationCandidates(favorites: List<ToonItem>): List<ToonItem> =
        when (notificationMode) {
            NotificationMode.NONE -> emptyList()
            NotificationMode.LATEST_INTERSECTION,
            NotificationMode.PER_FAVORITE,
            -> candidates(favorites)
        }

    override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage =
        ListingPage(emptyList(), page, page)

    override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> = emptyList()

    override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage =
        EpisodePage(emptyList(), page, page)

    override suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp): List<String> =
        images
}

internal fun hostSuffixes(vararg suffixes: String): (String) -> Boolean = { host ->
    suffixes.any { host == it || host.endsWith(".$it") }
}
