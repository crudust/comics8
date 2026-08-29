package com.comics8.core.sync

import com.comics8.core.network.FallbackDns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ServerDiscovery {
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(FallbackDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun fetchRemoteServerUrl(discoveryUrl: String = SyncConstants.REMOTE_DISCOVERY_URL): String? {
        return try {
            val request = Request.Builder()
                .url(discoveryUrl)
                .header("User-Agent", "Comics8/ServerDiscovery")
                .get()
                .build()
            val jsonStr = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            } ?: return null

            val obj = JSONObject(jsonStr)
            val url = obj.optString("serverUrl", "").trim()
            if (url.startsWith("http://") || url.startsWith("https://")) url else null
        } catch (_: Exception) {
            null
        }
    }
}
