package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class SourcePackageIsolationTest {
    @Test
    fun sharedMainCodeDoesNotImportSitePackages() {
        val leaks = mutableListOf<String>()
        for (root in mainRoots()) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    for (banned in BANNED_PACKAGES) {
                        if (text.contains(banned)) {
                            leaks += "${file.relativeTo(workspaceRoot())}: $banned"
                        }
                    }
                }
        }
        assertThat(leaks).isEmpty()
    }

    @Test
    fun productionHasNoSiteParserPackages() {
        val sourceDir = File(coreMain(), "source")
        val sharedSourceFiles = sourceDir
            .listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .map { it.name }
        assertThat(sharedSourceFiles).doesNotContain("BuiltInSources.kt")
        assertThat(sharedSourceFiles).doesNotContain("ElevenToonSource.kt")
        assertThat(File(sourceDir, "eleven").exists()).isFalse()
        assertThat(File(sourceDir, "hitomi").exists()).isFalse()
        assertThat(File(coreMain(), "parser").exists()).isFalse()
        assertThat(File(coreMain(), "model/Catalog.kt").exists()).isFalse()
    }

    private fun mainRoots(): List<File> = listOf(
        coreMain(),
        File(workspaceRoot(), "app/src/main"),
        File(workspaceRoot(), "desktop/src/main"),
    ).filter { it.isDirectory }

    private fun coreMain(): File = File(workspaceRoot(), "core/src/main/kotlin/com/comics8/core")

    private fun workspaceRoot(): File {
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        val candidates = listOf(cwd, cwd.parentFile)
        return candidates.first { File(it, "core/src/main/kotlin/com/comics8/core").isDirectory }
    }

    companion object {
        private val BANNED_PACKAGES = listOf(
            "com.comics8.core.source.eleven",
            "com.comics8.core.source.hitomi",
            "com.comics8.core.parser",
            "com.comics8.core.model.Catalog",
        )
    }
}
