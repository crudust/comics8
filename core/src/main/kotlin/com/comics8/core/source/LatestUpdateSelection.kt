package com.comics8.core.source

import com.comics8.core.model.ToonItem
import com.comics8.core.source.hitomi.HitomiSource

object LatestUpdateSelection {
    fun elevenIntersection(latest: List<ToonItem>, favoriteKeys: Set<String>): List<ToonItem> =
        latest.filter { it.workId().storageKey() in favoriteKeys }

    fun hitomiEligible(favorites: List<ToonItem>, enabled: Boolean): List<ToonItem> {
        if (!enabled) return emptyList()
        return favorites.filter { it.id.startsWith(HitomiSource.ARTIST_PREFIX) }
    }
}
