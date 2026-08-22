package com.comics8.core.source.local

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

class ZipArchiveTest {
    @Test
    fun listsImagesWithNaturalSortAndSkipsJunk() {
        val zip = tempZip(
            "10.jpg" to "ten",
            "2.jpg" to "two",
            "1.jpg" to "one",
            "01.jpg" to "zero-one",
            ".DS_Store" to "store",
            "__MACOSX/._1.jpg" to "appledouble",
            "notes.txt" to "nope",
            "nested/001.png" to "nested",
        )
        ZipArchive(zip).use { archive ->
            assertThat(archive.imageEntries()).containsExactly(
                "1.jpg",
                "01.jpg",
                "2.jpg",
                "10.jpg",
                "nested/001.png",
            ).inOrder()
            assertThat(archive.firstImageEntry()).isEqualTo("1.jpg")
            assertThat(archive.open("10.jpg").readBytes().toString(Charsets.UTF_8)).isEqualTo("ten")
        }
    }

    @Test
    fun skipsMacosxFixtureAndKeepsPages() {
        val zip = fixture("local/macosx.zip")
        ZipArchive(zip).use { archive ->
            assertThat(archive.imageEntries()).containsExactly("pages/001.jpg").inOrder()
            assertThat(archive.open("pages/001.jpg").readBytes().toString(Charsets.UTF_8)).isEqualTo("page")
        }
        ZipFile(zip).use { file ->
            val names = file.entries().asIterator().asSequence().map { it.name }.toList()
            assertThat(names).contains("__MACOSX/._001.jpg")
            assertThat(names).contains(".DS_Store")
        }
    }

    @Test
    fun numericFixtureSortsByValueThenShorterDigits() {
        val zip = fixture("local/numeric.zip")
        ZipArchive(zip).use { archive ->
            assertThat(archive.imageEntries()).containsExactly(
                "1.jpg",
                "01.jpg",
                "001.jpg",
                "2.jpg",
                "10.jpg",
            ).inOrder()
        }
    }

    @Test
    fun rejectsZipSlipEntries() {
        assertThrows(IllegalArgumentException::class.java) {
            ZipArchive.normalizeZipEntry("../evil.jpg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZipArchive.normalizeZipEntry("nested/../../outside.jpg")
        }
        assertThat(ZipArchive.normalizeZipEntry("folder\\001.jpg")).isEqualTo("folder/001.jpg")
        assertThat(ZipArchive.normalizeZipEntry("/abs/001.jpg")).isEqualTo("abs/001.jpg")

        val zip = fixture("local/zipslip.zip")
        ZipArchive(zip).use { archive ->
            assertThat(archive.imageEntries()).containsExactly("safe/001.jpg")
            assertThrows(IllegalArgumentException::class.java) {
                archive.open("../evil.jpg")
            }
            assertThrows(IllegalArgumentException::class.java) {
                archive.open("nested/../../outside.jpg")
            }
            assertThat(archive.open("safe/001.jpg").readBytes().toString(Charsets.UTF_8)).isEqualTo("ok")
        }
    }

    @Test
    fun opensBackslashEntriesAfterNormalize() {
        val zip = tempZip("folder\\002.jpg" to "two", "folder\\001.jpg" to "one")
        ZipArchive(zip).use { archive ->
            assertThat(archive.imageEntries()).containsExactly("folder/001.jpg", "folder/002.jpg").inOrder()
            assertThat(archive.open("folder/002.jpg").readBytes().toString(Charsets.UTF_8)).isEqualTo("two")
        }
    }

    @Test
    fun rejectsEntriesOver75Mb() {
        assertThat(ZipArchive.exceedsEntryCap(ZipArchive.MAX_ENTRY_BYTES)).isFalse()
        assertThat(ZipArchive.exceedsEntryCap(ZipArchive.MAX_ENTRY_BYTES + 1)).isTrue()
        val zip = tempZip("tiny.jpg" to "x")
        ZipArchive(zip).use { archive ->
            assertThat(archive.open("tiny.jpg").readBytes()).hasLength(1)
        }
    }

    @Test
    fun usesZipFileNotZipInputStream() {
        val zip = tempZip("a.jpg" to "A", "b.jpg" to "B")
        ZipArchive(zip).use { archive ->
            // Random access: later entry without draining the first.
            assertThat(archive.open("b.jpg").readBytes().toString(Charsets.UTF_8)).isEqualTo("B")
            assertThat(archive.open("a.jpg").readBytes().toString(Charsets.UTF_8)).isEqualTo("A")
        }
        val text = LocalTestZips.sourceFile("ZipArchive.kt").readText()
        assertThat(text).contains("ZipFile")
        assertThat(text).doesNotContain("ZipInputStream")
    }

    private fun tempZip(vararg entries: Pair<String, String>): File {
        val dir = createTempDirectory("zip-archive").toFile()
        dir.deleteOnExit()
        val zip = File(dir, "test.zip")
        LocalTestZips.write(zip, entries.toList())
        return zip
    }

    private fun fixture(path: String): File {
        val url = checkNotNull(javaClass.classLoader?.getResource(path)) { "missing $path" }
        return File(url.toURI())
    }
}
