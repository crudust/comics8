package com.comics8.core.model

object FavoriteListing {
    /**
     * Orders favorites by update date score descending,
     * maintaining stable local order (savedAt DESC) for identical dates.
     */
    fun assemble(localFavorites: List<ToonItem>): List<ToonItem> {
        if (localFavorites.isEmpty()) return emptyList()
        return localFavorites
            .sortedByDescending { UpdateDates.parseScore(it.updatedAt) }
            .map { it.copy(isFavorite = true) }
    }
}
