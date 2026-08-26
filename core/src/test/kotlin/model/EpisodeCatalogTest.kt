package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EpisodeCatalogTest {
    @Test
    fun singlePageReturnsThatPage() = runBlocking<Unit> {
        val items = listOf(episode("1"), episode("2"))
        val loaded = EpisodeCatalog.loadAllPages { page ->
            assertThat(page).isEqualTo(1)
            EpisodePage(items, currentPage = 1, lastPage = 1)
        }
        assertThat(loaded.map { it.wrId }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun walksEveryPageInOrder() = runBlocking<Unit> {
        val pages = mapOf(
            1 to listOf(episode("a"), episode("b")),
            2 to listOf(episode("c")),
            3 to listOf(episode("d"), episode("e")),
        )
        val seen = mutableListOf<Int>()
        val loaded = EpisodeCatalog.loadAllPages { page ->
            synchronized(seen) { seen += page }
            EpisodePage(pages.getValue(page), currentPage = page, lastPage = 3)
        }
        assertThat(seen.first()).isEqualTo(1)
        assertThat(seen).containsExactly(1, 2, 3)
        assertThat(loaded.map { it.wrId }).containsExactly("a", "b", "c", "d", "e").inOrder()
    }

    @Test
    fun fromOfflineMarksReadProgress() {
        val items = EpisodeCatalog.fromOffline(
            rows = listOf(
                OfflineEpisodeRef("1", "1화", "/1", date = "26.08.18"),
                OfflineEpisodeRef("2", "2화", "/2"),
            ),
            readAtByWrId = mapOf("1" to 10L),
            lastPageByWrId = mapOf("1" to 3),
        )
        assertThat(items).hasSize(2)
        assertThat(items[0].isRead).isTrue()
        assertThat(items[0].readAt).isEqualTo(10L)
        assertThat(items[0].lastReadPage).isEqualTo(3)
        assertThat(items[1].isRead).isFalse()
        assertThat(items[1].href).isEqualTo("/2")
    }

    private fun episode(id: String) = EpisodeItem(
        wrId = id,
        title = id,
        date = null,
        thumbUrl = null,
        href = "/$id",
    )
}
