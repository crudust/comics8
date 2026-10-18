package com.comics8.core.model

data class PlatformUpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val windowsDownloadUrl: String = "",
    val releaseNotes: String = "",
)

/**
 * 릴리즈 이력 한 건. 서버 version.json 의 `history` 배열 항목과 1:1 대응합니다.
 * [platform] 은 `all` | `android` | `desktop` 중 하나이며, `all` 은 두 플랫폼 모두에 노출됩니다.
 */
data class ReleaseHistoryEntry(
    val versionName: String = "",
    val versionCode: Int = 0,
    val date: String = "",
    val platform: String = "all",
    val releaseNotes: String = "",
)

data class VersionResponse(
    val android: PlatformUpdateInfo? = null,
    val desktop: PlatformUpdateInfo? = null,
    val serverUrl: String? = null,
    val history: List<ReleaseHistoryEntry> = emptyList(),
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
    val history: List<ReleaseHistoryEntry> = emptyList(),
)
