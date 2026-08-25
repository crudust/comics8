package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LocalImageUriTest {
    @Test
    fun roundTripsPathWithColon() {
        val root = createTempDirectory("local-uri").toFile()
        root.deleteOnExit()
        val file = File(root, "hitomi/artist:asanagi/4128037/0001.jpg")
        file.parentFile.mkdirs()
        file.writeText("img")

        val uri = LocalImageUri.fromFile(file)
        assertThat(uri).startsWith("file:")
        val back = LocalImageUri.toFile(uri)
        assertThat(back).isNotNull()
        assertThat(back!!.canonicalFile).isEqualTo(file.canonicalFile)
        assertThat(back.readText()).isEqualTo("img")
    }

    @Test
    fun readsUnencodedFileUrlWithColon() {
        val root = createTempDirectory("local-uri-raw").toFile()
        root.deleteOnExit()
        val file = File(root, "artist:asanagi/0001.jpg")
        file.parentFile.mkdirs()
        file.writeText("raw")

        val raw = "file://" + file.absolutePath
        val back = LocalImageUri.toFile(raw)
        assertThat(back).isNotNull()
        assertThat(back!!.readText()).isEqualTo("raw")
    }

    @Test
    fun ignoresHttpUrls() {
        assertThat(LocalImageUri.toFile("https://example.com/a.jpg")).isNull()
    }
}
