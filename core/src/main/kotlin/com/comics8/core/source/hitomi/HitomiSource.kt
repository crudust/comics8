package com.comics8.core.source.hitomi

import com.comics8.core.model.ArtistRef
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.ComicSource
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class HitomiSource(
    language: String = HitomiUrls.DEFAULT_LANGUAGE,
    private val delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : ComicSource {
    @Volatile
    var language: String = language
    @Volatile
    private var ggCache: CachedGg? = null

    override val id: String = HitomiUrls.SOURCE_ID
    override val displayName: String = "Hitomi"
    override val origin: String = HitomiUrls.ORIGIN
    override val catalogs: List<SourceCatalog> = CATALOGS
    override val defaultPolicy: RequestPolicy = RequestPolicy(
        userAgent = ToonClient.USER_AGENT,
        referer = HitomiUrls.REFERER,
        extraHeaders = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
        ),
    )
    override val searchFirstClass: Boolean = true

    override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage {
        val p = page.coerceAtLeast(1)
        val result = fetchNozomiPage(listingNozomi(catalogId), p, http)
        val ids = NozomiIndex.parseIds(result.body)
        val lastPage = NozomiIndex.lastPage(p, ids, result.totalLength())
        val items = loadGalleryCards(ids, http)
        return ListingPage(items = items, currentPage = p, lastPage = lastPage)
    }

    override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> {
        val tokens = HitomiSearch.parse(query.text) ?: return emptyList()
        if (tokens.isEmpty() && query.language.isNullOrBlank() && query.type.isNullOrBlank()) {
            return emptyList()
        }
        var lang = language
        if (!query.language.isNullOrBlank()) {
            lang = query.language
        }
        tokens.find { it.ns == "language" }?.let { lang = ArtistSlug.normalize(it.value) }
        val filters = tokens.filter { it.ns != "language" }.toMutableList()
        if (!query.type.isNullOrBlank() && filters.none { it.ns == "type" }) {
            filters += SearchToken("type", query.type)
        }
        val urls = if (filters.isEmpty()) {
            listOf(HitomiUrls.indexNozomi(lang))
        } else {
            filters.map { HitomiSearch.nozomiUrl(it, lang) }
        }
        return loadGalleryCards(collectSearchIds(urls, http), http)
    }

    override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage {
        val p = page.coerceAtLeast(1)
        val artistSlug = artistSlugOf(item.id)
        if (artistSlug != null) {
            val result = fetchNozomiPage(HitomiUrls.nsNozomi("artist", artistSlug, language), p, http)
            val ids = NozomiIndex.parseIds(result.body)
            val lastPage = NozomiIndex.lastPage(p, ids, result.totalLength())
            val episodes = loadGalleryInfos(ids, http).map { it.toEpisodeItem() }
            return EpisodePage(items = episodes, currentPage = p, lastPage = lastPage)
        }
        val galleryId = galleryIdOf(item) ?: return EpisodePage(emptyList(), p, 1)
        val info = fetchGalleryInfo(galleryId, http) ?: return EpisodePage(emptyList(), p, 1)
        if (info.type.equals("anime", ignoreCase = true)) {
            return EpisodePage(emptyList(), p, 1)
        }
        return EpisodePage(items = listOf(info.toEpisodeItem()), currentPage = p, lastPage = 1)
    }

    override suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp): List<String> {
        val info = fetchGalleryInfo(episode.wrId, http)
            ?: error("만화 이미지를 불러오지 못했습니다.")
        val gg = loadGg(http)
        if (info.files.isEmpty()) {
            error("만화 이미지를 불러오지 못했습니다.")
        }
        return info.files.map { file ->
            val ext = when {
                file.hasWebp -> "webp"
                file.hasAvif -> "avif"
                else -> "webp"
            }
            HitomiUrls.imageUrl(file.hash, gg.b, gg.subdomainIndex(file.hash), ext)
        }
    }

    override fun imageFallbacks(url: String): List<String> =
        listOfNotNull(HitomiUrls.altImageUrl(url))

    override fun supportsChapterNotifications(item: ToonItem): Boolean = false

    private fun listingNozomi(catalogId: String): String {
        val lang = language
        return when (catalogId.uppercase()) {
            "LATEST", "INDEX" -> HitomiUrls.indexNozomi(lang)
            "POPULAR" -> HitomiUrls.popularNozomi("week", lang)
            "TODAY" -> HitomiUrls.popularNozomi("today", lang)
            "MONTH" -> HitomiUrls.popularNozomi("month", lang)
            "YEAR" -> HitomiUrls.popularNozomi("year", lang)
            "DOUJINSHI" -> HitomiUrls.typeNozomi("doujinshi", lang)
            "MANGA" -> HitomiUrls.typeNozomi("manga", lang)
            "ARTISTCG" -> HitomiUrls.typeNozomi("artistcg", lang)
            "GAMECG" -> HitomiUrls.typeNozomi("gamecg", lang)
            "IMAGESET" -> HitomiUrls.typeNozomi("imageset", lang)
            else -> error("Unknown catalog: $catalogId")
        }
    }

    private fun nozomiSpec(url: String, page: Int): FetchSpec =
        FetchSpec(
            url = url,
            policy = defaultPolicy,
            headers = mapOf("Range" to HitomiUrls.rangeHeader(page)),
        )

    private suspend fun fetchNozomiPage(url: String, page: Int, http: SourceHttp): HttpResult {
        val result = fetchWithBackoff(http, nozomiSpec(url, page))
            ?: error("HTTP nozomi failed")
        if (result.code !in 200..299) {
            error("HTTP ${result.code}")
        }
        return result
    }

    private suspend fun collectSearchIds(urls: List<String>, http: SourceHttp): List<Long> {
        if (urls.isEmpty()) return emptyList()
        val cursors = urls.map { NozomiCursor(it) }
        val out = ArrayList<Long>(NozomiIndex.PAGE)
        while (out.size < NozomiIndex.PAGE) {
            val heads = cursors.map { it.peek(http) }
            if (heads.any { it == null }) break
            val present = heads.filterNotNull()
            val target = present.minOrNull() ?: break
            if (present.all { it == target }) {
                out += target
                cursors.forEach { it.pop() }
            } else {
                for (cursor in cursors) {
                    val head = cursor.peek(http) ?: return out
                    if (head > target) cursor.pop()
                }
            }
        }
        return out
    }

    private inner class NozomiCursor(private val url: String) {
        private var page = 1
        private var lastPage = Int.MAX_VALUE
        private val buffer = ArrayDeque<Long>()

        suspend fun peek(http: SourceHttp): Long? {
            while (buffer.isEmpty() && page <= lastPage) {
                val result = fetchNozomiPage(url, page, http)
                val ids = NozomiIndex.parseIds(result.body)
                lastPage = NozomiIndex.lastPage(page, ids, result.totalLength())
                buffer.addAll(ids)
                page += 1
                if (ids.isEmpty()) break
            }
            return buffer.firstOrNull()
        }

        fun pop() {
            if (buffer.isNotEmpty()) buffer.removeFirst()
        }
    }

    private suspend fun loadGalleryInfos(ids: List<Long>, http: SourceHttp): List<GalleryInfo> {
        if (ids.isEmpty()) return emptyList()
        val semaphore = Semaphore(GALLERY_CONCURRENCY)
        return coroutineScope {
            ids.map { id ->
                async {
                    semaphore.withPermit {
                        val info = fetchGalleryInfo(id.toString(), http) ?: return@withPermit null
                        if (info.type.equals("anime", ignoreCase = true)) null else info
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun loadGalleryCards(ids: List<Long>, http: SourceHttp): List<ToonItem> =
        loadGalleryInfos(ids, http).mapNotNull(::toListingItem)

    private suspend fun loadGg(http: SourceHttp): GgJsRouter {
        val now = System.currentTimeMillis()
        ggCache?.let { cached ->
            if (now - cached.loadedAt < GG_TTL_MS && cached.router.b.isNotBlank()) {
                return cached.router
            }
        }
        val result = fetchWithBackoff(http, FetchSpec(HitomiUrls.ggJs(), defaultPolicy))
            ?: error("만화 이미지를 불러오지 못했습니다.")
        val router = GgJsRouter.parse(result.body.toString(Charsets.UTF_8))
        if (router.b.isBlank()) {
            error("만화 이미지를 불러오지 못했습니다.")
        }
        ggCache = CachedGg(now, router)
        return router
    }

    private suspend fun fetchGalleryInfo(id: String, http: SourceHttp): GalleryInfo? {
        val spec = FetchSpec(HitomiUrls.galleryJs(id), defaultPolicy)
        val result = fetchWithBackoff(http, spec) ?: return null
        if (result.code !in 200..299) return null
        return GalleryInfoParser.parse(result.body.toString(Charsets.UTF_8))
    }

    private suspend fun fetchWithBackoff(http: SourceHttp, spec: FetchSpec): HttpResult? {
        val delays = BACKOFF_MS
        for (attempt in 0..delays.size) {
            val result = try {
                http.fetch(spec)
            } catch (_: Exception) {
                if (attempt == delays.size) return null
                delayMillis(delays[attempt])
                continue
            }
            if (result.code in 200..299) return result
            if (result.code == 429 || result.code == 503) {
                if (attempt == delays.size) return null
                delayMillis(delays[attempt])
                continue
            }
            return null
        }
        return null
    }

    private fun toListingItem(info: GalleryInfo): ToonItem? {
        if (info.type.equals("anime", ignoreCase = true)) return null
        val artists = info.artists
        val (toonId, choices, title) = when {
            artists.isEmpty() -> Triple("gallery:${info.id}", emptyList<ArtistRef>(), info.title)
            artists.size == 1 -> Triple("artist:${artists[0].slug}", emptyList<ArtistRef>(), artists[0].displayName)
            else -> Triple("gallery:${info.id}", artists, info.title)
        }
        return ToonItem(
            id = toonId,
            title = title,
            thumbUrl = info.files.firstOrNull()?.let { HitomiUrls.thumbUrl(it.hash) }.orEmpty(),
            href = HitomiUrls.galleryPage(info.id),
            genre = info.type,
            updatedAt = info.updatedAt,
            sourceId = id,
            entryEpisodeId = info.id,
            artistChoices = choices,
        )
    }

    private fun GalleryInfo.toEpisodeItem(): EpisodeItem = EpisodeItem(
        wrId = id,
        title = title,
        date = updatedAt,
        thumbUrl = files.firstOrNull()?.let { HitomiUrls.thumbUrl(it.hash) },
        href = HitomiUrls.reader(id),
        artistChoices = if (artists.size >= 2) artists else emptyList(),
    )

    private fun artistSlugOf(toonId: String): String? =
        if (toonId.startsWith(ARTIST_PREFIX)) toonId.removePrefix(ARTIST_PREFIX).ifBlank { null } else null

    private fun galleryIdOf(item: ToonItem): String? {
        item.entryEpisodeId?.takeIf { it.isNotBlank() }?.let { return it }
        val toonId = item.id
        if (toonId.startsWith(GALLERY_PREFIX)) {
            return toonId.removePrefix(GALLERY_PREFIX).ifBlank { null }
        }
        return toonId.takeIf { it.all(Char::isDigit) }
    }

    companion object {
        const val ID = HitomiUrls.SOURCE_ID
        const val ENABLED_PREF_KEY = HitomiUrls.ENABLED_PREF_KEY
        const val ENABLED_BY_DEFAULT = HitomiUrls.ENABLED_BY_DEFAULT
        const val LANGUAGE_PREF_KEY = HitomiUrls.LANGUAGE_PREF_KEY
        const val DEFAULT_LANGUAGE = HitomiUrls.DEFAULT_LANGUAGE
        const val ACTIVE_SOURCE_PREF_KEY = HitomiUrls.ACTIVE_SOURCE_PREF_KEY
        const val ARTIST_PREFIX = "artist:"
        const val GALLERY_PREFIX = "gallery:"

        fun artistSeriesItem(
            from: ToonItem,
            artist: ArtistRef,
            entryEpisodeId: String? = from.entryEpisodeId,
        ): ToonItem = ToonItem(
            id = "$ARTIST_PREFIX${artist.slug}",
            title = artist.displayName,
            thumbUrl = from.thumbUrl,
            href = HitomiUrls.artistPage(artist.slug),
            genre = from.genre,
            updatedAt = from.updatedAt,
            sourceId = ID,
            entryEpisodeId = entryEpisodeId,
        )

        private const val GALLERY_CONCURRENCY = 4
        private const val GG_TTL_MS = 10 * 60 * 1000L
        private val BACKOFF_MS = listOf(250L, 1_000L, 4_000L)

        private data class CachedGg(val loadedAt: Long, val router: GgJsRouter)

        private val CATALOGS = listOf(
            SourceCatalog("LATEST", "최신", paginated = true),
            SourceCatalog("POPULAR", "인기", paginated = true),
            SourceCatalog("TODAY", "오늘", paginated = true),
            SourceCatalog("MONTH", "월간", paginated = true),
            SourceCatalog("YEAR", "연간", paginated = true),
            SourceCatalog("DOUJINSHI", "동인지", paginated = true),
            SourceCatalog("MANGA", "망가", paginated = true),
            SourceCatalog("ARTISTCG", "아티스트 CG", paginated = true),
            SourceCatalog("GAMECG", "게임 CG", paginated = true),
            SourceCatalog("IMAGESET", "이미지셋", paginated = true),
        )
    }
}
