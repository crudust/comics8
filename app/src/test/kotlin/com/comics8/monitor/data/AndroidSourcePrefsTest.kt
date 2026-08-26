package com.comics8.monitor.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AndroidSourcePrefsTest {
    @Test
    fun libraryRootsPersistTreeUris() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("android-source-prefs-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = AndroidSourcePrefs(prefs)
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AComics"
        settings.setLibraryRoots(listOf(uri))
        assertThat(settings.libraryRoots()).containsExactly(uri)
        settings.setEnabled(WorkId.LOCAL_SOURCE, true)
        assertThat(settings.installedIds()).contains(WorkId.LOCAL_SOURCE)

        val customOrder = linkedSetOf("local", "smb:nas", "webdav:drive", "hitomi", "eleven")
        settings.setInstalledIds(customOrder)
        assertThat(settings.installedIds().toList()).containsExactly("local", "smb:nas", "webdav:drive", "hitomi", "eleven").inOrder()
    }

    @Test
    fun notificationPreferencesDefaultAndPersist() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("android-source-prefs-test-notif", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = AndroidSourcePrefs(prefs)

        // 기본값 확인
        assertThat(settings.globalNotificationEnabled()).isTrue()
        assertThat(settings.notificationIntervalMinutes()).isEqualTo(60L)
        assertThat(settings.isNotificationEnabled("hitomi")).isTrue()

        // 설정 변경 및 영속화 확인
        settings.setGlobalNotificationEnabled(false)
        assertThat(settings.globalNotificationEnabled()).isFalse()

        settings.setNotificationIntervalMinutes(30L)
        assertThat(settings.notificationIntervalMinutes()).isEqualTo(30L)

        // 최소 간격 15분 보정 확인
        settings.setNotificationIntervalMinutes(5L)
        assertThat(settings.notificationIntervalMinutes()).isEqualTo(15L)

        settings.setNotificationEnabled("hitomi", false)
        assertThat(settings.isNotificationEnabled("hitomi")).isFalse()
        settings.setNotificationEnabled("hitomi", true)
        assertThat(settings.isNotificationEnabled("hitomi")).isTrue()
    }
}
