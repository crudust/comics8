package com.comics8.core.source.network

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

object NetworkImageUri {
    const val SCHEME = "comics8-net"

    data class Ref(
        val sourceId: String,
        val path: String,
        val zipEntry: String? = null,
        val size: Long = -1L,
    )

    fun encode(sourceId: String, path: String, zipEntry: String? = null, size: Long = -1L): String {
        val json = JSONObject().put("source", sourceId).put("path", path)
        if (zipEntry != null) json.put("entry", zipEntry)
        if (size >= 0L) json.put("size", size)
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
        return "$SCHEME:$payload"
    }

    fun parse(url: String): Ref? {
        if (!url.startsWith("$SCHEME:", ignoreCase = true)) return null
        return try {
            val payload = url.substringAfter(':')
            val json = JSONObject(String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8))
            val source = json.getString("source")
            val path = NetworkSourceConfig.normalizePath(json.getString("path"))
            val entry = json.optString("entry").ifBlank { null }
            val size = json.optLong("size", -1L)
            if (!source.startsWith("network:") || path.isBlank()) null else Ref(source, path, entry, size)
        } catch (_: Exception) {
            null
        }
    }
}
