package com.comics8.core.source.local

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ZipArchive(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val images: List<String> by lazy { listImageEntries() }

    fun imageEntries(): List<String> = images

    fun firstImageEntry(): String? = images.firstOrNull()

    fun open(entryName: String): InputStream {
        val normalized = normalizeZipEntry(entryName)
        val entry = findEntry(normalized, entryName)
            ?: throw IllegalArgumentException("missing zip entry: $entryName")
        val size = entry.size
        if (size > MAX_ENTRY_BYTES) {
            throw IllegalArgumentException("zip entry too large: ${entry.name} ($size bytes)")
        }
        // Always cap actual bytes: DEFLATED entries can inflate past a lying CEN size.
        return CappedInputStream(zip.getInputStream(entry), MAX_ENTRY_BYTES)
    }

    override fun close() {
        zip.close()
    }

    private fun listImageEntries(): List<String> {
        val names = ArrayList<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val normalized = tryNormalizeZipEntry(entry.name) ?: continue
            if (!ZipImageNames.isImageEntry(normalized)) continue
            names += normalized
        }
        names.sortWith(NaturalSort)
        return names
    }

    private fun findEntry(normalized: String, requested: String): ZipEntry? {
        zip.getEntry(requested)?.let { return it }
        zip.getEntry(normalized)?.let { return it }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = tryNormalizeZipEntry(entry.name) ?: continue
            if (name == normalized) return entry
        }
        return null
    }

    internal class CappedInputStream(
        inner: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(inner) {
        private var seen = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) account(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) account(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            if (n <= 0L) return 0L
            val buf = ByteArray(minOf(n, 8192L).toInt())
            var skipped = 0L
            while (skipped < n) {
                val r = read(buf, 0, minOf(buf.size.toLong(), n - skipped).toInt())
                if (r < 0) break
                skipped += r
            }
            return skipped
        }

        private fun account(n: Long) {
            seen += n
            if (seen > maxBytes) {
                throw IllegalArgumentException("zip entry exceeds $maxBytes bytes")
            }
        }
    }

    companion object {
        const val MAX_ENTRY_BYTES: Long = 75L * 1024 * 1024

        fun normalizeZipEntry(name: String): String {
            val n = name.replace('\\', '/').trimStart('/')
            require(n.isNotEmpty() && n.split('/').none { it == ".." }) { "illegal zip entry: $name" }
            return n
        }

        internal fun tryNormalizeZipEntry(name: String): String? =
            try {
                normalizeZipEntry(name)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
