package com.comics8.core.model

import com.comics8.core.source.StubComicSource
import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RepositoryTransformsTest {
    @Test
    fun installedSources_ordersLocalThenStorageThenOnlineInConfiguredOrder() {
        val sources = listOf(
            StubComicSource("js-a"),
            StubComicSource("network-a"),
            StubComicSource(WorkId.LOCAL_SOURCE),
            StubComicSource("network-b"),
            StubComicSource("js-b"),
        )
        val installed = linkedSetOf("js-b", "network-b", WorkId.LOCAL_SOURCE, "js-a")

        val result = RepositoryTransforms.installedSources(sources, installed)

        assertThat(result.map { it.id }).containsExactly(
            WorkId.LOCAL_SOURCE,
            "network-b",
            "js-b",
            "js-a",
        ).inOrder()
    }

    @Test
    fun enrichEpisodePage_appliesReadStateAndKeepsFirstDuplicate() {
        val page = EpisodePage(
            items = listOf(
                episode("1", "first"),
                episode("1", "duplicate"),
                episode("2", "second"),
            ),
            currentPage = 2,
            lastPage = 3,
        )

        val result = RepositoryTransforms.enrichEpisodePage(
            page,
            mapOf("1" to RepositoryTransforms.EpisodeReadState(readAt = 50L, lastPage = 7)),
        )

        assertThat(result.currentPage).isEqualTo(2)
        assertThat(result.items.map { it.title }).containsExactly("first", "second").inOrder()
        assertThat(result.items.first().isRead).isTrue()
        assertThat(result.items.first().readAt).isEqualTo(50L)
        assertThat(result.items.first().lastReadPage).isEqualTo(7)
        assertThat(result.items.last().isRead).isFalse()
    }

    @Test
    fun aCountedTotalIsNeverDerivedFromASinglePage() {
        // One page proves only what it shows; the total itself comes from RepositoryTransforms.countEpisodeListing.
        val first = EpisodePage(
            items = listOf(episode("1", "one"), episode("2", "two")),
            currentPage = 1,
            lastPage = 4,
        )
        assertThat(first.items).hasSize(2)
        assertThat(EpisodeListingTotal.fromStored(0, 4, 20, 0)).isNull()
    }

    @Test
    fun storedShapeIsOnlyTrustedWhileItStillDescribesTheListing() {
        val counted = EpisodeListingTotal.fromStored(count = 247, lastPage = 3, pageSize = 100, delta = -5)
        assertThat(counted).isNotNull()
        assertThat(counted!!.isValid).isTrue()
        assertThat(counted.describes(lastPage = 3, pageSize = 100)).isTrue()
        assertThat(counted.describes(lastPage = 4, pageSize = 100)).isFalse()
        assertThat(counted.describes(lastPage = 3, pageSize = 50)).isFalse()

        // Rows written before counting existed carry no shape and are therefore not totals.
        assertThat(EpisodeListingTotal.fromStored(count = 200, lastPage = 0, pageSize = 0, delta = 0)).isNull()
        assertThat(EpisodeListingTotal.fromStored(count = 0, lastPage = 2, pageSize = 100, delta = -5)).isNull()
    }

    @Test
    fun historyProgressNeverClampsOrderAndOnlyClaimsUnreadWithACount() {
        val counted = EpisodeListingTotal(count = 199, lastPage = 2, pageSize = 100, delta = -1)
        val read = RepositoryTransforms.historyProgress(199, counted)
        assertThat(read.lastReadOrder).isEqualTo(199)
        assertThat(read.hasNew).isFalse()

        val unfinished = RepositoryTransforms.historyProgress(198, counted)
        assertThat(unfinished.hasNew).isTrue()

        // A stale smaller count (e.g. from server cache before tail refresh) must not clamp lastReadOrder.
        val aheadOfStaleTotal = RepositoryTransforms.historyProgress(205, counted)
        assertThat(aheadOfStaleTotal.lastReadOrder).isEqualTo(205)
        assertThat(aheadOfStaleTotal.hasNew).isFalse()

        // Uncounted: no unread claim, and the read order is left untouched.
        val uncounted = RepositoryTransforms.historyProgress(120, null)
        assertThat(uncounted.lastReadOrder).isEqualTo(120)
        assertThat(uncounted.hasNew).isFalse()
    }

    @Test
    fun totalFromPageDerivesSinglePageDirectlyAndRejectsMismatchedMultiPage() {
        val singlePage = EpisodePage(
            items = listOf(episode("1", "one"), episode("2", "two")),
            currentPage = 1,
            lastPage = 1,
        )
        assertThat(RepositoryTransforms.totalFromPage(singlePage, pageSize = 100, known = null))
            .isEqualTo(EpisodeListingTotal(count = 2, lastPage = 1, pageSize = 100, delta = 0))

        val multiFirstPage = EpisodePage(
            items = listOf(episode("1", "one")),
            currentPage = 1,
            lastPage = 3,
        )
        val stored = EpisodeListingTotal(count = 247, lastPage = 3, pageSize = 100, delta = -5)
        assertThat(RepositoryTransforms.totalFromPage(multiFirstPage, pageSize = 100, known = stored)).isNull()
    }

    @Test
    fun applyListingFlags_combinesSeenFavoriteAndHistoryState() {
        val seenItem = toon("seen", updatedAt = "new")
        val unseenItem = toon("unseen", updatedAt = "same")
        val seenKey = seenItem.workId().storageKey()

        val result = RepositoryTransforms.applyListingFlags(
            items = listOf(seenItem, unseenItem),
            seenCount = 1,
            favoriteKeys = setOf(seenKey),
            seenByKey = mapOf(seenKey to "old"),
            historyByKey = mapOf(seenKey to (2 to 10)),
            readCountsByKey = mapOf(seenKey to 4),
            seenUpdatedAt = { it },
        ) { _, history, _, readCount -> history?.let { "${it.first}/${it.second}/$readCount" } }

        assertThat(result[0].isNew).isTrue()
        assertThat(result[0].isFavorite).isTrue()
        assertThat(result[0].readProgress).isEqualTo("2/10/4")
        assertThat(result[1].isNew).isTrue()
        assertThat(result[1].isFavorite).isFalse()
        assertThat(result[1].readProgress).isNull()
    }

    @Test
    fun applyListingFlags_supportsCountedTotalsForUnreadItems() {
        val unreadItem = toon("unread", updatedAt = "same")
        val key = unreadItem.workId().storageKey()
        val totals = mapOf(key to EpisodeListingTotal(count = 104, lastPage = 2, pageSize = 100))

        val result = RepositoryTransforms.applyListingFlags(
            items = listOf(unreadItem),
            seenCount = 0,
            favoriteKeys = emptySet(),
            seenByKey = emptyMap<String, String>(),
            historyByKey = emptyMap<String, Pair<Int, Int>>(),
            readCountsByKey = emptyMap(),
            totalsByKey = totals,
            seenUpdatedAt = { it: String -> it },
        ) { _, _, total, _ ->
            total?.let { "${it.count}화" }
        }

        assertThat(result[0].readProgress).isEqualTo("104화")
    }

    @Test
    fun needsEpisodeSyncIsDrivenByUnreadEpisodesNotByMissingTotal() {
        // Unread episodes remain -> refresh even though a total is already known.
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = EpisodeListingTotal(count = 10, lastPage = 1, pageSize = 100),
                lastReadOrder = 3,
                thumbUrl = "thumb",
                isFavorite = false,
            ),
        ).isTrue()

        // Fully read and known -> nothing to refresh.
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = EpisodeListingTotal(count = 10, lastPage = 1, pageSize = 100),
                lastReadOrder = 10,
                thumbUrl = "thumb",
                isFavorite = false,
            ),
        ).isFalse()

        // An unknown total is itself an unread signal, so no separate "needsCatalog" flag is needed.
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = null,
                lastReadOrder = 10,
                thumbUrl = "thumb",
                isFavorite = false,
            ),
        ).isTrue()

    }

    @Test
    fun needsEpisodeSyncAlsoCoversMissingThumbnailAndFavorites() {
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = EpisodeListingTotal(count = 10, lastPage = 1, pageSize = 100),
                lastReadOrder = 10,
                thumbUrl = "",
                isFavorite = false,
            ),
        ).isTrue()
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = EpisodeListingTotal(count = 10, lastPage = 1, pageSize = 100),
                lastReadOrder = 10,
                thumbUrl = "thumb",
                isFavorite = true,
            ),
        ).isTrue()
    }

    @Test
    fun parseReaderSetting_supportsStoredEnumsAndLegacyFallbacks() {
        val valid = RepositoryTransforms.parseReaderSetting("DUAL", "LEFT_TO_RIGHT", "SLICE")
        assertThat(valid).isEqualTo(
            RepositoryTransforms.ReaderSettingValues(ViewMode.DUAL, ReadDirection.LEFT_TO_RIGHT, SplitMode.SLICE),
        )

        val fallback = RepositoryTransforms.parseReaderSetting("SINGLE", "bad", "bad")
        assertThat(fallback.viewMode).isEqualTo(ViewMode.PAGE)
        assertThat(fallback.readDirection).isEqualTo(ReadDirection.RIGHT_TO_LEFT)
        assertThat(fallback.splitMode).isEqualTo(SplitMode.FIT)
    }

    private fun episode(id: String, title: String) = EpisodeItem(
        wrId = id,
        title = title,
        date = null,
        thumbUrl = null,
        href = "/$id",
    )

    private fun toon(id: String, updatedAt: String?) = ToonItem(
        id = id,
        title = id,
        thumbUrl = "",
        href = "/$id",
        updatedAt = updatedAt,
        sourceId = "eleven",
    )
}
