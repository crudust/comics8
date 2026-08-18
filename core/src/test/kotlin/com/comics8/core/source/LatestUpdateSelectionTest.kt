package com.comics8.core.source

import com.comics8.core.model.ToonItem
import com.comics8.core.source.hitomi.HitomiSource
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
        assertThat(LatestUpdateSelection.elevenIntersection(latest, keys).map { it.id })
            .containsExactly("a", "c")
            .inOrder()
    }

    @Test
    fun hitomiSkipsGalleryAndHonorsFlag() {
        val favs = listOf(
            toon(HitomiSource.ID, "artist:one"),
            toon(HitomiSource.ID, "gallery:99"),
            toon(HitomiSource.ID, "artist:two"),
        )
        val source = HitomiSource()
        assertThat(LatestUpdateSelection.candidates(source, favs, null, sourceEnabled = false)).isEmpty()
        assertThat(LatestUpdateSelection.candidates(source, favs, null, sourceEnabled = true).map { it.id })
            .containsExactly("artist:one", "artist:two")
            .inOrder()
    }

    @Test
    fun elevenUsesLatestIntersection() {
        val source = ElevenToonSource()
        val favs = listOf(toon("eleven", "a"), toon("eleven", "c"))
        val latest = listOf(toon("eleven", "a"), toon("eleven", "b"))
        assertThat(LatestUpdateSelection.candidates(source, favs, latest, sourceEnabled = true).map { it.id })
            .containsExactly("a")
            .inOrder()
    }

    private fun toon(sourceId: String, id: String) = ToonItem(
        id = id,
        title = id,
        thumbUrl = "",
        href = "",
        sourceId = sourceId,
    )
}
