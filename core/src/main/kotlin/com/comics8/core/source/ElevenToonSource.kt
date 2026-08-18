package com.comics8.core.source

import com.comics8.core.model.Catalog
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.parser.ComicSite
import com.comics8.core.parser.HtmlParsers
import com.comics8.core.parser.SearchParser

class ElevenToonSource : ComicSource {
    override val id: String = WorkId.DEFAULT_SOURCE
    override val displayName: String = "11toon"
    override val origin: String = ComicSite.ORIGIN
    override val catalogs: List<SourceCatalog> = REMOTE_CATALOGS.map { catalog ->
        SourceCatalog(id = catalog.name, label = catalog.label, paginated = catalog.paginated)
    }
    override val defaultPolicy: RequestPolicy = RequestPolicy(
        userAgent = ToonClient.USER_AGENT,
        referer = ComicSite.ORIGIN,
        extraHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
        ),
    )
    override val searchFirstClass: Boolean = true

    override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage {
        val html = http.fetchText(listingSpec(catalogId, page))
        return HtmlParsers.parseListing(html, origin).let(::stampSource)
    }

    override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> {
        val q = query.text.trim()
        if (q.isEmpty()) return emptyList()
        val json = http.fetchText(FetchSpec(ComicSite.searchUrl(q), defaultPolicy))
        return SearchParser.parse(json).map { it.copy(sourceId = id) }
    }

    override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage {
        val html = http.fetchText(FetchSpec(ComicSite.seriesUrl(item.id, item.title, page), defaultPolicy))
        return HtmlParsers.parseEpisodes(html, origin)
    }

    override suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp): List<String> =
        resolveImages(episode, http)

    override fun imageFallbacks(url: String): List<String> = ComicSite.getFallbackImageUrls(url)

    override fun coverUrl(toonId: String): String = ComicSite.coverUrl(toonId)

    fun resolveImages(episode: EpisodeItem, http: SourceHttp): List<String> {
        val html = http.fetchText(FetchSpec(episode.href, defaultPolicy))
        val candidateLists = HtmlParsers.parseAllImageCandidateLists(html, origin)
        if (candidateLists.isEmpty()) {
            val parsed = HtmlParsers.parseImages(html, origin)
            if (parsed.isEmpty()) {
                error("만화 이미지를 불러오지 못했습니다.")
            }
            return parsed
        }
        if (candidateLists.size == 1) {
            return candidateLists[0]
        }
        val primary = candidateLists[0]
        if (http.isAccessible(primary.firstOrNull().orEmpty(), defaultPolicy)) {
            return primary
        }
        for (i in 1 until candidateLists.size) {
            val candidate = candidateLists[i]
            if (http.isAccessible(candidate.firstOrNull().orEmpty(), defaultPolicy)) {
                return candidate
            }
        }
        return primary
    }

    private fun listingSpec(catalogId: String, page: Int): FetchSpec {
        val catalog = catalogFromId(catalogId)
        return FetchSpec(ComicSite.listingUrl(catalog, page), defaultPolicy)
    }

    private fun catalogFromId(catalogId: String): Catalog {
        val catalog = Catalog.entries.find { it.name.equals(catalogId, ignoreCase = true) }
            ?: error("Unknown catalog: $catalogId")
        if (catalog == Catalog.FAVORITE) {
            error("즐겨찾기는 로컬 목록입니다.")
        }
        return catalog
    }

    private fun stampSource(page: ListingPage): ListingPage =
        page.copy(items = page.items.map { it.copy(sourceId = id) })

    companion object {
        private val REMOTE_CATALOGS = listOf(
            Catalog.LATEST,
            Catalog.POPULAR,
            Catalog.COMPLETE,
            Catalog.TODAY,
        )
    }
}
