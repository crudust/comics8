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
        // 1. Try GitHub Releases API first (public CDN)
        val githubUrl = "https://api.github.com/repos/crudust/comics8/releases/latest"
        val fromGithub = tryFetchGithubRelease(githubUrl)
        if (fromGithub != null) return fromGithub

        // 2. Fallback to custom server / legacy version.json
        val url = SyncConstants.versionUrl(serverUrl)
        return tryFetchLegacyVersion(url)
    }

    private fun tryFetchGithubRelease(url: String): VersionResponse? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Comics8/UpdateChecker")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            val jsonStr = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            } ?: return null

            val root = JSONObject(jsonStr)
            val tagName = root.optString("tag_name", "").removePrefix("v")
            if (tagName.isBlank()) return null
            val releaseNotes = root.optString("body", "")

            var apkUrl = ""
            var macUrl = ""
            var winUrl = ""

            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").lowercase()
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = downloadUrl
                    } else if (name.contains("mac") && name.endsWith(".zip")) {
                        macUrl = downloadUrl
                    } else if (name.contains("win") && name.endsWith(".zip")) {
                        winUrl = downloadUrl
                    }
                }
            }

            val androidInfo = PlatformUpdateInfo(
                versionCode = 0,
                versionName = tagName,
                downloadUrl = apkUrl,
                releaseNotes = releaseNotes,
            )
            val desktopInfo = PlatformUpdateInfo(
                versionCode = 0,
                versionName = tagName,
                downloadUrl = macUrl,
                windowsDownloadUrl = winUrl,
                releaseNotes = releaseNotes,
            )
            VersionResponse(android = androidInfo, desktop = desktopInfo)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryFetchLegacyVersion(url: String): VersionResponse? {
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
        val info = response.android ?: return AppUpdateState(
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
            error = "Android 버전 정보가 없습니다",
        )

        val hasUpdate = isNewerVersion(
            remoteVersion = info.versionName,
            remoteCode = info.versionCode,
            currentVersion = currentVersionName,
            currentCode = currentVersionCode,
        )

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
            error = "업데이트 정보를 확인할 수 없습니다",
        )
        val info = response.desktop ?: return AppUpdateState(
            currentVersion = currentVersionName,
            currentVersionCode = currentVersionCode,
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
