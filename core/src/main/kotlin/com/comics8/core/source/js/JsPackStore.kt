package com.comics8.core.source.js

import com.comics8.core.source.HostApi
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-device JS pack files. Desktop: `~/.comics8/sources/<safeId>.js`.
 * Android: `context.filesDir/sources/<safeId>.js`.
 */
class JsPackStore(val directory: File) {
    fun copy(id: String, apiLevel: Int, bytes: ByteArray): File {
        checkSize(bytes.size)
        val safe = validate(id, apiLevel)
        directory.mkdirs()
        val dest = File(directory, "$safe.js")
        val tmp = File(directory, "$safe.js.tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return dest
    }

    fun list(): List<File> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { file ->
            file.isFile && file.extension.equals("js", ignoreCase = true)
        }?.sortedBy { it.name }.orEmpty()
    }

    fun fileFor(id: String): File {
        val safe = validateId(id)
        return File(directory, "$safe.js")
    }

    fun delete(id: String): Boolean {
        val safe = try {
            validateId(id)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val file = File(directory, "$safe.js")
        return !file.exists() || file.delete()
    }

    /** Parse, validate, and copy. Does not mutate the registry. */
    fun ingest(script: String, fileName: String = "source.js"): JsComicSource {
        val bytes = script.toByteArray(StandardCharsets.UTF_8)
        checkSize(bytes.size)
        val source = JsComicSource.fromScript(script, fileName)
        copy(source.id, source.hostApiLevel, bytes)
        return source
    }

    fun loadAll(): List<JsComicSource> {
        return list().mapNotNull { file ->
            if (file.length() > MAX_SCRIPT_BYTES) return@mapNotNull null
            try {
                val source = JsComicSource.fromScript(file.readText(StandardCharsets.UTF_8), file.name)
                validate(source.id, source.hostApiLevel)
                source
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Register already-parsed packs. Caller must be on the UI thread. */
    fun registerAll(registry: SourceRegistry, sources: List<JsComicSource>): List<JsComicSource> {
        for (source in sources) {
            registry.remove(source.id)
            registry.add(source)
        }
        return sources
    }

    /** Parse then register. Tests; production parses off the UI thread then [registerAll]. */
    fun loadInto(registry: SourceRegistry): List<JsComicSource> = registerAll(registry, loadAll())

    companion object {
        const val MAX_SCRIPT_BYTES: Int = 2 * 1024 * 1024
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        fun safeId(id: String): String = id.replace(UNSAFE, "")

        fun desktopDefault(home: File = File(System.getProperty("user.home"))): JsPackStore =
            JsPackStore(File(home, ".comics8/sources"))

        fun androidFiles(filesDir: File): JsPackStore = JsPackStore(File(filesDir, "sources"))

        fun checkSize(byteCount: Int) {
            if (byteCount > MAX_SCRIPT_BYTES) {
                throw IllegalArgumentException("파일이 너무 큽니다")
            }
        }

        fun readCapped(input: InputStream): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                checkSize(total)
                out.write(buf, 0, n)
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }

        fun validate(id: String, apiLevel: Int): String {
            if (apiLevel > HostApi.LEVEL) {
                throw IllegalArgumentException("앱 업데이트가 필요합니다")
            }
            return validateId(id)
        }

        fun validateId(id: String): String {
            val trimmed = id.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("id 없음")
            val safe = safeId(trimmed)
            if (safe.isEmpty() || safe == "." || safe == "..") {
                throw IllegalArgumentException("id 없음")
            }
            if (safe == WorkId.LOCAL_SOURCE) throw IllegalArgumentException("id가 예약됨")
            return safe
        }
    }
}
