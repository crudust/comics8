package com.comics8.core.model

import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowseTabTest {
    @Test
    fun notificationTargetDefaultsToFavorite() {
        val tabs = BrowseTab.forSource(SourceRegistry.defaults().default)
        assertThat(BrowseTab.resolveLaunchTarget(null, tabs)).isEqualTo(BrowseTab.Favorite)
        assertThat(BrowseTab.resolveLaunchTarget(BrowseTab.FAVORITE_ID, tabs)).isEqualTo(BrowseTab.Favorite)
        val latest = tabs.first { it is BrowseTab.Remote && it.catalog.id == "LATEST" }
        assertThat(BrowseTab.resolveLaunchTarget("LATEST", tabs)).isEqualTo(latest)
    }

    @Test
    fun afterSourceChangeKeepsFavoriteOrMatchingCatalog() {
        val eleven = BrowseTab.forSource(SourceRegistry.defaults().default)
        val hitomi = BrowseTab.forSource(SourceRegistry.defaults().get("hitomi"))
        assertThat(BrowseTab.afterSourceChange(BrowseTab.Favorite, hitomi)).isEqualTo(BrowseTab.Favorite)
        val elevenLatest = BrowseTab.Remote(
            WorkId.DEFAULT_SOURCE,
            SourceCatalog("LATEST", "최신", paginated = true),
        )
        val next = BrowseTab.afterSourceChange(elevenLatest, hitomi)
        assertThat(next).isInstanceOf(BrowseTab.Remote::class.java)
        assertThat((next as BrowseTab.Remote).catalog.id).isEqualTo("LATEST")
        assertThat(eleven.any { it is BrowseTab.Remote && it.catalog.id == "COMPLETE" }).isTrue()
    }
}
