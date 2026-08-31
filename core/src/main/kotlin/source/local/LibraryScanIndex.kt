package com.comics8.core.source.local

import com.comics8.core.source.FileRevision
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class IndexedLibraryEpisode(
    val path: String,
    val title: String,
    val zip: Boolean,
    val revision: FileRevision,
)

data class IndexedLibraryWork(
    val id: String,
    val title: String,
    val path: String,
    val kind: String,
    val episodes: List<IndexedLibraryEpisode>,
)

class LibraryScanIndex(
    private val file: File,
    private val maxAgeMs: Long = 5L * 60L * 1000L,
    private val maxSizeBytes: Long = 16L * 1024L * 1024L,
) {
    @Synchronized
    fun load(signature: String): List<IndexedLibraryWork>? {
        if (!file.isFile || file.length() <= 0L || file.length() > maxSizeBytes) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optInt("version") != VERSION || json.optString("signature") != signature) return null
            if (System.currentTimeMillis() - json.optLong("savedAt") > maxAgeMs) return null
            val array = json.getJSONArray("works")
            buildList {
                for (i in 0 until array.length()) add(array.getJSONObject(i).toIndexedWork())
            }
        }.getOrNull()
    }

    @Synchronized
    fun save(signature: String, works: List<IndexedLibraryWork>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        works.forEach { array.put(it.toJson()) }
        val json = JSONObject()
            .put("version", VERSION)
            .put("signature", signature)
            .put("savedAt", System.currentTimeMillis())
            .put("works", array)
        val encoded = json.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > maxSizeBytes) {
            clear()
            return
        }
        val part = File(file.parentFile, "${file.name}.part")
        part.writeText(encoded, Charsets.UTF_8)
        if (!part.renameTo(file)) {
            part.copyTo(file, overwrite = true)
            part.delete()
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        private const val VERSION = 2

        fun signature(lines: Iterable<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            lines.sorted().forEach { line ->
                digest.update(line.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

private fun IndexedLibraryWork.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("path", path)
    .put("kind", kind)
    .put("episodes", JSONArray().also { array ->
        episodes.forEach { episode ->
            array.put(
                JSONObject()
                    .put("path", episode.path)
                    .put("title", episode.title)
                    .put("zip", episode.zip)
                    .put(
                        "revision",
                        JSONObject()
                            .put("sizeBytes", episode.revision.sizeBytes)
                            .put("modifiedAtEpochMs", episode.revision.modifiedAtEpochMs)
                            .put("entityTag", episode.revision.entityTag),
                    ),
            )
        }
    })

private fun JSONObject.toIndexedWork(): IndexedLibraryWork {
    val array = getJSONArray("episodes")
    val episodes = buildList {
        for (i in 0 until array.length()) {
            val episode = array.getJSONObject(i)
            add(
                IndexedLibraryEpisode(
                    path = episode.getString("path"),
                    title = episode.getString("title"),
                    zip = episode.getBoolean("zip"),
                    revision = episode.getJSONObject("revision").let { revision ->
                        FileRevision(
                            revision.getLong("sizeBytes"),
                            revision.getLong("modifiedAtEpochMs"),
                            revision.optString("entityTag").ifBlank { null },
                        )
                    },
                ),
            )
        }
    }
    return IndexedLibraryWork(
        id = getString("id"),
        title = getString("title"),
        path = getString("path"),
        kind = getString("kind"),
        episodes = episodes,
    )
}
