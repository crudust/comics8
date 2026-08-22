package com.comics8.core.source.local

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ZipImageUri {
    const val SCHEME = "comics8-zip"

    data class Ref(val zip: File, val entry: String)

    /**
     * `comics8-zip:///absolute/path/to.cbz!/nested/001.jpg`
     * Zip path and entry are UTF-8 percent-encoded. Separator `!/` matches Java `jar:` URLs.
     */
    fun encode(zip: File, entry: String): String {
        val path = canonicalPath(zip).replace('\\', '/')
        val safeEntry = ZipArchive.normalizeZipEntry(entry)
        return "$SCHEME://${encodePath(path)}!/${encodePath(safeEntry)}"
    }

    fun parse(url: String): Ref? {
        if (!url.startsWith("$SCHEME:", ignoreCase = true)) return null
        val body = url.substring(SCHEME.length)
        if (!body.startsWith(":")) return null
        val rest = body.substring(1)
        val sep = rest.indexOf("!/")
        if (sep < 0) return null
        val pathPart = stripHierarchicalPrefix(rest.substring(0, sep))
        val entryPart = rest.substring(sep + 2)
        if (pathPart.isEmpty() || entryPart.isEmpty()) return null
        val path = decodePath(pathPart) ?: return null
        val entry = decodePath(entryPart) ?: return null
        val safeEntry = ZipArchive.tryNormalizeZipEntry(entry) ?: return null
        if (path.isEmpty() || safeEntry.isEmpty()) return null
        return Ref(File(path), safeEntry)
    }

    private fun stripHierarchicalPrefix(pathPart: String): String =
        if (pathPart.startsWith("//")) pathPart.substring(2) else pathPart

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(segment: String): String {
        val bytes = segment.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (isUnreserved(c)) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(HEX[c shr 4])
                out.append(HEX[c and 0xF])
            }
        }
        return out.toString()
    }

    private fun decodePath(path: String): String? =
        try {
            URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }

    private fun canonicalPath(file: File): String =
        try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }

    private fun isUnreserved(c: Int): Boolean =
        c in 'A'.code..'Z'.code ||
            c in 'a'.code..'z'.code ||
            c in '0'.code..'9'.code ||
            c == '-'.code ||
            c == '.'.code ||
            c == '_'.code ||
            c == '~'.code

    private const val HEX = "0123456789ABCDEF"
}
