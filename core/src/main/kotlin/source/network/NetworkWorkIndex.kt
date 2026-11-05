package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import com.comics8.core.source.local.LibraryScanIndex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 루트 한 단계 아래 항목이 어떤 종류의 작품인지. [SKIP]은 만화가 아닌 항목이다. */
enum class NetworkWorkKind(val prefix: String) {
    SERIES("series"),
    DIR("dir"),
    ZIP("zip"),
    SKIP(""),
}

/** 네트워크 저장소의 회차 하나. ZIP 회차면 [zip]이 true다. */
data class NetworkEpisode(
    val path: String,
    val title: String,
    val zip: Boolean,
    val revision: FileRevision,
)

/**
 * 그리드 한 칸을 그리는 데 필요한 최소 정보.
 *
 * [episode]는 썸네일을 만들 첫 회차이며, [revision]은 정렬이 아니라 썸네일 캐시 키(ZIP 세션 힌트 +
 * 썸네일 무효화)로만 쓰인다. 작품 수정일처럼 순서로 보이는 값은 캐시에 저장하지 않는다.
 */
data class NetworkWorkShape(
    val kind: NetworkWorkKind,
    val episode: NetworkEpisode? = null,
)

/**
 * 루트 목록 시그니처에 묶인 네트워크 작품 분류 캐시.
 *
 * 캐시가 아끼는 비용은 "루트 아래 폴더를 하나씩 열어 작품인지 판별하고 썸네일용 첫 회차를 찾는 일"뿐이다.
 * 시그니처는 루트 목록 전체(이름·종류·수정일)에서 만들기 때문에, 작품 폴더에 회차가 추가되면 작품 폴더의
 * 수정일이 바뀌면서 자동으로 무효화된다. [maxAgeMs]는 시그니처가 감지하지 못하는 변화에 대한 안전망이다.
 */
class NetworkWorkIndex(
    private val file: File,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    @Synchronized
    fun load(signature: String): Map<String, NetworkWorkShape>? {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_SIZE_BYTES) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optInt("version") != VERSION || json.optString("signature") != signature) return null
            if (System.currentTimeMillis() - json.optLong("savedAt") > maxAgeMs) return null
            val entries = json.getJSONArray("works")
            buildMap {
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    put(
                        entry.getString("path"),
                        NetworkWorkShape(
                            kind = NetworkWorkKind.valueOf(entry.getString("kind")),
                            episode = entry.optJSONObject("episode")?.toEpisode(),
                        ),
                    )
                }
            }
        }.getOrNull()
    }

    @Synchronized
    fun save(signature: String, shapes: Map<String, NetworkWorkShape>) {
        val entries = JSONArray()
        shapes.forEach { (path, shape) ->
            entries.put(
                JSONObject()
                    .put("path", path)
                    .put("kind", shape.kind.name)
                    .put("episode", shape.episode?.toJson()),
            )
        }
        val json = JSONObject()
            .put("version", VERSION)
            .put("signature", signature)
            .put("savedAt", System.currentTimeMillis())
            .put("works", entries)
        val encoded = json.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_SIZE_BYTES) {
            clear()
            return
        }
        file.parentFile?.mkdirs()
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
        const val DEFAULT_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L
        private const val VERSION = 1
        private const val MAX_SIZE_BYTES = 4L * 1024L * 1024L

        /** 루트 목록이 그대로면 작품 구성도 그대로다. 목록의 수정일까지 포함해 회차 추가/삭제를 감지한다. */
        fun signature(root: List<NetworkNode>): String = LibraryScanIndex.signature(
            root.map { "${it.path}|${it.name}|${it.directory}|${it.revision}" },
        )
    }
}

private fun NetworkEpisode.toJson(): JSONObject = JSONObject()
    .put("path", path)
    .put("title", title)
    .put("zip", zip)
    .put(
        "revision",
        JSONObject()
            .put("sizeBytes", revision.sizeBytes)
            .put("modifiedAtEpochMs", revision.modifiedAtEpochMs)
            .put("entityTag", revision.entityTag),
    )

private fun JSONObject.toEpisode(): NetworkEpisode = NetworkEpisode(
    path = getString("path"),
    title = getString("title"),
    zip = getBoolean("zip"),
    revision = getJSONObject("revision").let { revision ->
        FileRevision(
            sizeBytes = revision.getLong("sizeBytes"),
            modifiedAtEpochMs = revision.getLong("modifiedAtEpochMs"),
            entityTag = revision.optString("entityTag").ifBlank { null },
        )
    },
)
