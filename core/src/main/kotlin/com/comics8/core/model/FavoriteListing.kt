package com.comics8.core.model

object FavoriteListing {
    /**
     * Favorites follow the first page of the latest-update listing.
     * Anything not seen there is appended by local update date.
     */
    suspend fun assemble(
        localFavorites: List<ToonItem>,
        fetchLatestPage: suspend (Int) -> ListingPage,
    ): List<ToonItem> {
        if (localFavorites.isEmpty()) return emptyList()
        val remaining = localFavorites.mapTo(mutableSetOf()) { it.id }
        val fromLatest = mutableListOf<ToonItem>()
        try {
            val listing = fetchLatestPage(1)
            for (item in listing.items) {
                if (remaining.remove(item.id)) {
                    fromLatest.add(item.copy(isFavorite = true))
                }
            }
        } catch (_: Exception) {
            // Offline or listing failure: keep whatever we already took, rest go by date.
        }
        val seen = fromLatest.mapTo(HashSet()) { it.id }
        val leftover = localFavorites
            .filter { it.id !in seen }
            .sortedByDescending { UpdateDates.parseScore(it.updatedAt) }
            .map { it.copy(isFavorite = true) }
        return fromLatest + leftover
    }
}
