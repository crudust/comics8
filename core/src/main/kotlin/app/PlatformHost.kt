package com.comics8.core.app

import com.comics8.core.i18n.AppStrings
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.AppUpdateState
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ViewMode
import com.comics8.core.source.SourceSettings
import com.comics8.core.storage.KeyValuePrefs
import java.io.File

interface PlatformHost {
    val prefs: KeyValuePrefs
    val sourceSettings: SourceSettings
    val localLibraryIndexFile: File
    fun materializeLocalRoots(): List<File>

    fun clearImageMemory(role: ImageCacheRole)
    fun cancelPendingImagePreviews() {}
    fun clearImageFailures() {}
    fun preloadReaderImages(urls: List<String>) {}
    fun evictReaderImage(url: String)

    fun pickLibraryFolder(): File? = null
    fun onLocalFolderAdded(path: String) {}
    fun onLocalFolderRemoved(path: String) {}

    val appVersionName: String
    val appVersionCode: Int
    val isPlayStoreBuild: Boolean get() = false

    suspend fun checkForAppUpdate(serverUrl: String, strings: AppStrings): AppUpdateState
    suspend fun downloadAndInstallUpdate(downloadUrl: String, onProgress: (Float) -> Unit): Result<Unit>

    fun scheduleBackgroundRefresh(enabled: Boolean, intervalMinutes: Long) {}
    fun checkNotificationPermission(): Boolean = true
    fun initialNotificationEnabled(): Boolean = true
    fun initialNotificationIntervalMinutes(): Long = 60L
    fun setNotificationEnabled(enabled: Boolean) {}
    fun setNotificationIntervalMinutes(minutes: Long) {}

    val persistGlobalReaderDefaults: Boolean get() = false
    val autoSwitchWebtoonInReader: Boolean get() = true
    val markSeenOnFavoriteTab: Boolean get() = true
    val initialLoadWithForce: Boolean get() = false
    val collapseSearchClearsActiveSearch: Boolean get() = false
    val refreshReaderUsesAsRefresh: Boolean get() = false
    val initialViewportWidthDp: Int get() = 0
    val initialViewportHeightDp: Int get() = 0
    val initialViewMode: ViewMode get() = ViewMode.AUTO
    val initialReadDirection: ReadDirection get() = ReadDirection.RIGHT_TO_LEFT
    val initialPacksReady: Boolean get() = true
}
