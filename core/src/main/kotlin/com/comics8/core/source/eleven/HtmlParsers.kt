package com.comics8.core.source.eleven

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object HtmlParsers {
    private val dateRegex = Regex("""(\d{2}\.\d{2})(?:\((\d+)\))?""")
    private val fullDateRegex = Regex("""(\d{2}\.\d{2}\.\d{2})""")
    private val bgUrlRegex = Regex("""url\(\s*(['"]?)(.+?)\1\s*\)""", RegexOption.IGNORE_CASE)
    private val wrIdRegex = Regex("""wr_id=(\d+)""")
    private val pageRegex = Regex("""(?:\?|&|&amp;)page=(\d+)""")

    fun parseListing(html: String, baseUri: String = ComicSite.ORIGIN): ListingPage {
        val doc = Jsoup.parse(html, baseUri)
        val items = doc.select("li[data-id]").mapNotNull { parseToon(it, baseUri) }
            .distinctBy { it.id }

        val current = doc.selectFirst("strong.pg_current")?.text()?.trim()?.toIntOrNull() ?: 1
        val lastFromEnd = doc.selectFirst("a.pg_end")?.attr("abs:href")?.let(::pageFromHref)
        val lastFromPages = doc.select("a.pg_page").mapNotNull { pageFromHref(it.attr("abs:href")) }.maxOrNull()
        val lastPage = listOfNotNull(lastFromEnd, lastFromPages, current).max()

        return ListingPage(
            items = items,
            currentPage = current,
            lastPage = lastPage,
        )
    }

    fun parseEpisodes(html: String, baseUri: String = ComicSite.ORIGIN): EpisodePage {
        val doc = Jsoup.parse(html, baseUri)
        val items = doc.select("button.episode.is-series").mapNotNull { parseEpisode(it, baseUri) }
            .distinctBy { it.wrId }

        val current = doc.selectFirst("strong.pg_current")?.text()?.trim()?.toIntOrNull() ?: 1
        val lastFromEnd = doc.selectFirst("a.pg_end")?.attr("abs:href")?.let(::pageFromHref)
        val lastFromPages = doc.select("a.pg_page").mapNotNull { pageFromHref(it.attr("abs:href")) }.maxOrNull()
        val lastPage = listOfNotNull(lastFromEnd, lastFromPages, current).max()

        return EpisodePage(items = items, currentPage = current, lastPage = lastPage)
    }

    private val jsImgArrayRegex = Regex(
        """(?:var|let|const)\s+(?:img_list|img_list_2|data_list|arr_img|images)\s*=\s*\[(.*?)\]""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val jsStringUrlRegex = Regex(
        """["']([^"']+\.(?:jpg|jpeg|png|webp|gif)(?:\?[^"']*)?)["']""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses all JavaScript image array definitions (e.g. img_list, img_list_2).
     * Returns a list of candidate image URL lists for failover verification.
     */
    fun parseAllImageCandidateLists(html: String, baseUri: String = ComicSite.ORIGIN): List<List<String>> {
        val result = mutableListOf<List<String>>()
        for (match in jsImgArrayRegex.findAll(html)) {
            val arrayBody = match.groupValues[1]
            val urls = LinkedHashSet<String>()
            for (urlMatch in jsStringUrlRegex.findAll(arrayBody)) {
                val rawUrl = urlMatch.groupValues[1].trim()
                if (rawUrl.isNotBlank() && isImageUrl(rawUrl)) {
                    urls.add(ComicSite.absUrl(rawUrl, baseUri))
                }
            }
            if (urls.isNotEmpty()) {
                result.add(urls.toList())
            }
        }
        return result
    }

    /**
     * Parses an episode page and extracts the comic image URLs.
     * Handles JavaScript array definitions (var img_list = [...]), standard <img> tags,
     * and CSS background‑image styles.
     */
    fun parseImages(html: String, baseUri: String = ComicSite.ORIGIN): List<String> {
        val candidateLists = parseAllImageCandidateLists(html, baseUri)
        if (candidateLists.isNotEmpty()) {
            return candidateLists[0]
        }

        val urls = LinkedHashSet<String>()

        // 2. Check DOM elements in viewer containers
        val doc = Jsoup.parse(html, baseUri)
        val viewerContainer = doc.selectFirst("#scroll-list, .view-content, .scroll-viewer, #page-list, #contents-wrapper")
        val searchRoot = viewerContainer ?: doc

        searchRoot.select("img").forEach { img ->
            val url = when {
                img.hasAttr("data-src") -> img.attr("abs:data-src")
                img.hasAttr("data-original") -> img.attr("abs:data-original")
                img.hasAttr("data-lazy") -> img.attr("abs:data-lazy")
                else -> img.attr("abs:src")
            }
            if (url.isNotBlank() && isComicImageUrl(url)) {
                urls.add(url)
            }
        }

        searchRoot.select("[style*='background']").forEach { el ->
            val bg = extractBackgroundUrl(el.attr("style")) ?: return@forEach
            val absBg = ComicSite.absUrl(bg, baseUri)
            if (isComicImageUrl(absBg)) {
                urls.add(absBg)
            }
        }

        return urls.toList()
    }

    internal fun extractBackgroundUrl(style: String?): String? {
        if (style.isNullOrBlank()) return null
        return bgUrlRegex.find(style)?.groupValues?.get(2)?.trim()?.trim('\'', '"')
    }

    internal fun pageFromHref(href: String?): Int? {
        if (href.isNullOrBlank()) return null
        return pageRegex.find(href)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseToon(li: Element, baseUri: String): ToonItem? {
        val id = li.attr("data-id").trim()
        if (id.isEmpty()) return null
        val title = li.selectFirst(".homelist-title")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        val thumbEl = li.selectFirst(".homelist-thumb")
        val rawThumb = thumbEl?.attr("data-mobile-image")?.ifBlank { null }
            ?: extractBackgroundUrl(thumbEl?.attr("style"))
            ?: ComicSite.coverUrl(id)
        val thumbUrl = ComicSite.absUrl(rawThumb, baseUri)

        val genreRaw = li.selectFirst(".homelist-genre")?.text()?.replace('\u00a0', ' ')?.trim().orEmpty()
        val dateMatch = dateRegex.find(genreRaw)
        val updatedAt = dateMatch?.groupValues?.get(1)
        val genre = if (dateMatch != null) {
            genreRaw.replace(dateMatch.value, "").trim(' ', ',', '·', '-')
        } else {
            genreRaw
        }

        val href = li.selectFirst("a[href]")?.absUrl("href")
            ?.ifBlank { ComicSite.seriesUrl(id, title) }
            ?: ComicSite.seriesUrl(id, title)

        val rankingEl = li.selectFirst(".homelist-ranking")
        val ranking = rankingEl?.ownText()?.filter { it.isDigit() }?.ifBlank { null }
            ?: rankingEl?.text()?.filter { it.isDigit() }?.ifBlank { null }

        return ToonItem(
            id = id,
            title = title,
            thumbUrl = thumbUrl,
            href = href,
            genre = genre,
            updatedAt = updatedAt,
            ranking = ranking,
        )
    }

    private fun parseEpisode(button: Element, baseUri: String): EpisodeItem? {
        val onclick = button.attr("onclick")
        val wrId = wrIdRegex.find(onclick)?.groupValues?.get(1)
            ?: button.attr("data-episode-id").ifBlank { null }
            ?: return null
        val title = button.selectFirst(".episode-title")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val dateText = button.selectFirst(".free-date")?.text()?.replace('\u00a0', ' ').orEmpty()
        val date = fullDateRegex.find(dateText)?.groupValues?.get(1)
            ?: dateRegex.find(dateText)?.groupValues?.get(1)
        val rawThumb = extractBackgroundUrl(button.selectFirst(".episode-banner")?.attr("style"))
        val hrefMatch = Regex("""location\.href\s*=\s*[`'"]([^`'"]+)[`'"]""").find(onclick)?.groupValues?.get(1)
        val href = hrefMatch?.let { raw ->
            val cleaned = raw.removePrefix("./")
            val path = if (cleaned.startsWith("board.php")) "/bbs/$cleaned" else cleaned
            ComicSite.absUrl(path, baseUri)
        } ?: ComicSite.absUrl("/bbs/board.php?bo_table=toons&wr_id=$wrId", baseUri)
        return EpisodeItem(
            wrId = wrId,
            title = title,
            date = date,
            thumbUrl = rawThumb?.let { ComicSite.absUrl(it, baseUri) },
            href = href,
        )
    }

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".webp") ||
            lower.contains(".gif")
    }

    private fun isComicImageUrl(url: String): Boolean {
        if (!isImageUrl(url)) return false
        val lower = url.lowercase()
        val ignoreKeywords = listOf("icon-", "favicon", "192x192", "/toonfile/toonres/", "logo", "banner", "avatar")
        return ignoreKeywords.none { lower.contains(it) }
    }
}
