package com.comics8.monitor.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.comics8.core.source.local.ZipImageNames
import java.io.File
import java.security.MessageDigest

/**
 * Maps persistable SAF tree URIs onto app-private [File] trees so [com.comics8.core.source.local.LocalSource]
 * can keep using File-based ZIP/scan APIs. Copies via PFD; never puts Android types in :core.
 */
class SafLibraryMirror(
    private val context: Context,
    private val destRoot: File = File(context.filesDir, "local-roots"),
) {
    private val lock = Any()

    fun takePersistable(uri: Uri): Boolean =
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (_: SecurityException) {
            false
        }

    fun releasePersistable(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        destFor(uri.toString()).deleteRecursively()
    }

    fun hasPersistable(uri: Uri): Boolean {
        val target = uri.toString()
        return context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == target && it.isReadPermission
        }
    }

    fun displayName(uriString: String): String {
        val uri = Uri.parse(uriString)
        treeDocument(uri)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        val last = uri.lastPathSegment.orEmpty()
        return last.substringAfterLast(':').substringAfterLast('/').ifBlank { uriString }
    }

    fun destFor(uriString: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uriString.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        return File(destRoot, hex)
    }

    fun materialize(rootUris: List<String>): List<File> = synchronized(lock) {
        destRoot.mkdirs()
        val expected = linkedSetOf<String>()
        val out = ArrayList<File>()
        for (raw in rootUris) {
            val key = raw.trim()
            if (key.isEmpty()) continue
            val uri = Uri.parse(key)
            val tree = treeDocument(uri) ?: continue
            val dest = destFor(key)
            materializeDocument(tree, dest)
            expected += dest.name
            if (dest.isDirectory) out += dest
        }
        destRoot.listFiles()?.forEach { child ->
            if (child.name !in expected) child.deleteRecursively()
        }
        out
    }

    fun materializeDocument(tree: DocumentFile, dest: File): File {
        dest.mkdirs()
        val kept = linkedSetOf<String>()
        val children = tree.listFiles()
            .mapNotNull { child -> child.name?.let { name -> name to child } }
            .sortedBy { it.first }
        for ((name, child) in children) {
            if (ZipImageNames.isJunkName(name)) continue
            if (child.isFile && ZipImageNames.isZipName(name)) {
                if (copyDocument(child, File(dest, name))) kept += name
                continue
            }
            if (child.isDirectory) {
                val sub = File(dest, name)
                if (materializeFolder(child, sub)) kept += name
            }
        }
        pruneUnexpected(dest, kept)
        return dest
    }

    private fun materializeFolder(dir: DocumentFile, dest: File): Boolean {
        val children = dir.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            if (ZipImageNames.isJunkName(name)) null else name to child
        }
        val zips = children.filter { (name, child) -> child.isFile && ZipImageNames.isZipName(name) }
        val imageFolders = children.filter { (_, child) -> child.isDirectory && hasDirectImages(child) }
        if (zips.isNotEmpty() || imageFolders.isNotEmpty()) {
            dest.mkdirs()
            val kept = linkedSetOf<String>()
            for ((name, zip) in zips) {
                if (copyDocument(zip, File(dest, name))) kept += name
            }
            for ((name, folder) in imageFolders) {
                val sub = File(dest, name)
                copyImages(folder, sub)
                kept += name
            }
            pruneUnexpected(dest, kept)
            return dest.isDirectory
        }
        if (hasDirectImages(dir)) {
            copyImages(dir, dest)
            return dest.isDirectory
        }
        return false
    }

    private fun copyImages(dir: DocumentFile, dest: File) {
        dest.mkdirs()
        val kept = linkedSetOf<String>()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (!child.isFile || !ZipImageNames.isImageEntry(name)) continue
            if (copyDocument(child, File(dest, name))) kept += name
        }
        pruneUnexpected(dest, kept)
    }

    private fun hasDirectImages(dir: DocumentFile): Boolean =
        dir.listFiles().any { child ->
            val name = child.name ?: return@any false
            child.isFile && ZipImageNames.isImageEntry(name)
        }

    private fun pruneUnexpected(dir: File, kept: Set<String>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.name.endsWith(".part")) {
                file.delete()
                continue
            }
            if (file.name !in kept) file.deleteRecursively()
        }
    }

    private fun treeDocument(uri: Uri): DocumentFile? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path ?: return null
            return DocumentFile.fromFile(File(path)).takeIf { it.isDirectory }
        }
        if (!hasPersistable(uri)) return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    internal fun copyDocument(doc: DocumentFile, dest: File): Boolean {
        val length = doc.length()
        val mtime = doc.lastModified()
        if (
            dest.isFile &&
            dest.length() > 0L &&
            (length <= 0L || dest.length() == length) &&
            (mtime <= 0L || dest.lastModified() >= mtime)
        ) {
            return true
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.part")
        tmp.delete()
        return try {
            if (!copyUriToFile(doc.uri, tmp)) return false
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (mtime > 0L) dest.setLastModified(mtime)
            dest.isFile && dest.length() > 0L
        } catch (_: Exception) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun copyUriToFile(uri: Uri, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        }
        if (pfd != null) {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            return dest.isFile
        }
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
        if (stream != null) {
            stream.use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            return dest.isFile
        }
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val src = uri.path?.let(::File) ?: return false
            if (!src.isFile) return false
            src.copyTo(dest, overwrite = true)
            return dest.isFile
        }
        return false
    }
}
