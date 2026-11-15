package com.comics8.core.source.local

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class RarArchive(
    private val file: File,
    private val bsdtarBin: String = BSDTAR_PATH,
) : ComicArchive {
    private val rawByNormalized = LinkedHashMap<String, String>()
    private val images: List<String> by lazy { loadImageEntries() }

    init {
        if (!file.isFile) {
            throw IOException("RAR file not found: ${file.path}")
        }
        if (!File(bsdtarBin).canExecute()) {
            throw IOException("bsdtar is not available at $bsdtarBin")
        }
    }

    override fun imageEntries(): List<String> = images

    override fun firstImageEntry(): String? = images.firstOrNull()

    override fun open(entryName: String): InputStream {
        images // ensure rawByNormalized is populated
        val normalized = ZipArchive.normalizeZipEntry(entryName)
        val raw = rawByNormalized[normalized]
            ?: throw IllegalArgumentException("missing rar entry: $entryName")
        val process = startBsdtar("-xOf", file.path, escapeBsdtarPattern(raw))
        return ZipArchive.CappedInputStream(
            BsdtarEntryInputStream(process, entryName),
            ZipArchive.MAX_ENTRY_BYTES,
        )
    }

    override fun close() {
        // Each bsdtar invocation is scoped to the call/stream.
    }

    private fun loadImageEntries(): List<String> {
        val process = startBsdtar("-tf", file.path)
        val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Failed to list RAR archive (${file.name}, exit=$exitCode): ${stderr.trim()}")
        }
        val names = ArrayList<String>()
        for (line in stdout.lineSequence()) {
            if (line.isBlank() || line.endsWith("/")) continue
            val normalized = ZipArchive.tryNormalizeZipEntry(line) ?: continue
            if (!ZipImageNames.isImageEntry(normalized)) continue
            if (rawByNormalized.putIfAbsent(normalized, line) == null) {
                names += normalized
            }
        }
        names.sortWith(NaturalSort)
        return names
    }

    private fun startBsdtar(vararg args: String): Process {
        val pb = ProcessBuilder(listOf(bsdtarBin) + args)
        pb.environment()["LC_ALL"] = "en_US.UTF-8"
        return pb.start()
    }

    private class BsdtarEntryInputStream(
        private val process: Process,
        private val entryName: String,
    ) : FilterInputStream(process.inputStream) {
        private var checkedExit = false

        override fun read(): Int {
            val b = super.read()
            if (b < 0) verifyExit()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n < 0) verifyExit()
            return n
        }

        override fun close() {
            try {
                super.close()
            } finally {
                runCatching { process.errorStream.close() }
                if (process.isAlive) {
                    process.destroy()
                }
            }
        }

        private fun verifyExit() {
            if (checkedExit) return
            checkedExit = true
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val err = runCatching {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText().trim() }
                }.getOrDefault("")
                throw IOException("Failed to read RAR entry '$entryName' (exit=$exitCode): $err")
            }
        }
    }

    companion object {
        const val BSDTAR_PATH = "/usr/bin/bsdtar"

        val isSupported: Boolean by lazy {
            isMacOs() && File(BSDTAR_PATH).canExecute()
        }

        private fun isMacOs(): Boolean =
            System.getProperty("os.name", "").lowercase().contains("mac")

        internal fun escapeBsdtarPattern(name: String): String {
            val sb = StringBuilder(name.length)
            for (ch in name) {
                if (ch == '\\' || ch == '*' || ch == '?' || ch == '[' || ch == ']') {
                    sb.append('\\')
                }
                sb.append(ch)
            }
            return sb.toString()
        }
    }
}
