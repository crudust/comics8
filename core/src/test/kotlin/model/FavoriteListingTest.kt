package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FavoriteListingTest {
    @Test
    fun assembleOrdersByUpdateDateDescending() {
        val local = listOf(
            toon("old", "15.10.24"),
            toon("mid", "24.10.26"),
            toon("latest", "26.10.11"),
        )

        val result = FavoriteListing.assemble(local)

        assertThat(result.map { it.id }).containsExactly("latest", "mid", "old").inOrder()
        assertThat(result.all { it.isFavorite }).isTrue()
    }

    @Test
    fun assemblePreservesOrderForIdenticalDates() {
        val local = listOf(
            toon("first", "26.10.11"),
            toon("second", "26.10.11"),
            toon("third", "26.10.11"),
        )

        val result = FavoriteListing.assemble(local)

        assertThat(result.map { it.id }).containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun assemblePlacesEmptyDateAtEnd() {
        val local = listOf(
            toon("no-date", ""),
            toon("dated", "26.10.11"),
        )

        val result = FavoriteListing.assemble(local)

        assertThat(result.map { it.id }).containsExactly("dated", "no-date").inOrder()
    }

    @Test
    fun assembleEmptyListReturnsEmpty() {
        assertThat(FavoriteListing.assemble(emptyList())).isEmpty()
    }

    private fun toon(id: String, updatedAt: String) = ToonItem(
        id = id,
        title = id,
        thumbUrl = "",
        href = "",
        updatedAt = updatedAt,
    )
}
