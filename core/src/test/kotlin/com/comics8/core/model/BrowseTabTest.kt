package com.comics8.core.model

import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowseTabTest {
    @Test
    fun notificationTargetDefaultsToFavorite() {
        val tabs = BrowseTab.forSource(SourceRegistry.forTests().get("eleven"))
        val favorite = BrowseTab.Favorite(WorkId.DEFAULT_SOURCE)
        assertThat(BrowseTab.resolveLaunchTarget(null, tabs)).isEqualTo(favorite)
        assertThat(BrowseTab.resolveLaunchTarget(BrowseTab.FAVORITE_ID, tabs)).isEqualTo(favorite)
        val latest = tabs.first { it is BrowseTab.Remote && it.catalog.id == "LATEST" }
        assertThat(BrowseTab.resolveLaunchTarget("LATEST", tabs)).isEqualTo(latest)
    }

    @Test
    fun favoriteTabSitsAfterLatest() {
        val hitomi = BrowseTab.forSource(SourceRegistry.forTests().get("hitomi"))
        val labels = hitomi.map { it.label }
        assertThat(labels.take(2)).containsExactly("최신", "즐겨찾기").inOrder()
        assertThat((hitomi[1] as BrowseTab.Favorite).sourceId).isEqualTo("hitomi")
        val eleven = BrowseTab.forSource(SourceRegistry.forTests().get("eleven"))
        assertThat(eleven.map { it.label }.take(2)).containsExactly("최신", "즐겨찾기").inOrder()
    }

    @Test
    fun afterSourceChangeKeepsFavoriteOrMatchingCatalog() {
        val eleven = BrowseTab.forSource(SourceRegistry.forTests().get("eleven"))
        val hitomi = BrowseTab.forSource(SourceRegistry.forTests().get("hitomi"))
        assertThat(BrowseTab.afterSourceChange(BrowseTab.Favorite(WorkId.DEFAULT_SOURCE), hitomi))
            .isEqualTo(BrowseTab.Favorite("hitomi"))
        val elevenLatest = BrowseTab.Remote(
            WorkId.DEFAULT_SOURCE,
            SourceCatalog("LATEST", "최신", paginated = true),
        )
        val next = BrowseTab.afterSourceChange(elevenLatest, hitomi)
        assertThat(next).isInstanceOf(BrowseTab.Remote::class.java)
        assertThat((next as BrowseTab.Remote).catalog.id).isEqualTo("LATEST")
        assertThat(eleven.any { it is BrowseTab.Remote && it.catalog.id == "COMPLETE" }).isTrue()
    }

    @Test
    fun emptyTabsHaveNoElevenFallback() {
        assertThat(BrowseTab.resolveLaunchTarget(null, emptyList())).isNull()
        assertThat(BrowseTab.resolveLaunchTarget(BrowseTab.FAVORITE_ID, emptyList())).isNull()
        assertThat(BrowseTab.afterSourceChange(BrowseTab.Favorite(WorkId.DEFAULT_SOURCE), emptyList()))
            .isNull()
        assertThat(BrowseTab.afterSourceChange(null, emptyList())).isNull()
    }
}
