package com.comics8.core.source.network

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

object NetworkImageUri {
    const val SCHEME = "comics8-net"

    enum class PreviewKind { ZIP_FIRST, FOLDER_FIRST }

    data class Ref(
        val sourceId: String,
        val path: String,
        val zipEntry: String? = null,
        val size: Long = -1L,
        val preview: PreviewKind? = null,
        val modifiedAt: Long = 0L,
        val thumbnailPx: Int = 0,
    )

    fun encode(
        sourceId: String,
        path: String,
        zipEntry: String? = null,
        size: Long = -1L,
        preview: PreviewKind? = null,
        modifiedAt: Long = 0L,
        thumbnailPx: Int = 0,
    ): String {
        val json = JSONObject().put("source", sourceId).put("path", path)
        if (zipEntry != null) json.put("entry", zipEntry)
        if (size >= 0L) json.put("size", size)
        if (preview != null) json.put("preview", preview.name)
        if (modifiedAt > 0L) json.put("modifiedAt", modifiedAt)
        if (thumbnailPx > 0) json.put("thumbnailPx", thumbnailPx)
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
            val preview = json.optString("preview").takeIf(String::isNotBlank)
                ?.let { PreviewKind.valueOf(it) }
            val modifiedAt = json.optLong("modifiedAt")
            val thumbnailPx = json.optInt("thumbnailPx")
            if (!source.startsWith("network-") || path.isBlank()) null else {
                Ref(source, path, entry, size, preview, modifiedAt, thumbnailPx)
            }
        } catch (_: Exception) {
            null
        }
    }
}
