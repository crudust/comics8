package com.comics8.core.source

import com.comics8.core.model.ToonItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LatestUpdateSelectionTest {
    @Test
    fun elevenKeepsOnlyFavoriteIntersection() {
        val latest = listOf(
            toon("eleven", "a"),
            toon("eleven", "b"),
            toon("eleven", "c"),
        )
        val keys = setOf("eleven:a", "eleven:c", "hitomi:artist:x")
        assertThat(LatestUpdateSelection.intersection(latest, keys).map { it.id })
            .containsExactly("a", "c")
            .inOrder()
    }

    @Test
    fun perFavoriteHonorsFlagAndCandidateFilter() {
        val favs = listOf(
            toon("hitomi", "artist:one"),
            toon("hitomi", "gallery:99"),
            toon("hitomi", "artist:two"),
        )
        val source = StubComicSource(
            id = "hitomi",
            notificationMode = NotificationMode.PER_FAVORITE,
            candidates = { favorites -> favorites.filter { it.id.startsWith("artist:") } },
        )
        assertThat(LatestUpdateSelection.candidates(source, favs, null, sourceEnabled = false)).isEmpty()
        assertThat(LatestUpdateSelection.candidates(source, favs, null, sourceEnabled = true).map { it.id })
            .containsExactly("artist:one", "artist:two")
            .inOrder()
    }

    @Test
    fun latestIntersectionUsesFavoriteKeys() {
        val source = StubComicSource(
            id = "eleven",
            notificationMode = NotificationMode.LATEST_INTERSECTION,
        )
        val favs = listOf(toon("eleven", "a"), toon("eleven", "c"))
        val latest = listOf(toon("eleven", "a"), toon("eleven", "b"))
        assertThat(LatestUpdateSelection.candidates(source, favs, latest, sourceEnabled = true).map { it.id })
            .containsExactly("a")
            .inOrder()
        assertThat(LatestUpdateSelection.candidates(source, favs, latest, sourceEnabled = false)).isEmpty()
    }

    private fun toon(sourceId: String, id: String) = ToonItem(
        id = id,
        title = id,
        thumbUrl = "",
        href = "",
        sourceId = sourceId,
    )
}
