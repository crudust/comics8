package com.comics8.core.sync

object SyncConstants {
    const val KEY_SYNC_KEY = "sync_key"
    const val KEY_SERVER_URL = "sync_server_url"
    const val KEY_AUTO_SYNC = "sync_auto_enabled"
    const val KEY_LAST_SYNCED_AT = "sync_last_at"
    const val DEFAULT_SERVER_URL = "https://homeassistant.tail1946af.ts.net/api/comics8/sync"

    fun generateSyncKey(): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        return (1..4).map { chars.random() }.joinToString("")
    }

    fun versionUrl(serverUrl: String = DEFAULT_SERVER_URL): String {
        val base = serverUrl.trimEnd('/')
        return if (base.endsWith("/sync")) {
            base.substringBeforeLast("/sync") + "/version"
        } else {
            "$base/version"
        }
    }

    fun resolveDownloadUrl(downloadPath: String, serverUrl: String = DEFAULT_SERVER_URL): String {
        if (downloadPath.startsWith("http://") || downloadPath.startsWith("https://")) {
            return downloadPath
        }
        val origin = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            val uri = java.net.URI(serverUrl)
            "${uri.scheme}://${uri.authority}"
        } else {
            "https://homeassistant.tail1946af.ts.net"
        }
        val cleaned = if (downloadPath.startsWith("/")) downloadPath else "/$downloadPath"
        return "$origin$cleaned"
    }
}
