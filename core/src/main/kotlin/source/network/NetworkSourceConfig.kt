package com.comics8.core.source.network

import org.json.JSONArray
import org.json.JSONObject
import com.comics8.core.source.local.LibraryScanIndex
import java.io.File
import java.util.UUID

enum class NetworkProtocol { SMB, WEBDAV }

data class NetworkSourceConfig(
    val id: String = "network-${UUID.randomUUID()}",
    val protocol: NetworkProtocol,
    val name: String,
    val host: String = "",
    val port: Int = if (protocol == NetworkProtocol.SMB) 445 else 0,
    val share: String = "",
    val path: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    override fun toString(): String =
        "NetworkSourceConfig(id=$id, protocol=$protocol, name=$name, host=$host, port=$port, " +
            "share=$share, path=$path, url=$url, username=$username, password=<redacted>, domain=$domain)"

    fun validated(): NetworkSourceConfig {
        val validId = normalizeId(id)
        require(validId.startsWith("network-") && validId.length <= 128) { "잘못된 연결 ID입니다" }
        require(name.trim().isNotEmpty()) { "이름을 입력하세요" }
        require(name.length <= 80) { "이름이 너무 깁니다" }
        return when (protocol) {
            NetworkProtocol.SMB -> {
                require(host.trim().isNotEmpty()) { "서버 주소를 입력하세요" }
                require(port in 1..65535) { "포트가 올바르지 않습니다" }
                require(share.trim().trim('/', '\\').isNotEmpty()) { "공유 이름을 입력하세요" }
                copy(
                    id = validId,
                    name = name.trim(),
                    host = host.trim().removePrefix("smb://").trimEnd('/'),
                    share = share.trim().trim('/', '\\'),
                    path = normalizePath(path),
                    username = username.trim(),
                    domain = domain.trim(),
                    url = "",
                )
            }
            NetworkProtocol.WEBDAV -> {
                val normalizedUrl = url.trim().let { if (it.endsWith('/')) it else "$it/" }
                require(normalizedUrl.startsWith("https://") || normalizedUrl.startsWith("http://")) {
                    "WebDAV URL은 http:// 또는 https://로 시작해야 합니다"
                }
                copy(
                    id = validId,
                    name = name.trim(),
                    url = normalizedUrl,
                    username = username.trim(),
                    host = "",
                    share = "",
                    path = "",
                    domain = "",
                )
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("protocol", protocol.name)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("share", share)
        .put("path", path)
        .put("url", url)
        .put("username", username)
        .put("password", password)
        .put("domain", domain)

    companion object {
        fun fromJson(json: JSONObject): NetworkSourceConfig = NetworkSourceConfig(
            id = json.getString("id"),
            protocol = NetworkProtocol.valueOf(json.getString("protocol")),
            name = json.getString("name"),
            host = json.optString("host"),
            port = json.optInt("port", 445),
            share = json.optString("share"),
            path = json.optString("path"),
            url = json.optString("url"),
            username = json.optString("username"),
            password = json.optString("password"),
            domain = json.optString("domain"),
        ).validated()

        internal fun normalizePath(value: String): String = value
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .also { require(".." !in it) { "상위 경로(..)는 사용할 수 없습니다" } }
            .joinToString("/")

        fun normalizeId(value: String): String = value.trim().let { id ->
            if (id.startsWith("network:")) "network-${id.removePrefix("network:")}" else id
        }
    }
}

class NetworkSourceStore(
    private val file: File,
    private val indexDir: File = File(file.parentFile, "network-index"),
) {
    @Synchronized
    fun all(): List<NetworkSourceConfig> {
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until array.length()) {
                    runCatching { NetworkSourceConfig.fromJson(array.getJSONObject(i)) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(config: NetworkSourceConfig) {
        val valid = config.validated()
        val next = all().filterNot { it.id == valid.id } + valid
        write(next)
    }

    @Synchronized
    fun delete(id: String) {
        val next = all().filterNot { it.id == id }
        write(next)
        NetworkSourceRuntime.remove(id)
        indexFor(id).clear()
    }

    fun createSource(config: NetworkSourceConfig): NetworkLibrarySource =
        NetworkLibrarySource(config.validated(), index = indexFor(config.id))

    private fun indexFor(id: String): LibraryScanIndex {
        val name = LibraryScanIndex.signature(listOf(id))
        return LibraryScanIndex(File(indexDir, "$name.json"), maxAgeMs = 24L * 60L * 60L * 1000L)
    }

    private fun write(configs: List<NetworkSourceConfig>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        configs.forEach { array.put(it.toJson()) }
        val tmp = File(file.parentFile, "${file.name}.part")
        tmp.writeText(array.toString(), Charsets.UTF_8)
        if (file.exists() && !file.delete()) error("연결 설정을 갱신할 수 없습니다")
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
