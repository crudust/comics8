package com.comics8.core.model

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class OfflineEpisodeRef(
    val wrId: String,
    val title: String,
    val href: String,
    val thumbUrl: String? = null,
    val date: String? = null,
)

object EpisodeCatalog {
    fun fromOffline(
        rows: List<OfflineEpisodeRef>,
        readAtByWrId: Map<String, Long?> = emptyMap(),
        lastPageByWrId: Map<String, Int> = emptyMap(),
    ): List<EpisodeItem> = rows.map { row ->
        val readAt = readAtByWrId[row.wrId]
        EpisodeItem(
            wrId = row.wrId,
            title = row.title,
            date = row.date,
            thumbUrl = row.thumbUrl,
            href = row.href,
            isRead = readAt != null,
            readAt = readAt,
            lastReadPage = lastPageByWrId[row.wrId] ?: 0,
        )
    }

    suspend fun loadAllPages(loadPage: suspend (Int) -> EpisodePage): List<EpisodeItem> {
        val first = loadPage(1)
        val lastP = first.lastPage.coerceAtLeast(1)
        if (lastP <= 1) return first.items
        val remaining = coroutineScope {
            val semaphore = Semaphore(2)
            (2..lastP).map { page ->
                async {
                    semaphore.withPermit { page to loadPage(page) }
                }
            }.awaitAll()
                .sortedBy { it.first }
                .flatMap { it.second.items }
        }
        return first.items + remaining
    }
}
