package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeSortOrderTest {

    private fun ep(id: String, title: String, date: String? = null, mtime: Long? = null) =
        EpisodeItem(
            wrId = id,
            title = title,
            date = date,
            thumbUrl = null,
            href = "comics8://$id",
            mtime = mtime,
        )

    @Test
    fun preservesSourceOrderOnDefault() {
        val list = listOf(
            ep("1", "24-1화"),
            ep("2", "24-2화"),
            ep("3", "23-1화"),
            ep("4", "23-2화"),
        )
        val sorted = list.sortedWithOrder(EpisodeSortOrder.DEFAULT)
        assertThat(sorted.map { it.title }).containsExactly("24-1화", "24-2화", "23-1화", "23-2화").inOrder()
    }

    @Test
    fun sortsByNameAscendingNaturalSort() {
        val list = listOf(
            ep("1", "10화"),
            ep("2", "1화"),
            ep("3", "2화"),
            ep("4", "20화"),
        )
        val sorted = list.sortedWithOrder(EpisodeSortOrder.NAME_ASC)
        assertThat(sorted.map { it.title }).containsExactly("1화", "2화", "10화", "20화").inOrder()
    }

    @Test
    fun sortsByNameDescendingNaturalSort() {
        val list = listOf(
            ep("1", "10화"),
            ep("2", "1화"),
            ep("3", "2화"),
            ep("4", "20화"),
        )
        val sorted = list.sortedWithOrder(EpisodeSortOrder.NAME_DESC)
        assertThat(sorted.map { it.title }).containsExactly("20화", "10화", "2화", "1화").inOrder()
    }

    @Test
    fun sortsByDateDescendingNewestFirst() {
        val list = listOf(
            ep("1", "1화", date = "24.01.01", mtime = 1000L),
            ep("2", "2화", date = "24.05.01", mtime = 5000L),
            ep("3", "3화", date = "24.03.01", mtime = 3000L),
        )
        val sorted = list.sortedWithOrder(EpisodeSortOrder.DATE_DESC)
        assertThat(sorted.map { it.title }).containsExactly("2화", "3화", "1화").inOrder()
    }

    @Test
    fun sortsByDateDescendingWithNaturalSortDescendingTiebreaker() {
        val list = listOf(
            ep("1", "24-1화", date = "26.09.01"),
            ep("2", "24-2화", date = "26.09.01"),
            ep("3", "23-1화", date = "26.08.30"),
            ep("4", "23-2화", date = "26.08.30"),
        )
        val sorted = list.sortedWithOrder(EpisodeSortOrder.DATE_DESC)
        assertThat(sorted.map { it.title })
            .containsExactly("24-2화", "24-1화", "23-2화", "23-1화")
            .inOrder()
    }

    @Test
    fun sortsByDateAscendingOldestFirst() {
        val list = listOf(
            ep("1", "1화", date = "24.01.01", mtime = 1000L),
            ep("2", "2화", date = "24.05.01", mtime = 5000L),
            ep("3", "3화", date = "24.03.01", mtime = 3000L),
        )
        val sorted = list.sortedWithOrder(EpisodeSortOrder.DATE_ASC)
        assertThat(sorted.map { it.title }).containsExactly("1화", "3화", "2화").inOrder()
    }

    @Test
    fun sortsByDateAscendingWithNaturalSortAscendingTiebreaker() {
        val list = listOf(
            ep("1", "1-2화", date = "24.01.01"),
            ep("2", "1-1화", date = "24.01.01"),
            ep("3", "2-2화", date = "24.02.01"),
            ep("4", "2-1화", date = "24.02.01"),
        )
        val sorted = list.sortedWithOrder(EpisodeSortOrder.DATE_ASC)
        assertThat(sorted.map { it.title })
            .containsExactly("1-1화", "1-2화", "2-1화", "2-2화")
            .inOrder()
    }

    @Test
    fun fromKeyParsesFallbackCorrectly() {
        assertThat(EpisodeSortOrder.fromKey("default")).isEqualTo(EpisodeSortOrder.DEFAULT)
        assertThat(EpisodeSortOrder.fromKey("name_asc")).isEqualTo(EpisodeSortOrder.NAME_ASC)
        assertThat(EpisodeSortOrder.fromKey("name_desc")).isEqualTo(EpisodeSortOrder.NAME_DESC)
        assertThat(EpisodeSortOrder.fromKey("date_desc")).isEqualTo(EpisodeSortOrder.DATE_DESC)
        assertThat(EpisodeSortOrder.fromKey("date_asc")).isEqualTo(EpisodeSortOrder.DATE_ASC)
        assertThat(EpisodeSortOrder.fromKey("unknown")).isEqualTo(EpisodeSortOrder.DEFAULT)
        assertThat(EpisodeSortOrder.fromKey(null)).isEqualTo(EpisodeSortOrder.DEFAULT)
    }
}
