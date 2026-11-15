package com.comics8.core.source.local

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RarArchiveTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun escapesBsdtarGlobMetacharacters() {
        val raw = "folder/[Author] Title (vol.1)?*\\001.jpg"
        assertThat(RarArchive.escapeBsdtarPattern(raw))
            .isEqualTo("folder/\\[Author\\] Title (vol.1)\\?\\*\\\\001.jpg")
    }

    @Test
    fun listsNaturalSortedImagesAndExtractsViaBsdtar() {
        val fakeBsdtar = createFakeBsdtar(
            """
            #!/bin/sh
            if [ "$1" = "-tf" ]; then
              printf '%s\n' "book/info.txt" "book/10.jpg" "book/[Vol] 2.jpg" "book/1.png" "__MACOSX/._1.png"
              exit 0
            fi
            if [ "$1" = "-xOf" ]; then
              printf '%s' "$3"
              exit 0
            fi
            exit 1
            """.trimIndent(),
        )
        val rar = tempFolder.newFile("sample.rar")

        RarArchive(rar, bsdtarBin = fakeBsdtar.path).use { archive ->
            assertThat(archive.imageEntries()).containsExactly(
                "book/1.png",
                "book/10.jpg",
                "book/[Vol] 2.jpg",
            ).inOrder()
            assertThat(archive.firstImageEntry()).isEqualTo("book/1.png")

            val extracted = archive.open("book/[Vol] 2.jpg").use { it.readBytes().decodeToString() }
            assertThat(extracted).isEqualTo("book/\\[Vol\\] 2.jpg")

            assertThrows(IllegalArgumentException::class.java) {
                archive.open("../evil.jpg")
            }
        }
    }

    @Test
    fun throwsOnNonZeroBsdtarExit() {
        val failingBsdtar = createFakeBsdtar(
            """
            #!/bin/sh
            echo "corrupt archive" >&2
            exit 2
            """.trimIndent(),
        )
        val rar = tempFolder.newFile("broken.rar")

        RarArchive(rar, bsdtarBin = failingBsdtar.path).use { archive ->
            val err = assertThrows(IOException::class.java) {
                archive.imageEntries()
            }
            assertThat(err.message).contains("corrupt archive")
        }
    }

    private fun createFakeBsdtar(script: String): File {
        val file = tempFolder.newFile("fake-bsdtar.sh")
        file.writeText(script)
        file.setExecutable(true)
        return file
    }
}
