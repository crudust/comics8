package com.comics8.core.i18n

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.PageTapZone
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

            // 2-depth settings strings
            assertThat(lang.sectionGeneralSettings).isNotEmpty()
            assertThat(lang.descGeneralSettings).isNotEmpty()
            assertThat(lang.descViewerSettings).isNotEmpty()
            assertThat(lang.descNotificationSettings).isNotEmpty()
            assertThat(lang.sectionSyncAndBackup).isNotEmpty()
            assertThat(lang.descSyncAndBackup).isNotEmpty()
            assertThat(lang.sectionNetworkAndDownload).isNotEmpty()
            assertThat(lang.descNetworkAndDownload).isNotEmpty()
            assertThat(lang.sectionAppInfoAndAbout).isNotEmpty()
            assertThat(lang.descAppInfoAndAbout).isNotEmpty()

            // Page Tap Zone & Volume Key & Quick Settings strings
            assertThat(lang.labelPageTapZone).isNotEmpty()
            assertThat(lang.descPageTapZone).isNotEmpty()
            assertThat(lang.pageTapZoneDirection).isNotEmpty()
            assertThat(lang.descPageTapZoneDirection).isNotEmpty()
            assertThat(lang.pageTapZoneRightNext).isNotEmpty()
            assertThat(lang.descPageTapZoneRightNext).isNotEmpty()
            assertThat(lang.pageTapZoneLeftNext).isNotEmpty()
            assertThat(lang.descPageTapZoneLeftNext).isNotEmpty()
            assertThat(lang.labelVolumePageTurn).isNotEmpty()
            assertThat(lang.descVolumePageTurn).isNotEmpty()
            assertThat(lang.titleQuickSettings).isNotEmpty()
        }
    }

    @Test
    fun testI18nExtensions() {
        val ko = KoStrings
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

        // PageTapZone
        assertThat(PageTapZone.FOLLOW_DIRECTION.displayLabel(ko)).isEqualTo("읽기 방향 연동 (기본값)")
        assertThat(PageTapZone.FOLLOW_DIRECTION.displayLabel(en)).isEqualTo("Follow Reading Direction (Default)")
        assertThat(PageTapZone.FOLLOW_DIRECTION.displayLabel(ja)).isEqualTo("読み方向に連動 (デフォルト)")
        assertThat(PageTapZone.RIGHT_NEXT.displayLabel(en)).isEqualTo("Right Next / Left Prev")
        assertThat(PageTapZone.LEFT_NEXT.displayLabel(en)).isEqualTo("Left Next / Right Prev")
        assertThat(PageTapZone.FOLLOW_DIRECTION.displayDescription(ko)).isEqualTo("좌우/우좌 읽기 방향에 따라 다음 페이지 영역이 자동 변경됩니다.")
        assertThat(PageTapZone.RIGHT_NEXT.displayDescription(en)).isEqualTo("Always tap right for next page, tap left for previous page.")
        assertThat(PageTapZone.LEFT_NEXT.displayDescription(ja)).isEqualTo("常に左をタップすると次のページ、右をタップすると前のページに移動します。")

        // ViewMode
        assertThat(com.comics8.core.model.ViewMode.SCROLL.displayLabel(ko)).isEqualTo("세로 스크롤")
        assertThat(com.comics8.core.model.ViewMode.SCROLL.displayLabel(en)).isEqualTo("Webtoon (Scroll)")
        assertThat(com.comics8.core.model.ViewMode.SCROLL.displayLabel(ja)).isEqualTo("縦スクロール (Webtoon)")
        assertThat(com.comics8.core.model.ViewMode.SCROLL.displayShortLabel(ko)).isEqualTo("스크롤")
        assertThat(com.comics8.core.model.ViewMode.SCROLL.displayShortLabel(en)).isEqualTo("Scroll")

        // ReadDirection
        assertThat(com.comics8.core.model.ReadDirection.RIGHT_TO_LEFT.displayLabel(ko)).isEqualTo("오른쪽 → 왼쪽 (만화)")
        assertThat(com.comics8.core.model.ReadDirection.RIGHT_TO_LEFT.displayShortLabel(ko)).isEqualTo("우→좌")
        assertThat(com.comics8.core.model.ReadDirection.RIGHT_TO_LEFT.displayShortLabel(en)).isEqualTo("R → L")

        // Notification Interval
        assertThat(formatNotificationInterval(15L, ko)).isEqualTo("15분")
        assertThat(formatNotificationInterval(60L, ko)).isEqualTo("1시간")
        assertThat(formatNotificationInterval(60L, en)).isEqualTo("1 hour")
        assertThat(formatNotificationInterval(60L, ja)).isEqualTo("1時間")
        assertThat(formatNotificationInterval(180L, ko)).isEqualTo("3시간")
        assertThat(formatNotificationInterval(180L, en)).isEqualTo("3 hours")
        assertThat(formatNotificationInterval(1440L, ko)).isEqualTo("24시간")
        assertThat(formatNotificationInterval(1440L, en)).isEqualTo("24 hours")

        // Relative Time Formatting
        val baseMs = 1_000_000_000_000L
        assertThat(formatRelativeTime(baseMs - 10_000L, ko, nowMs = baseMs)).isEqualTo("방금 전")
        assertThat(formatRelativeTime(baseMs - 5 * 60_000L, ko, nowMs = baseMs)).isEqualTo("5분 전")
        assertThat(formatRelativeTime(baseMs - 3 * 3600_000L, ko, nowMs = baseMs)).isEqualTo("3시간 전")
        assertThat(formatRelativeTime(baseMs - 2 * 86400_000L, ko, nowMs = baseMs)).isEqualTo("2일 전")
        assertThat(formatRelativeTime(baseMs - 10 * 86400_000L, ko, nowMs = baseMs)).isNotEmpty()
        assertThat(formatRelativeTime(0L, ko, nowMs = baseMs)).isEmpty()
    }
}
