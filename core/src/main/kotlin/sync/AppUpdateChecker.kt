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
        // Always discover the latest active server URL from GitHub first
        val activeServerUrl = ServerDiscovery.fetchRemoteServerUrl() ?: serverUrl
        val url = SyncConstants.versionUrl(activeServerUrl)
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
            val remoteServerUrl = root.optString("serverUrl", "").trim().ifBlank { activeServerUrl }

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
            VersionResponse(
                android = androidInfo,
                desktop = desktopInfo,
                serverUrl = remoteServerUrl,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isNewerVersion(remoteVersion: String, remoteCode: Int, currentVersion: String, currentCode: Int): Boolean {
        if (remoteCode > 0 && currentCode > 0) {
            return remoteCode > currentCode
        }
        val remoteParts = remoteVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    fun checkAndroidUpdate(
        currentVersionName: String,
        currentVersionCode: Int,
        serverUrl: String = SyncConstants.DEFAULT_SERVER_URL,
    ): AppUpdateState {
        val response = fetchVersionInfo(serverUrl) ?: return AppUpdateState(
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            error = "업데이트 정보를 확인할 수 없습니다",
        )
        val effectiveServerUrl = response.serverUrl ?: serverUrl
        val info = response.android ?: return AppUpdateState(
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            newServerUrl = response.serverUrl,
            error = "Android 버전 정보가 없습니다",
        )

        val hasUpdate = isNewerVersion(
            remoteVersion = info.versionName,
            remoteCode = info.versionCode,
            currentVersion = currentVersionName,
            currentCode = currentVersionCode,
        )

        val resolvedUrl = SyncConstants.resolveDownloadUrl(info.downloadUrl, effectiveServerUrl)

        return AppUpdateState(
            hasUpdate = hasUpdate,
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            latestVersion = info.versionName,
            latestVersionCode = info.versionCode,
            downloadUrl = resolvedUrl,
            releaseNotes = info.releaseNotes,
            newServerUrl = response.serverUrl,
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
            error = "업데이트 정보를 확인할 수 없습니다",
        )
        val effectiveServerUrl = response.serverUrl ?: serverUrl
        val info = response.desktop ?: return AppUpdateState(
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            newServerUrl = response.serverUrl,
            error = "Desktop 버전 정보가 없습니다",
        )

        val hasUpdate = isNewerVersion(
            remoteVersion = info.versionName,
            remoteCode = info.versionCode,
            currentVersion = currentVersionName,
            currentCode = currentVersionCode,
        )

        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        val rawUrl = if (isWindows && info.windowsDownloadUrl.isNotBlank()) {
            info.windowsDownloadUrl
        } else {
            info.downloadUrl
        }
        val resolvedUrl = SyncConstants.resolveDownloadUrl(rawUrl, effectiveServerUrl)

        return AppUpdateState(
            hasUpdate = hasUpdate,
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            latestVersion = info.versionName,
            latestVersionCode = info.versionCode,
            downloadUrl = resolvedUrl,
            releaseNotes = info.releaseNotes,
            newServerUrl = response.serverUrl,
        )
    }
}
