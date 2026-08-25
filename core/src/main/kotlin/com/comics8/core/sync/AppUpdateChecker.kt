package com.comics8.core.sync

import com.comics8.core.model.AppUpdateState
import com.comics8.core.model.PlatformUpdateInfo
import com.comics8.core.model.VersionResponse
import com.comics8.core.network.FallbackDns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AppUpdateChecker {
    private val httpClient = OkHttpClient.Builder()
        .dns(FallbackDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun fetchVersionInfo(serverUrl: String = SyncConstants.DEFAULT_SERVER_URL): VersionResponse? {
        val url = SyncConstants.versionUrl(serverUrl)
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Comics8/UpdateChecker")
                .get()
                .build()
            val jsonStr = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            } ?: return null

            val root = JSONObject(jsonStr)
            val androidObj = root.optJSONObject("android")
            val desktopObj = root.optJSONObject("desktop")

            val androidInfo = androidObj?.let {
                PlatformUpdateInfo(
                    versionCode = it.optInt("versionCode", 0),
                    versionName = it.optString("versionName", ""),
                    downloadUrl = it.optString("downloadUrl", ""),
                    releaseNotes = it.optString("releaseNotes", ""),
                )
            }
            val desktopInfo = desktopObj?.let {
                PlatformUpdateInfo(
                    versionCode = it.optInt("versionCode", 0),
                    versionName = it.optString("versionName", ""),
                    downloadUrl = it.optString("downloadUrl", ""),
                    windowsDownloadUrl = it.optString("windowsDownloadUrl", ""),
                    releaseNotes = it.optString("releaseNotes", ""),
                )
            }
            VersionResponse(android = androidInfo, desktop = desktopInfo)
        } catch (_: Exception) {
            null
        }
    }

    fun checkAndroidUpdate(
        currentVersionName: String,
        currentVersionCode: Int,
        serverUrl: String = SyncConstants.DEFAULT_SERVER_URL,
    ): AppUpdateState {
        val response = fetchVersionInfo(serverUrl) ?: return AppUpdateState(
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            error = "업데이트 서버에 연결할 수 없습니다",
        )
        val info = response.android ?: return AppUpdateState(
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            error = "서버에 Android 버전 정보가 없습니다",
        )

        val hasUpdate = (info.versionCode > currentVersionCode) ||
            (info.versionCode == 0 && info.versionName.isNotBlank() && info.versionName != currentVersionName)

        val resolvedUrl = SyncConstants.resolveDownloadUrl(info.downloadUrl, serverUrl)

        return AppUpdateState(
            hasUpdate = hasUpdate,
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            latestVersion = info.versionName,
            latestVersionCode = info.versionCode,
            downloadUrl = resolvedUrl,
            releaseNotes = info.releaseNotes,
        )
    }

    fun checkDesktopUpdate(
        currentVersionName: String,
        currentVersionCode: Int,
        serverUrl: String = SyncConstants.DEFAULT_SERVER_URL,
    ): AppUpdateState {
        val response = fetchVersionInfo(serverUrl) ?: return AppUpdateState(
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            error = "업데이트 서버에 연결할 수 없습니다",
        )
        val info = response.desktop ?: return AppUpdateState(
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            error = "서버에 Desktop 버전 정보가 없습니다",
        )

        val hasUpdate = (info.versionCode > currentVersionCode) ||
            (info.versionCode == 0 && info.versionName.isNotBlank() && info.versionName != currentVersionName)

        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        val rawUrl = if (isWindows && info.windowsDownloadUrl.isNotBlank()) {
            info.windowsDownloadUrl
        } else {
            info.downloadUrl
        }
        val resolvedUrl = SyncConstants.resolveDownloadUrl(rawUrl, serverUrl)

        return AppUpdateState(
            hasUpdate = hasUpdate,
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            latestVersion = info.versionName,
            latestVersionCode = info.versionCode,
            downloadUrl = resolvedUrl,
            releaseNotes = info.releaseNotes,
        )
    }
}
