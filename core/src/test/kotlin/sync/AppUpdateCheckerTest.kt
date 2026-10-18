package com.comics8.core.sync

import com.comics8.core.model.ReleaseHistoryEntry
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun versionNumberComparisonWorksCorrectly() {
        val method = AppUpdateChecker.javaClass.getDeclaredMethod(
            "isNewerVersion",
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true

        fun check(remoteVer: String, remoteCode: Int, curVer: String, curCode: Int): Boolean {
            return method.invoke(AppUpdateChecker, remoteVer, remoteCode, curVer, curCode) as Boolean
        }

        assertThat(check("1.5.66", 92, "1.5.65", 91)).isTrue()
        assertThat(check("1.5.65", 91, "1.5.66", 92)).isFalse()
        assertThat(check("1.5.65", 91, "1.5.65", 91)).isFalse()

        // fallback to semver when versionCode is 0
        assertThat(check("1.5.66", 0, "1.5.65", 0)).isTrue()
        assertThat(check("1.6.0", 0, "1.5.99", 0)).isTrue()
        assertThat(check("1.5.65", 0, "1.5.66", 0)).isFalse()
    }

    private fun parseHistory(json: String): List<ReleaseHistoryEntry> {
        val method = AppUpdateChecker.javaClass.getDeclaredMethod(
            "parseHistory",
            JSONObject::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(AppUpdateChecker, JSONObject(json)) as List<ReleaseHistoryEntry>
    }

    @Test
    fun historyIsParsedFromVersionJson() {
        val history = parseHistory(
            """
            {
              "history": [
                {"versionName": "0.2.41", "versionCode": 46, "date": "2026-10-18",
                 "platform": "all", "releaseNotes": "refactor(desktop): unify overlay"},
                {"versionName": "0.2.40", "versionCode": 45, "date": "2026-10-18",
                 "platform": "desktop", "releaseNotes": "feat(desktop): reader zoom"}
              ]
            }
            """.trimIndent()
        )

        assertThat(history).hasSize(2)
        assertThat(history[0].versionName).isEqualTo("0.2.41")
        assertThat(history[0].versionCode).isEqualTo(46)
        assertThat(history[0].date).isEqualTo("2026-10-18")
        assertThat(history[0].platform).isEqualTo("all")
        assertThat(history[0].releaseNotes).isEqualTo("refactor(desktop): unify overlay")
        assertThat(history[1].platform).isEqualTo("desktop")
    }

    @Test
    fun missingHistoryKeyYieldsEmptyList() {
        // 구버전 서버는 history 키를 전혀 내려주지 않는다.
        assertThat(parseHistory("""{"android": {"versionCode": 46}}""")).isEmpty()
    }

    @Test
    fun blankPlatformDefaultsToAllAndNamelessEntriesAreSkipped() {
        val history = parseHistory(
            """
            {
              "history": [
                {"versionName": "0.2.41", "versionCode": 46},
                {"versionName": "", "versionCode": 45}
              ]
            }
            """.trimIndent()
        )

        assertThat(history).hasSize(1)
        assertThat(history[0].platform).isEqualTo("all")
        assertThat(history[0].date).isEmpty()
    }

    @Test
    fun historyForPlatformKeepsAllAndMatchingEntriesNewestFirst() {
        val history = listOf(
            ReleaseHistoryEntry("0.2.39", 44, "2026-10-14", "all", "mobile notes"),
            ReleaseHistoryEntry("0.2.41", 46, "2026-10-18", "all", "shared notes"),
            ReleaseHistoryEntry("0.2.40", 45, "2026-10-18", "desktop", "desktop only"),
        )

        val android = AppUpdateChecker.historyForPlatform(history, "android")
        assertThat(android.map { it.versionName }).containsExactly("0.2.41", "0.2.39").inOrder()

        val desktop = AppUpdateChecker.historyForPlatform(history, "desktop")
        assertThat(desktop.map { it.versionName }).containsExactly("0.2.41", "0.2.40", "0.2.39").inOrder()
    }
}
