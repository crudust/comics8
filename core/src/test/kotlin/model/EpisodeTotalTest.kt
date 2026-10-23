package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EpisodeTotalTest {
    private fun episode(id: String) = EpisodeItem(
        wrId = id,
        title = id,
        date = null,
        thumbUrl = null,
        href = "href/$id",
    )

    private fun page(current: Int, last: Int, count: Int, idPrefix: String = "$last-$current") = EpisodePage(
        items = List(count) { episode("$idPrefix-$it") },
        currentPage = current,
        lastPage = last,
    )

    @Test
    fun exactTotalMayCorrectAnInflatedValueDownward() {
        // The reported symptom: 199 real episodes shown as 200 because page one was multiplied up to
        // a full page, then frozen by monotonic merges.
        val inflated = EpisodeTotal.AtLeast(200)
        val real = EpisodeTotal.Exact(199)

        assertThat(EpisodeTotal.merge(inflated, real)).isEqualTo(real)
        assertThat(EpisodeTotal.merge(inflated, real).totalLabel()).isEqualTo("199")
        assertThat(EpisodeTotal.merge(inflated, real).needsRefresh(199)).isFalse()
        assertThat(inflated.needsRefresh(199)).isTrue()
    }

    @Test
    fun lowerBoundNeverOverridesAnExactTotal() {
        assertThat(EpisodeTotal.merge(EpisodeTotal.Exact(199), EpisodeTotal.AtLeast(300)))
            .isEqualTo(EpisodeTotal.Exact(199))
    }

    @Test
    fun firstPageAloneNeverMultipliesUpToAPageSizedEstimate() {
        val observed = EpisodeTotal.from(page(1, 2, 100), pageSize = 100)
        assertThat(observed).isEqualTo(EpisodeTotal.AtLeast(100))
    }

    @Test
    fun storedShapeTurnsASinglePageIntoAnExactTotal() {
        // 199 episodes over 2 pages: page one is full, the tail holds 99.
        val shape = EpisodeListingSnapshot(lastPage = 2, lastPageItemCount = 99)
        val observed = EpisodeTotal.from(page(1, 2, 100), pageSize = 100, snapshot = shape)

        assertThat(observed).isEqualTo(EpisodeTotal.Exact(199))
    }

    @Test
    fun storedShapeIsIgnoredWhenTheListingShapeChanged() {
        val staleShape = EpisodeListingSnapshot(lastPage = 2, lastPageItemCount = 99)
        // The site now reports 3 pages: the cached tail length describes a different listing.
        assertThat(EpisodeTotal.from(page(1, 3, 100), pageSize = 100, snapshot = staleShape))
            .isEqualTo(EpisodeTotal.AtLeast(100))
    }

    @Test
    fun storedShapeIsIgnoredWhenPageSizeLooksWrong() {
        val shape = EpisodeListingSnapshot(lastPage = 3, lastPageItemCount = 50)
        // Page 1 is not full although more pages exist -> pageSize metadata is wrong.
        assertThat(EpisodeTotal.from(page(1, 3, 20), pageSize = 100, snapshot = shape))
            .isEqualTo(EpisodeTotal.AtLeast(20))
    }

    @Test
    fun storedShapeIsIgnoredWhenItCannotReproduceTheObservedListing() {
        // A tail holding more items than a page can hold makes the formula meaningless.
        val impossible = EpisodeListingSnapshot(lastPage = 3, lastPageItemCount = 250)
        assertThat(EpisodeTotal.from(page(1, 3, 100), pageSize = 100, snapshot = impossible))
            .isEqualTo(EpisodeTotal.AtLeast(100))

        // A shape that yields fewer episodes than the page already shows is equally impossible.
        val contradicts = EpisodeListingSnapshot(lastPage = 2, lastPageItemCount = 0)
        assertThat(EpisodeTotal.from(page(1, 2, 100), pageSize = 100, snapshot = contradicts))
            .isEqualTo(EpisodeTotal.AtLeast(100))
    }

    @Test
    fun fullSweepLearnsTheShapeAndIsExact() = runBlocking {
        val requested = mutableListOf<Int>()
        val pages = mapOf(
            1 to page(1, 2, 100, idPrefix = "p1"),
            2 to page(2, 2, 99, idPrefix = "p2"),
        )
        val result = RepositoryTransforms.verifyEpisodeListing(pageSize = 100) { pageNumber ->
            requested += pageNumber
            pages.getValue(pageNumber)
        }

        assertThat(requested).containsExactly(1, 2).inOrder()
        assertThat(result.total).isEqualTo(EpisodeTotal.Exact(199))
        assertThat(result.snapshot).isEqualTo(EpisodeListingSnapshot(lastPage = 2, lastPageItemCount = 99))
    }

    @Test
    fun failedSweepYieldsALowerBoundAndNoShape() = runBlocking {
        val result = RepositoryTransforms.verifyEpisodeListing(pageSize = 100) { pageNumber ->
            if (pageNumber == 1) page(1, 3, 100) else error("network down")
        }

        assertThat(result.total).isEqualTo(EpisodeTotal.AtLeast(100))
        assertThat(result.snapshot).isNull()
    }

    @Test
    fun sweepWithAHoleInTheMiddleProvesNothing() = runBlocking {
        val result = RepositoryTransforms.verifyEpisodeListing(pageSize = 100) { pageNumber ->
            when (pageNumber) {
                1 -> page(1, 3, 100)
                2 -> page(2, 3, 0)
                else -> page(3, 3, 50)
            }
        }

        assertThat(result.total).isEqualTo(EpisodeTotal.AtLeast(100))
        assertThat(result.snapshot).isNull()
    }

    @Test
    fun singlePageListingIsExactAndVerifiableFromOneRequest() = runBlocking {
        val result = RepositoryTransforms.verifyEpisodeListing(pageSize = 100) { page(1, 1, 42) }

        assertThat(result.total).isEqualTo(EpisodeTotal.Exact(42))
        assertThat(result.snapshot).isEqualTo(EpisodeListingSnapshot(lastPage = 1, lastPageItemCount = 42))
    }
}
