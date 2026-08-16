package com.comics8.monitor.data

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
}
