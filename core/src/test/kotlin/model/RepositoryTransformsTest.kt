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
    fun estimateTotalEpisodes_usesLastPageCountOrFirstPageFallback() {
        val first = EpisodePage(
            items = listOf(episode("1", "one"), episode("2", "two")),
            currentPage = 1,
            lastPage = 4,
        )

        assertThat(RepositoryTransforms.estimateTotalEpisodes(first, pageSize = 20, lastPageItemCount = 3))
            .isEqualTo(63)
        assertThat(RepositoryTransforms.estimateTotalEpisodes(first, pageSize = 20))
            .isEqualTo(62)
        assertThat(
            RepositoryTransforms.estimateTotalEpisodes(
                EpisodePage(emptyList(), currentPage = 1, lastPage = 8),
                pageSize = 20,
            ),
        ).isEqualTo(0)
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
        ) { _, history, readCount -> "${history.first}/${history.second}/$readCount" }

        assertThat(result[0].isNew).isTrue()
        assertThat(result[0].isFavorite).isTrue()
        assertThat(result[0].readProgress).isEqualTo("2/10/4")
        assertThat(result[1].isNew).isTrue()
        assertThat(result[1].isFavorite).isFalse()
        assertThat(result[1].readProgress).isNull()
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
