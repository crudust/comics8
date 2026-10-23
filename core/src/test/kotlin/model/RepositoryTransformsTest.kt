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
    fun episodeTotalObservationOnlyTrustsTheLastPage() {
        // Page 1 of a 4-page listing proves 2 episodes exist, not 4 * pageSize.
        val first = EpisodePage(
            items = listOf(episode("1", "one"), episode("2", "two")),
            currentPage = 1,
            lastPage = 4,
        )
        assertThat(EpisodeTotal.from(first, pageSize = 20))
            .isEqualTo(EpisodeTotal.AtLeast(2))

        // The last page is authoritative: 3 pages * 20 + 3 -> 63.
        val last = EpisodePage(
            items = List(3) { episode("${it + 61}", "e${it + 61}") },
            currentPage = 4,
            lastPage = 4,
        )
        assertThat(EpisodeTotal.from(last, pageSize = 20))
            .isEqualTo(EpisodeTotal.Exact(63))

        // A single-page listing is exact by construction.
        val single = EpisodePage(
            items = List(42) { episode("$it", "e$it") },
            currentPage = 1,
            lastPage = 1,
        )
        assertThat(EpisodeTotal.from(single, pageSize = 100)).isEqualTo(EpisodeTotal.Exact(42))

        assertThat(EpisodeTotal.from(EpisodePage(emptyList(), currentPage = 1, lastPage = 8), pageSize = 20))
            .isEqualTo(EpisodeTotal.Unknown)
    }

    @Test
    fun episodeTotalMergeLetsExactCorrectDownwardAndKeepsLowerBoundsMonotonic() {
        // Exact always wins, including a lower count (episodes were deleted or renamed).
        assertThat(EpisodeTotal.merge(EpisodeTotal.AtLeast(200), EpisodeTotal.Exact(199)))
            .isEqualTo(EpisodeTotal.Exact(199))
        assertThat(EpisodeTotal.merge(EpisodeTotal.Exact(200), EpisodeTotal.Exact(199)))
            .isEqualTo(EpisodeTotal.Exact(199))
        // A lower bound never replaces an exact total.
        assertThat(EpisodeTotal.merge(EpisodeTotal.Exact(199), EpisodeTotal.AtLeast(250)))
            .isEqualTo(EpisodeTotal.Exact(199))
        // Lower bounds only grow.
        assertThat(EpisodeTotal.merge(EpisodeTotal.AtLeast(100), EpisodeTotal.AtLeast(52)))
            .isEqualTo(EpisodeTotal.AtLeast(100))
        assertThat(EpisodeTotal.merge(EpisodeTotal.AtLeast(100), EpisodeTotal.AtLeast(300)))
            .isEqualTo(EpisodeTotal.AtLeast(300))
    }

    @Test
    fun mergeHistoryProgressNeverClampsOrderAgainstALowerBound() {
        val exact = RepositoryTransforms.mergeHistoryProgress(
            lastReadOrder = 199,
            storedTotal = 200,
            observed = EpisodeTotal.Exact(199),
        )
        assertThat(exact.total).isEqualTo(EpisodeTotal.Exact(199))
        assertThat(exact.lastReadOrder).isEqualTo(199)
        assertThat(exact.hasNew).isFalse()

        // An unfinished work must not be reported as fully read just because the total is unknown.
        val lowerBound = RepositoryTransforms.mergeHistoryProgress(
            lastReadOrder = 100,
            storedTotal = 100,
            observed = EpisodeTotal.AtLeast(100),
        )
        assertThat(lowerBound.hasNew).isTrue()

        // A lower bound must not truncate how far the user actually read.
        val ahead = RepositoryTransforms.mergeHistoryProgress(
            lastReadOrder = 120,
            storedTotal = 100,
            observed = EpisodeTotal.AtLeast(100),
        )
        assertThat(ahead.lastReadOrder).isEqualTo(120)
        assertThat(ahead.hasNew).isTrue()
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
        ) { _, history, readCount, _ -> history?.let { "${it.first}/${it.second}/$readCount" } }

        assertThat(result[0].isNew).isTrue()
        assertThat(result[0].isFavorite).isTrue()
        assertThat(result[0].readProgress).isEqualTo("2/10/4")
        assertThat(result[1].isNew).isTrue()
        assertThat(result[1].isFavorite).isFalse()
        assertThat(result[1].readProgress).isNull()
    }

    @Test
    fun applyListingFlags_supportsCatalogTotalsForUnreadItems() {
        val unreadItem = toon("unread", updatedAt = "same")
        val key = unreadItem.workId().storageKey()

        val result = RepositoryTransforms.applyListingFlags(
            items = listOf(unreadItem),
            seenCount = 0,
            favoriteKeys = emptySet(),
            seenByKey = emptyMap<String, String>(),
            historyByKey = emptyMap<String, Pair<Int, Int>>(),
            readCountsByKey = emptyMap(),
            catalogTotalsByKey = mapOf(key to EpisodeTotal.Exact(104)),
            seenUpdatedAt = { it: String -> it },
        ) { _, _, _, catalogTotal ->
            if (catalogTotal.count > 0) "${catalogTotal.count}화" else null
        }

        assertThat(result[0].readProgress).isEqualTo("104화")
    }

    @Test
    fun needsEpisodeSyncIsDrivenByUnreadEpisodesNotByMissingCatalog() {
        // Unread episodes remain -> refresh even though a total is already known.
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = EpisodeTotal.Exact(10),
                lastReadOrder = 3,
                thumbUrl = "thumb",
                isFavorite = false,
            ),
        ).isTrue()

        // Fully read and known -> nothing to refresh.
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = EpisodeTotal.Exact(10),
                lastReadOrder = 10,
                thumbUrl = "thumb",
                isFavorite = false,
            ),
        ).isFalse()

        // An unknown total is itself an unread signal, so no separate "needsCatalog" flag is needed.
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = EpisodeTotal.Unknown,
                lastReadOrder = 10,
                thumbUrl = "thumb",
                isFavorite = false,
            ),
        ).isTrue()

        // A lower bound never proves the work is finished.
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = EpisodeTotal.AtLeast(10),
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
                knownTotal = EpisodeTotal.Exact(10),
                lastReadOrder = 10,
                thumbUrl = "",
                isFavorite = false,
            ),
        ).isTrue()
        assertThat(
            RepositoryTransforms.needsEpisodeSync(
                knownTotal = EpisodeTotal.Exact(10),
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
