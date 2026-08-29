package com.comics8.core.model

data class PlatformUpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val windowsDownloadUrl: String = "",
    val releaseNotes: String = "",
)

data class VersionResponse(
    val android: PlatformUpdateInfo? = null,
    val desktop: PlatformUpdateInfo? = null,
    val serverUrl: String? = null,
)

data class AppUpdateState(
    val isChecking: Boolean = false,
    val hasUpdate: Boolean = false,
    val currentVersion: String = "",
    val currentVersionCode: Int = 0,
    val latestVersion: String = "",
    val latestVersionCode: Int = 0,
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val statusMessage: String? = null,
    val error: String? = null,
    val newServerUrl: String? = null,
)
