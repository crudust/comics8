package com.comics8.core.i18n

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
}
