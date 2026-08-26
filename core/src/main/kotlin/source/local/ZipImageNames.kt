package com.comics8.core.source.local

object ZipImageNames {
    val IMAGE_EXTS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "avif", "gif")
    val ZIP_EXTS: Set<String> = setOf("zip", "cbz")

    fun isJunkEntry(name: String): Boolean {
        val n = name.replace('\\', '/')
        if (n.startsWith("__MACOSX") || "/__MACOSX/" in "/$n") return true
        val base = n.substringAfterLast('/')
        if (base == ".DS_Store" || base.startsWith(".")) return true
        return false
    }

    fun isImageEntry(name: String): Boolean {
        if (isJunkEntry(name) || name.endsWith("/")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS
    }

    fun isZipName(name: String): Boolean {
        if (isJunkEntry(name) || name.endsWith("/")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in ZIP_EXTS
    }

    fun isJunkName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return true
        if (name == "__MACOSX" || name.startsWith(".")) return true
        return isJunkEntry(name)
    }
}
