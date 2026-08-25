package com.comics8.core.sync

object SyncConstants {
    const val KEY_SYNC_KEY = "sync_key"
    const val KEY_SERVER_URL = "sync_server_url"
    const val KEY_AUTO_SYNC = "sync_auto_enabled"
    const val KEY_LAST_SYNCED_AT = "sync_last_at"
    const val KEY_USE_SERVER_PROXY = "sync_use_server_proxy"
    const val DEFAULT_SERVER_URL = "https://homeassistant.tail1946af.ts.net/api/comics8/sync"

    fun generateSyncKey(): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val part1 = (1..4).map { chars.random() }.joinToString("")
        val part2 = (1..4).map { chars.random() }.joinToString("")
        val part3 = (1..4).map { chars.random() }.joinToString("")
        return "C8-$part1-$part2-$part3"
    }

    fun apiRoot(serverUrl: String = DEFAULT_SERVER_URL): String {
        val base = serverUrl.trimEnd('/')
        return when {
            base.endsWith("/sync") -> base.substringBeforeLast("/sync")
            base.endsWith("/proxy") -> base.substringBeforeLast("/proxy")
            base.endsWith("/version") -> base.substringBeforeLast("/version")
            base.endsWith("/health") -> base.substringBeforeLast("/health")
            base.endsWith("/pair") -> base.substringBeforeLast("/pair")
            else -> base
        }.trimEnd('/')
    }

    fun pairRequestUrl(serverUrl: String = DEFAULT_SERVER_URL): String = "${apiRoot(serverUrl)}/pair/request"

    fun pairConfirmUrl(serverUrl: String = DEFAULT_SERVER_URL): String = "${apiRoot(serverUrl)}/pair/confirm"

    fun versionUrl(serverUrl: String = DEFAULT_SERVER_URL): String = "${apiRoot(serverUrl)}/version"

    fun healthUrl(serverUrl: String = DEFAULT_SERVER_URL): String = "${apiRoot(serverUrl)}/health"

    fun proxyBaseUrl(serverUrl: String = DEFAULT_SERVER_URL): String = "${apiRoot(serverUrl)}/proxy"

    fun proxyUrl(targetUrl: String, serverUrl: String = DEFAULT_SERVER_URL): String {
        val proxyBase = proxyBaseUrl(serverUrl)
        val encodedTarget = java.net.URLEncoder.encode(targetUrl, "UTF-8")
        return "$proxyBase?url=$encodedTarget"
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
