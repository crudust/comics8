package com.comics8.desktop

import com.comics8.core.source.local.ZipImageNames
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.Normalizer

internal object ExternalTargetResolver {
    sealed interface Target {
        data class Zip(val file: File) : Target
        data class Folder(val dir: File, val initialFile: File? = null) : Target
    }

    private fun File.isAnyRegularFile(): Boolean {
        if (isFile) return true
        return try {
            Files.isRegularFile(toPath())
        } catch (_: Exception) {
            false
        }
    }

    private fun File.isAnyDirectory(): Boolean {
        if (isDirectory) return true
        return try {
            Files.isDirectory(toPath())
        } catch (_: Exception) {
            false
        }
    }

    private fun hasDirectImages(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        return files.any { it.isAnyRegularFile() && ZipImageNames.isImageEntry(it.name) }
    }

    fun resolve(target: File): Target? {
        val normalized = try {
            target.toPath().toAbsolutePath().normalize().toFile()
        } catch (_: Exception) {
            target.absoluteFile
        }
        val exists = normalized.exists() || try {
            Files.exists(normalized.toPath())
        } catch (_: Exception) {
            false
        }

        if (!exists) {
            if (normalized.path.contains("%")) {
                val decoded = try {
                    File(URLDecoder.decode(normalized.path, StandardCharsets.UTF_8.name()))
                } catch (_: Exception) { null }
                if (decoded != null && (decoded.exists() || Files.exists(decoded.toPath()))) {
                    return resolve(decoded)
                }
            }
            return null
        }

        if (normalized.isAnyRegularFile()) {
            if (ZipImageNames.isZipName(normalized.name)) {
                return Target.Zip(normalized)
            }
            if (ZipImageNames.isImageEntry(normalized.name)) {
                val parent = normalized.parentFile
                if (parent != null && parent.isAnyDirectory()) {
                    return Target.Folder(parent, initialFile = normalized)
                }
            }
            return null
        }

        if (normalized.isAnyDirectory()) {
            // 1. Direct inner file with the same name (macOS SMB mount: /Volumes/foo.zip/foo.zip)
            val directInner = File(normalized, normalized.name)
            if (directInner.isAnyRegularFile() && ZipImageNames.isZipName(directInner.name)) {
                return Target.Zip(directInner)
            }

            val nfcTargetName = Normalizer.normalize(normalized.name, Normalizer.Form.NFC)
            val children = normalized.listFiles() ?: emptyArray()

            // 2. Matching child archive by normalized name
            val innerSameName = children.firstOrNull { child ->
                child.isAnyRegularFile() && ZipImageNames.isZipName(child.name) &&
                    Normalizer.normalize(child.name, Normalizer.Form.NFC).equals(nfcTargetName, ignoreCase = true)
            }
            if (innerSameName != null) {
                return Target.Zip(innerSameName)
            }

            // 3. Any child archives in folder
            val zips = children.filter { it.isAnyRegularFile() && ZipImageNames.isZipName(it.name) }
            if (zips.size == 1) {
                return Target.Zip(zips.first())
            } else if (zips.isNotEmpty()) {
                val matching = zips.firstOrNull {
                    Normalizer.normalize(it.name, Normalizer.Form.NFC).contains(nfcTargetName, ignoreCase = true)
                } ?: zips.first()
                return Target.Zip(matching)
            }

            // 4. Direct images inside directory
            if (hasDirectImages(normalized)) {
                return Target.Folder(normalized)
            }

            // 5. Subfolder containing images
            val subdirs = children.filter { it.isAnyDirectory() && !ZipImageNames.isJunkName(it.name) }
            val subWithImages = subdirs.firstOrNull { hasDirectImages(it) }
            if (subWithImages != null) {
                return Target.Folder(subWithImages)
            }
        }

        return null
    }
}
