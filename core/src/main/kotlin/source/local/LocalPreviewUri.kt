package com.comics8.core.source.local

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

object LocalPreviewUri {
    const val SCHEME = "comics8-local-preview"

    enum class Kind { ZIP, FOLDER }

    data class Ref(
        val path: String,
        val kind: Kind,
        val modifiedAt: Long,
        val size: Long,
        val thumbnailPx: Int,
    )

    fun encode(file: File, kind: Kind, thumbnailPx: Int): String {
        val json = JSONObject()
            .put("path", file.absolutePath)
            .put("kind", kind.name)
            .put("modifiedAt", file.lastModified())
            .put("size", file.length())
            .put("thumbnailPx", thumbnailPx)
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
        return "$SCHEME:$payload"
    }

    fun parse(url: String): Ref? {
        if (!url.startsWith("$SCHEME:", ignoreCase = true)) return null
        return runCatching {
            val json = JSONObject(
                String(Base64.getUrlDecoder().decode(url.substringAfter(':')), StandardCharsets.UTF_8),
            )
            Ref(
                path = json.getString("path"),
                kind = Kind.valueOf(json.getString("kind")),
                modifiedAt = json.optLong("modifiedAt"),
                size = json.optLong("size"),
                thumbnailPx = json.optInt("thumbnailPx").coerceAtLeast(64),
            ).takeIf { it.path.isNotBlank() }
        }.getOrNull()
    }

    fun open(ref: Ref): InputStream = when (ref.kind) {
        Kind.ZIP -> {
            val archive = ZipArchive(File(ref.path))
            val entry = archive.firstImageEntry() ?: run {
                archive.close()
                error("ZIP에 이미지가 없습니다")
            }
            val input = archive.open(entry)
            object : java.io.FilterInputStream(input) {
                override fun close() {
                    runCatching { super.close() }
                    archive.close()
                }
            }
        }
        Kind.FOLDER -> {
            val image = LibraryScanner().listFolderImages(File(ref.path)).firstOrNull()
                ?: error("폴더에 이미지가 없습니다")
            image.inputStream()
        }
    }
}
