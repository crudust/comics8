package com.comics8.core.source.js

import com.comics8.core.model.ArtistRef
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.source.ComicSource
import com.comics8.core.source.NotificationMode
import com.comics8.core.source.ProgressDisplay
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SearchSuggestion
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceConfig
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class JsComicSource(
    private val engine: JsEngine,
    private val handle: JsSourceHandle,
) : ComicSource {
    override val id: String = handle.id
    override val displayName: String = handle.displayName
    override val origin: String = handle.origin
    override val catalogs: List<SourceCatalog> = handle.catalogs
    override val defaultPolicy: RequestPolicy = handle.defaultPolicy
    override val searchPlaceholder: String = handle.searchPlaceholder
    override val kind: SourceKind = SourceKind.REMOTE
    override val hostApiLevel: Int = handle.apiLevel
    override val notificationMode: NotificationMode = handle.notificationMode
    override val episodePageSize: Int = handle.episodePageSize
    override val emptyListingOk: Boolean = handle.emptyListingOk
    override val emptyEpisodesOk: Boolean = handle.emptyEpisodesOk
    override val defaultLanguage: String? = handle.defaultLanguage
    override val progressDisplay: ProgressDisplay = handle.progressDisplay

    override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage =
        runInterruptible(Dispatchers.IO) {
            engine.callListing(handle, http, catalogId, page)
        }

    override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> =
        runInterruptible(Dispatchers.IO) {
            engine.callSearch(handle, http, query)
        }

    override suspend fun suggest(query: SearchQuery, http: SourceHttp): List<SearchSuggestion> =
        runInterruptible(Dispatchers.IO) {
            engine.callSuggest(handle, http, query)
        }

    override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage =
        runInterruptible(Dispatchers.IO) {
            engine.callEpisodes(handle, http, item, page)
        }

    override suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp): List<String> =
        runInterruptible(Dispatchers.IO) {
            engine.callImages(handle, http, episode, item)
        }

    private val ownsHostCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val imageRefererCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val imageFallbacksCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val useProxyCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override fun imageFallbacks(url: String): List<String> {
        if (url.isBlank()) return emptyList()
        return imageFallbacksCache.computeIfAbsent(url) {
            engine.callStringList(handle, "imageFallbacks", it)
        }
    }

    override fun imageReferer(url: String): String {
        if (url.isBlank()) return defaultPolicy.referer ?: origin
        return imageRefererCache.computeIfAbsent(url) {
            engine.callString(handle, "imageReferer", it) ?: (defaultPolicy.referer ?: origin)
        }
    }

    override fun coverUrl(toonId: String): String? =
        engine.callString(handle, "coverUrl", toonId)

    override fun ownsHost(host: String): Boolean {
        val key = host.lowercase().trim().trim('.')
        if (key.isEmpty()) return false
        val query = handle.api.nestedHostQuery
        if (query != null) {
            return query.ownsHost[key] ?: (handle.originHost == key)
        }
        return ownsHostCache.computeIfAbsent(key) {
            engine.callBoolean(handle, "ownsHost", it, default = false)
        }
    }

    override fun useProxy(url: String): Boolean {
        if (url.isBlank()) return true
        val query = handle.api.nestedHostQuery
        if (query != null) {
            return query.useProxy[url] ?: true
        }
        return useProxyCache.computeIfAbsent(url) {
            engine.callBoolean(handle, "useProxy", it, default = true)
        }
    }

    override fun resolveParent(
        item: ToonItem,
        choice: ArtistRef,
        entryEpisodeId: String?,
    ): ToonItem? = engine.callResolveParent(handle, item, choice, entryEpisodeId)

    override fun supportsChapterNotifications(item: ToonItem): Boolean =
        engine.callSupportsChapterNotifications(
            handle,
            item,
            default = notificationMode != NotificationMode.NONE,
        )

    override fun notificationCandidates(favorites: List<ToonItem>): List<ToonItem> =
        engine.callNotificationCandidates(handle, favorites)

    override fun applyConfig(config: SourceConfig) {
        engine.applyConfig(handle, config)
    }

    companion object {
        fun fromScript(
            script: String,
            fileName: String = "<source.js>",
            engine: JsEngine = JsEngine(),
        ): JsComicSource = JsComicSource(engine, engine.load(script, fileName))
    }
}
