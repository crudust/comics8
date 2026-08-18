package com.comics8.core.source

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
    val searchFirstClass: Boolean

    suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage
    suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem>
    suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage
    suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp): List<String>

    fun imageFallbacks(url: String): List<String> = emptyList()
    fun coverUrl(toonId: String): String? = null
    fun supportsChapterNotifications(item: ToonItem): Boolean = true
}
