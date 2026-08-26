package com.comics8.core.model

import com.comics8.core.source.WorkId

object FavoriteListing {
    /**
     * Favorites follow the first page of the latest-update listing.
     * Anything not seen there is appended by local update date.
     */
    suspend fun assemble(
        localFavorites: List<ToonItem>,
        fetchLatestPage: (suspend (Int) -> ListingPage)? = null,
    ): List<ToonItem> {
        if (localFavorites.isEmpty()) return emptyList()
        val remaining = localFavorites.mapTo(mutableSetOf()) { it.workId() }
        val fromLatest = mutableListOf<ToonItem>()
        if (fetchLatestPage != null) {
            try {
                val listing = fetchLatestPage(1)
                for (item in listing.items) {
                    if (remaining.remove(item.workId())) {
                        fromLatest.add(item.copy(isFavorite = true))
                    }
                }
            } catch (_: Exception) {
                // Offline or listing failure: keep whatever we already took, rest go by date.
            }
        }
        val seen = fromLatest.mapTo(HashSet()) { it.workId() }
        val leftover = localFavorites
            .filter { it.workId() !in seen }
            .sortedByDescending { UpdateDates.parseScore(it.updatedAt) }
            .map { it.copy(isFavorite = true) }
        return fromLatest + leftover
    }

    suspend fun assembleBySource(
        localFavorites: List<ToonItem>,
        fetchLatestPage: suspend (sourceId: String, page: Int) -> ListingPage?,
    ): List<ToonItem> {
        if (localFavorites.isEmpty()) return emptyList()
        val groups = localFavorites.groupBy { it.sourceId.ifBlank { WorkId.DEFAULT_SOURCE } }
        val assembled = groups.flatMap { (sourceId, items) ->
            val first = fetchLatestPage(sourceId, 1)
            assemble(
                items,
                fetchLatestPage = if (first == null) {
                    null
                } else {
                    { page ->
                        if (page == 1) first else fetchLatestPage(sourceId, page) ?: ListingPage(emptyList(), page, 1)
                    }
                },
            )
        }
        return assembled.sortedByDescending { UpdateDates.parseScore(it.updatedAt) }
    }
}
