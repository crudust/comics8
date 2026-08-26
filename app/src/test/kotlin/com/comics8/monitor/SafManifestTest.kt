package com.comics8.monitor

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class SafManifestTest {
    @Test
    fun androidManifestHasNoBroadStoragePermission() {
        val manifest = workspaceFile("app/src/main/AndroidManifest.xml").readText()
        assertThat(manifest).doesNotContain("READ_EXTERNAL_STORAGE")
        assertThat(manifest).doesNotContain("WRITE_EXTERNAL_STORAGE")
        assertThat(manifest).doesNotContain("MANAGE_EXTERNAL_STORAGE")
    }

    @Test
    fun coreLocalSourcesStayFileBased() {
        val zip = workspaceFile("core/src/main/kotlin/source/local/ZipArchive.kt").readText()
        assertThat(zip).doesNotContain("android.")
        assertThat(zip).doesNotContain("ParcelFileDescriptor")
        assertThat(zip).doesNotContain("DocumentFile")
        val scanner = workspaceFile("core/src/main/kotlin/source/local/LibraryScanner.kt").readText()
        assertThat(scanner).doesNotContain("android.")
    }

    private fun workspaceFile(rel: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val candidates = listOfNotNull(cwd, cwd.parentFile)
        val root = candidates.first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        return File(root, rel)
    }
}
