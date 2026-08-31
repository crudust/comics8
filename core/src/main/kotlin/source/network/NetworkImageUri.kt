package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
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
        val preview: PreviewKind? = null,
        val revision: FileRevision = FileRevision.UNKNOWN,
        val thumbnailPx: Int = 0,
    )

    fun encode(
        sourceId: String,
        path: String,
        zipEntry: String? = null,
        preview: PreviewKind? = null,
        revision: FileRevision = FileRevision.UNKNOWN,
        thumbnailPx: Int = 0,
    ): String {
        val json = JSONObject().put("source", sourceId).put("path", path)
        if (zipEntry != null) json.put("entry", zipEntry)
        if (preview != null) json.put("preview", preview.name)
        if (revision != FileRevision.UNKNOWN) {
            json.put(
                "revision",
                JSONObject()
                    .put("sizeBytes", revision.sizeBytes)
                    .put("modifiedAtEpochMs", revision.modifiedAtEpochMs)
                    .put("entityTag", revision.entityTag),
            )
        }
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
            val preview = json.optString("preview").takeIf(String::isNotBlank)
                ?.let { PreviewKind.valueOf(it) }
            val revision = json.optJSONObject("revision")?.let {
                FileRevision(
                    sizeBytes = it.getLong("sizeBytes"),
                    modifiedAtEpochMs = it.getLong("modifiedAtEpochMs"),
                    entityTag = it.optString("entityTag").ifBlank { null },
                )
            } ?: FileRevision.UNKNOWN
            val thumbnailPx = json.optInt("thumbnailPx")
            if (!source.startsWith("network-") || path.isBlank()) null else {
                Ref(source, path, entry, preview, revision, thumbnailPx)
            }
        } catch (_: Exception) {
            null
        }
    }
}
