package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EpisodeListingTotalTest {
    private fun episode(id: String) = EpisodeItem(
        wrId = id,
        title = id,
        date = null,
        thumbUrl = null,
        href = "href/$id",
    )

    private fun page(current: Int, last: Int, count: Int, prefix: String = "p") = EpisodePage(
        items = List(count) { episode("$prefix$current-$it") },
        currentPage = current,
        lastPage = last,
    )

    @Test
    fun countingEveryPageProducesTheExactTotal() = runBlocking {
        val requested = mutableListOf<Int>()
        // 3 pages of 100/95/52: the middle page is short, which page arithmetic would miss.
        val pages = mapOf(
            1 to page(1, 3, 100),
            2 to page(2, 3, 95),
            3 to page(3, 3, 52),
        )
        val counted = RepositoryTransforms.countEpisodeListing(pageSize = 100) { number ->
            requested += number
            pages.getValue(number)
        }

        assertThat(requested).containsExactly(1, 2, 3).inOrder()
        // naive = 2 full pages + 52 on the tail = 252, counted = 247, so delta = -5.
        assertThat(counted).isEqualTo(EpisodeListingTotal(count = 247, lastPage = 3, pageSize = 100, delta = -5))
    }

    @Test
    fun aPartialSweepNeverProducesATotal() = runBlocking {
        val failedTail = RepositoryTransforms.countEpisodeListing(pageSize = 100) { number ->
            if (number == 1) page(1, 3, 100) else error("network down")
        }
        assertThat(failedTail).isNull()

        val hole = RepositoryTransforms.countEpisodeListing(pageSize = 100) { number ->
            when (number) {
                1 -> page(1, 3, 100)
                2 -> page(2, 3, 0)
                else -> page(3, 3, 50)
            }
        }
        assertThat(hole).isNull()
    }

    @Test
    fun singlePageListingIsCountedDirectly() = runBlocking {
        val counted = RepositoryTransforms.countEpisodeListing(pageSize = 100) { page(1, 1, 42) }
        assertThat(counted).isEqualTo(EpisodeListingTotal(count = 42, lastPage = 1, pageSize = 100))
    }

    @Test
    fun correctionCarriesShortPagesWhereverTheyAre() {
        // Counted 247 over 3 pages (100/95/52 of size 100): naive 2*100 + 52 = 252, delta = -5.
        val known = EpisodeListingTotal(count = 247, lastPage = 3, pageSize = 100, delta = -5)

        // The tail page reproduces the counted total exactly.
        assertThat(RepositoryTransforms.totalFromPage(page(3, 3, 52), pageSize = 100, known = known))
            .isEqualTo(known)

        // A newest episode is added: the page count is unchanged but the tail now holds 53, so one
        // single-page request reports 248 instead of freezing at 247.
        val grown = RepositoryTransforms.totalFromPage(page(3, 3, 53), pageSize = 100, known = known)!!
        assertThat(grown.count).isEqualTo(248)

        // An episode is removed from the same tail: 246.
        val shrunk = RepositoryTransforms.totalFromPage(page(3, 3, 51), pageSize = 100, known = known)!!
        assertThat(shrunk.count).isEqualTo(246)

        // A non-tail page carries no tail count, so it cannot refresh the total.
        assertThat(RepositoryTransforms.totalFromPage(page(1, 3, 100), pageSize = 100, known = known)).isNull()
    }

    @Test
    fun changedPageCountOrPageSizeInvalidatesTheCorrection() {
        val known = EpisodeListingTotal(count = 247, lastPage = 3, pageSize = 100, delta = -5)

        // The site added a page: the stored correction describes a different listing.
        assertThat(known.describes(lastPage = 4, pageSize = 100)).isFalse()
        assertThat(RepositoryTransforms.totalFromPage(page(4, 4, 10), pageSize = 100, known = known)).isNull()

        // A different page size invalidates it too.
        assertThat(known.describes(lastPage = 3, pageSize = 50)).isFalse()
        assertThat(RepositoryTransforms.totalFromPage(page(3, 3, 52), pageSize = 50, known = known)).isNull()

        // Nothing is derived from a page alone, without a count to correct it.
        assertThat(RepositoryTransforms.totalFromPage(page(3, 3, 52), pageSize = 100, known = null)).isNull()
    }

    @Test
    fun uncountedWorkHasNoTotalAndNoBadge() {
        assertThat(ProgressDisplayMode.LATEST_EPISODE.format(10, null, 0)).isNull()
        assertThat(ProgressDisplayMode.READ_COUNT.format(0, null, 5)).isNull()
        assertThat(ProgressDisplayMode.PERCENTAGE.format(10, null, 0)).isNull()
    }

    @Test
    fun countedTotalRendersWithoutAnApproximateMarker() {
        val counted = EpisodeListingTotal(count = 199, lastPage = 2, pageSize = 100, delta = -1)
        assertThat(ProgressDisplayMode.LATEST_EPISODE.format(199, counted, 199)).isEqualTo("199/199")
        assertThat(ProgressDisplayMode.READ_COUNT.format(0, counted, 199)).isEqualTo("199/199")
        assertThat(ProgressDisplayMode.PERCENTAGE.format(199, counted, 199)).isEqualTo("100%")
    }

    @Test
    fun unreadStateOnlyExistsOnceTheListingIsCounted() {
        // 199 real episodes, read 198: unread remains.
        val counted = EpisodeListingTotal(count = 199, lastPage = 2, pageSize = 100, delta = -1)
        assertThat(RepositoryTransforms.historyProgress(198, counted).hasNew).isTrue()
        assertThat(RepositoryTransforms.historyProgress(199, counted).hasNew).isFalse()

        // Without a count nothing claims unread episodes, and nothing is clamped.
        val uncounted = RepositoryTransforms.historyProgress(198, null)
        assertThat(uncounted.hasNew).isFalse()
        assertThat(uncounted.lastReadOrder).isEqualTo(198)
    }
}
