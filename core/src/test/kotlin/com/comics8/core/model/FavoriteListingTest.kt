package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class FavoriteListingTest {
    @Test
    fun latestHitsComeFirstInListingOrderThenLeftoversByDate() = runBlocking {
        val local = listOf(
            toon("old", "08.10"),
            toon("b", "08.16"),
            toon("a", "08.16"),
            toon("unseen-newer", "08.17"),
        )
        val pages = mapOf(
            1 to ListingPage(
                items = listOf(toon("x", "08.18"), toon("a", "08.18"), toon("b", "08.18")),
                currentPage = 1,
                lastPage = 1,
            ),
        )

        val result = FavoriteListing.assemble(local) { page ->
            pages.getValue(page)
        }

        assertThat(result.map { it.id }).containsExactly("a", "b", "unseen-newer", "old").inOrder()
        assertThat(result[0].updatedAt).isEqualTo("08.18")
        assertThat(result.all { it.isFavorite }).isTrue()
    }

    @Test
    fun onlyUsesFirstLatestPage() = runBlocking {
        val local = listOf(toon("p2", "08.16"), toon("p1", "08.16"))
        val fetched = mutableListOf<Int>()

        val result = FavoriteListing.assemble(local) { page ->
            fetched += page
            if (page != 1) error("should not fetch page $page")
            ListingPage(
                items = listOf(toon("p1", "08.18"), toon("other", "08.18")),
                currentPage = 1,
                lastPage = 2,
            )
        }

        assertThat(fetched).containsExactly(1)
        assertThat(result.map { it.id }).containsExactly("p1", "p2").inOrder()
    }

    @Test
    fun listingFailureFallsBackToLocalDateOrder() = runBlocking {
        val local = listOf(toon("older", "08.10"), toon("newer", "08.16"))

        val result = FavoriteListing.assemble(local) { error("offline") }

        assertThat(result.map { it.id }).containsExactly("newer", "older").inOrder()
    }

    @Test
    fun comparesWorkIdNotBareId() = runBlocking {
        val local = listOf(toon("shared", "08.16").copy(sourceId = "eleven"))
        val result = FavoriteListing.assemble(local) {
            ListingPage(
                items = listOf(toon("shared", "08.18").copy(sourceId = "hitomi")),
                currentPage = 1,
                lastPage = 1,
            )
        }
        assertThat(result.map { it.id }).containsExactly("shared")
        assertThat(result[0].updatedAt).isEqualTo("08.16")
    }

    @Test
    fun nullLatestFetchKeepsLocalDateOrder() = runBlocking {
        val local = listOf(toon("older", "08.10"), toon("newer", "08.16"))
        val result = FavoriteListing.assemble(local, fetchLatestPage = null)
        assertThat(result.map { it.id }).containsExactly("newer", "older").inOrder()
    }

    @Test
    fun assembleBySourceMergesByUpdateScore() = runBlocking {
        val local = listOf(
            toon("eleven-old", "08.10").copy(sourceId = "eleven"),
            toon("hitomi-new", "08.18").copy(sourceId = "hitomi"),
            toon("eleven-mid", "08.16").copy(sourceId = "eleven"),
        )
        val fetched = mutableListOf<String>()
        val result = FavoriteListing.assembleBySource(local) { sourceId, page ->
            fetched += "$sourceId:$page"
            if (sourceId == "hitomi") {
                null
            } else {
                ListingPage(
                    items = listOf(toon("eleven-mid", "08.17").copy(sourceId = "eleven")),
                    currentPage = 1,
                    lastPage = 1,
                )
            }
        }
        assertThat(result.map { it.id }).containsExactly("hitomi-new", "eleven-mid", "eleven-old").inOrder()
        assertThat(result[1].updatedAt).isEqualTo("08.17")
        assertThat(fetched).containsExactly("eleven:1", "hitomi:1")
        return@runBlocking
    }

    @Test
    fun blankSourceIdAssemblesAsElevenWithoutThrowing() = runBlocking {
        val local = listOf(toon("7883", "08.16").copy(sourceId = ""))
        val result = FavoriteListing.assemble(local) {
            ListingPage(
                items = listOf(toon("7883", "08.18").copy(sourceId = "eleven")),
                currentPage = 1,
                lastPage = 1,
            )
        }
        assertThat(result.map { it.id }).containsExactly("7883")
        assertThat(result[0].updatedAt).isEqualTo("08.18")
        return@runBlocking
    }

    private fun toon(id: String, updatedAt: String) = ToonItem(
        id = id,
        title = id,
        thumbUrl = "",
        href = "",
        updatedAt = updatedAt,
    )
}
