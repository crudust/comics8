package com.comics8.core.sync

import com.google.common.truth.Truth.assertThat
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
}
