package com.comics8.core.source.hitomi

import com.comics8.core.model.ArtistRef
import org.json.JSONArray
import org.json.JSONObject

data class GalleryFile(
    val hash: String,
    val hasWebp: Boolean,
    val hasAvif: Boolean,
)

data class GalleryInfo(
    val id: String,
    val title: String,
    val type: String,
    val dateRaw: String?,
    val updatedAt: String?,
    val artists: List<ArtistRef>,
    val files: List<GalleryFile>,
)

object GalleryInfoParser {
    private val dateRegex = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    fun parse(js: String): GalleryInfo? {
        val jsonText = extractJson(js) ?: return null
        val obj = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return null
        }
        val id = obj.opt("id")?.toString()?.trim().orEmpty()
        if (id.isEmpty() || id == "null") return null
        val title = obj.optString("title").ifBlank { id }
        val type = obj.optString("type").trim()
        val dateRaw = obj.optString("date").takeIf { it.isNotBlank() }
        return GalleryInfo(
            id = id,
            title = title,
            type = type,
            dateRaw = dateRaw,
            updatedAt = formatDate(dateRaw),
            artists = parseArtists(obj.opt("artists")),
            files = parseFiles(obj.optJSONArray("files")),
        )
    }

    fun formatDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = dateRegex.find(raw) ?: return null
        return "${match.groupValues[1].takeLast(2)}.${match.groupValues[2]}.${match.groupValues[3]}"
    }

    private fun extractJson(js: String): String? {
        val trimmed = js.trim()
        if (trimmed.isEmpty()) return null
        val eq = trimmed.indexOf('=')
        val body = if (eq >= 0 && trimmed.regionMatches(0, "var galleryinfo", 0, 15, ignoreCase = true)) {
            trimmed.substring(eq + 1).trim()
        } else {
            trimmed
        }
        return body.trim().trimEnd(';').trim().takeIf { it.startsWith("{") }
    }

    private fun parseArtists(raw: Any?): List<ArtistRef> {
        val array = raw as? JSONArray ?: return emptyList()
        val out = ArrayList<ArtistRef>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.opt(i) ?: continue
            val name: String
            val url: String?
            when (entry) {
                is JSONObject -> {
                    name = entry.optString("artist").ifBlank { entry.optString("name") }
                    url = entry.optString("url").takeIf { it.isNotBlank() }
                }
                else -> {
                    name = entry.toString()
                    url = null
                }
            }
            if (name.isBlank()) continue
            val slug = ArtistSlug.fromArtistUrl(url) ?: ArtistSlug.normalize(name)
            out += ArtistRef(slug = slug, displayName = name)
        }
        return out.distinctBy { it.slug }
    }

    private fun parseFiles(array: JSONArray?): List<GalleryFile> {
        if (array == null) return emptyList()
        val out = ArrayList<GalleryFile>(array.length())
        for (i in 0 until array.length()) {
            val file = array.optJSONObject(i) ?: continue
            val hash = file.optString("hash").trim()
            if (hash.isEmpty()) continue
            out += GalleryFile(
                hash = hash,
                hasWebp = file.optInt("haswebp", 1) != 0,
                hasAvif = file.optInt("hasavif", 0) != 0,
            )
        }
        return out
    }
}
