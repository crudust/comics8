package com.comics8.monitor.data

import com.comics8.monitor.data.local.SeenDao
import com.comics8.monitor.data.local.SeenToon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ToonRepository(
    private val client: ToonClient,
    private val seenDao: SeenDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun loadListing(catalog: Catalog, page: Int): ListingPage = withContext(Dispatchers.IO) {
        val html = client.fetch(ComicSite.listingUrl(catalog, page))
        val parsed = HtmlParsers.parseListing(html)
        if (parsed.items.isEmpty()) {
            error("목록을 읽지 못했습니다. 사이트 구조가 바뀌었을 수 있습니다.")
        }
        parsed.copy(items = applyBadges(parsed.items))
    }

    suspend fun loadEpisodes(item: ToonItem, page: Int): EpisodePage = withContext(Dispatchers.IO) {
        val html = client.fetch(ComicSite.seriesUrl(item.id, item.title, page))
        val parsed = HtmlParsers.parseEpisodes(html)
        if (parsed.items.isEmpty()) {
            error("회차 목록을 읽지 못했습니다.")
        }
        parsed
    }

    suspend fun search(query: String): List<ToonItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val json = client.fetch(ComicSite.searchUrl(q))
        val items = SearchParser.parse(json)
        applyBadges(items)
    }

    suspend fun markSeen(items: List<ToonItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val nowMs = now()
        val existing = seenDao.byIds(items.map { it.id }).associateBy { it.id }
        val rows = items.map { item ->
            val prev = existing[item.id]
            SeenToon(
                id = item.id,
                title = item.title,
                updatedAt = item.updatedAt,
                firstSeenAt = prev?.firstSeenAt ?: nowMs,
                lastSeenAt = nowMs,
                notifiedKey = notifyKey(item),
            )
        }
        seenDao.upsertAll(rows)
    }

    suspend fun checkLatestUpdates(): List<ToonItem> {
        val page = loadListing(Catalog.LATEST, 1)
        val fresh = page.items.filter { it.isNew || it.isUpdated }
        if (fresh.isEmpty()) {
            markSeen(page.items)
            return emptyList()
        }
        val existing = seenDao.byIds(fresh.map { it.id }).associateBy { it.id }
        val unseen = fresh.filter { item ->
            existing[item.id]?.notifiedKey != notifyKey(item)
        }
        markSeen(page.items)
        return unseen
    }

    private suspend fun applyBadges(items: List<ToonItem>): List<ToonItem> {
        if (items.isEmpty()) return items
        val total = seenDao.count()
        if (total == 0) {
            return items
        }
        val existing = seenDao.byIds(items.map { it.id }).associateBy { it.id }
        return items.map { item ->
            val prev = existing[item.id]
            item.copy(
                isNew = prev == null,
                isUpdated = prev != null &&
                    !item.updatedAt.isNullOrBlank() &&
                    prev.updatedAt != item.updatedAt,
            )
        }
    }

    private fun notifyKey(item: ToonItem): String = "${item.id}|${item.updatedAt.orEmpty()}"
}
