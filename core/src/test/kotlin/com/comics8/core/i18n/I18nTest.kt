package com.comics8.core.i18n

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.SourceType
import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class I18nTest {
    @Test
    fun testLanguageResolution() {
        assertThat(AppLanguage.resolve(AppLanguage.AUTO, "ko-KR")).isEqualTo(AppLanguage.KO)
        assertThat(AppLanguage.resolve(AppLanguage.AUTO, "ja-JP")).isEqualTo(AppLanguage.JA)
        assertThat(AppLanguage.resolve(AppLanguage.AUTO, "en-US")).isEqualTo(AppLanguage.EN)
        assertThat(AppLanguage.resolve(AppLanguage.AUTO, "zh-CN")).isEqualTo(AppLanguage.ZH_CN)
        assertThat(AppLanguage.resolve(AppLanguage.AUTO, "zh-TW")).isEqualTo(AppLanguage.ZH_TW)
        assertThat(AppLanguage.resolve(AppLanguage.AUTO, "zh-HK")).isEqualTo(AppLanguage.ZH_TW)
        assertThat(AppLanguage.resolve(AppLanguage.AUTO, "fr-FR")).isEqualTo(AppLanguage.KO)

        // Explicit choice overrides system locale
        assertThat(AppLanguage.resolve(AppLanguage.EN, "ko-KR")).isEqualTo(AppLanguage.EN)
    }

    @Test
    fun testAllLanguagesImplementAllKeys() {
        val languages = listOf(
            KoStrings,
            EnStrings,
            JaStrings,
            ZhCnStrings,
            ZhTwStrings,
        )

        for (lang in languages) {
            assertThat(lang.tabFavorite).isNotEmpty()
            assertThat(lang.actionBack).isNotEmpty()
            assertThat(lang.actionConfirm).isNotEmpty()
            assertThat(lang.actionCancel).isNotEmpty()
            assertThat(lang.navSettings).isNotEmpty()
            assertThat(lang.titleOfflineDownload).isNotEmpty()
            assertThat(lang.viewModeScroll).isNotEmpty()
            assertThat(lang.searchResultCount(5)).isNotEmpty()
            assertThat(lang.downloadTotalEpisodes(10)).isNotEmpty()
        }
    }

    @Test
    fun testI18nExtensions() {
        val en = EnStrings
        val ja = JaStrings

        // BrowseTab
        val favoriteTab = BrowseTab.Favorite("local")
        assertThat(favoriteTab.displayLabel(en)).isEqualTo("Favorites")
        assertThat(favoriteTab.displayLabel(ja)).isEqualTo("お気に入り")

        val libraryTab = BrowseTab.Remote("local", SourceCatalog("LIBRARY", "보관함", paginated = true))
        assertThat(libraryTab.displayLabel(en)).isEqualTo("Library")
        assertThat(libraryTab.displayLabel(ja)).isEqualTo("本棚")

        val latestTab = BrowseTab.Remote("local", SourceCatalog("LATEST", "최신순", paginated = true))
        assertThat(latestTab.displayLabel(en)).isEqualTo("Latest")
        assertThat(latestTab.displayLabel(ja)).isEqualTo("最新順")

        // External JS source catalog should preserve original label as-is
        val externalTab = BrowseTab.Remote("rawkuma", SourceCatalog("LATEST", "Recent Updates", paginated = true))
        assertThat(externalTab.displayLabel(en)).isEqualTo("Recent Updates")
        assertThat(externalTab.displayLabel(ja)).isEqualTo("Recent Updates")

        // SourceRegistry & displaySourceTitle
        val registry = SourceRegistry()
        assertThat(registry.displaySourceTitle(WorkId.LOCAL_SOURCE, en)).isEqualTo("Storage")
        assertThat(registry.displaySourceTitle(WorkId.LOCAL_SOURCE, ja)).isEqualTo("ストレージ")
        assertThat(registry.displaySourceTitle(null, en)).isEqualTo("Storage")

        // ProgressDisplayMode & SourceType
        assertThat(ProgressDisplayMode.LATEST_EPISODE.displayLabel(en)).isEqualTo("Latest Chapter")
        assertThat(SourceType.LOCAL.displayLabel(en)).isEqualTo("Local Storage")
    }
}
