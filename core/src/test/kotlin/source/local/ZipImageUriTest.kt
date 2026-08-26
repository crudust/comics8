package com.comics8.core.source.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ZipImageUriTest {
    @Test
    fun roundTripsAbsolutePathAndNestedEntry() {
        val root = createTempDirectory("zip-uri").toFile()
        root.deleteOnExit()
        val zip = File(root, "book.cbz")
        zip.writeText("zip")
        val url = ZipImageUri.encode(zip, "nested/001.jpg")
        assertThat(url).startsWith("${ZipImageUri.SCHEME}:")
        assertThat(url).contains("!/")
        assertThat(url).contains("book.cbz")
        val parsed = ZipImageUri.parse(url)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.zip.canonicalFile).isEqualTo(zip.canonicalFile)
        assertThat(parsed.entry).isEqualTo("nested/001.jpg")
    }

    @Test
    fun percentEncodesSpacesUnicodeAndBang() {
        val root = createTempDirectory("zip-uri-enc").toFile()
        root.deleteOnExit()
        val zip = File(root, "my file!만화.zip")
        zip.parentFile.mkdirs()
        zip.writeText("z")
        val url = ZipImageUri.encode(zip, "page 1.jpg")
        assertThat(url).contains("my%20file%21")
        assertThat(url).contains("page%201.jpg")
        assertThat(url).doesNotContain("!만화")
        val parsed = ZipImageUri.parse(url)!!
        assertThat(parsed.zip.canonicalFile).isEqualTo(zip.canonicalFile)
        assertThat(parsed.entry).isEqualTo("page 1.jpg")
    }

    @Test
    fun roundTripsColonInPath() {
        val root = createTempDirectory("zip-uri-colon").toFile()
        root.deleteOnExit()
        val zip = File(root, "artist:asanagi/book.zip")
        zip.parentFile.mkdirs()
        zip.writeText("z")
        val parsed = ZipImageUri.parse(ZipImageUri.encode(zip, "001.jpg"))!!
        assertThat(parsed.zip.canonicalFile).isEqualTo(zip.canonicalFile)
        assertThat(parsed.entry).isEqualTo("001.jpg")
    }

    @Test
    fun parseRejectsOtherSchemesAndZipSlip() {
        assertThat(ZipImageUri.parse("file:///tmp/a.zip!/1.jpg")).isNull()
        assertThat(ZipImageUri.parse("https://example.com/a.zip")).isNull()
        assertThat(ZipImageUri.parse("comics8-zip:///tmp/a.zip!/../evil.jpg")).isNull()
    }

    @Test
    fun encodeNormalizesBackslashesInEntry() {
        val root = createTempDirectory("zip-uri-slash").toFile()
        root.deleteOnExit()
        val zip = File(root, "a.zip")
        zip.writeText("z")
        val parsed = ZipImageUri.parse(ZipImageUri.encode(zip, "folder\\001.jpg"))!!
        assertThat(parsed.entry).isEqualTo("folder/001.jpg")
    }
}
