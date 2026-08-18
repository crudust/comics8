package com.comics8.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ImageBatchDownloadTest {
    @Test
    fun existingFilesAreNotFetched() = runBlocking<Unit> {
        val dir = tempDir("img-reuse")
        File(dir, "0001.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "0002.jpg").writeBytes(byteArrayOf(4, 5))
        var fetches = 0
        val result = ImageBatchDownload.toNumberedFiles(
            urls = listOf("https://a.test/1", "https://a.test/2"),
            destDir = dir,
            fetchBytes = {
                fetches++
                byteArrayOf(9)
            },
        )
        assertThat(fetches).isEqualTo(0)
        assertThat(result.files.map { it.name }).containsExactly("0001.jpg", "0002.jpg").inOrder()
        assertThat(result.totalBytes).isEqualTo(5)
    }

    @Test
    fun missingFilesAreFetchedInInputOrder() = runBlocking<Unit> {
        val dir = tempDir("img-fetch")
        val result = ImageBatchDownload.toNumberedFiles(
            urls = listOf("u1", "u2", "u3"),
            destDir = dir,
            fetchBytes = { url -> url.toByteArray() },
        )
        assertThat(result.files.map { it.name }).containsExactly("0001.jpg", "0002.jpg", "0003.jpg").inOrder()
        assertThat(result.files.map { it.readText() }).containsExactly("u1", "u2", "u3").inOrder()
        assertThat(result.totalBytes).isEqualTo("u1u2u3".length.toLong())
    }

    @Test
    fun gapDelayOnlyForActualFetches() = runBlocking<Unit> {
        val dir = tempDir("img-gap")
        File(dir, "0001.jpg").writeBytes(byteArrayOf(1))
        val delays = mutableListOf<Long>()
        ImageBatchDownload.toNumberedFiles(
            urls = listOf("u1", "u2"),
            destDir = dir,
            fetchBytes = { byteArrayOf(2, 3) },
            delayMillis = { delays += it },
        )
        assertThat(delays).containsExactly(ImageBatchDownload.FETCH_GAP_MS)
    }

    private fun tempDir(prefix: String): File {
        val dir = createTempDirectory(prefix).toFile()
        dir.deleteOnExit()
        return dir
    }
}
