package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HttpResultTest {
    @Test
    fun totalLengthPrefersContentRangeTotal() {
        val result = HttpResult(
            code = 206,
            headers = mapOf(
                "Content-Range" to "bytes 0-99/4096",
                "Content-Length" to "100",
            ),
            body = ByteArray(100),
        )
        assertThat(result.totalLength()).isEqualTo(4096)
    }

    @Test
    fun totalLengthReadsStarSlashForm() {
        val result = HttpResult(
            code = 200,
            headers = mapOf("Content-Range" to "bytes */2048"),
            body = ByteArray(10),
        )
        assertThat(result.totalLength()).isEqualTo(2048)
    }

    @Test
    fun totalLengthReadsSatisfiedRangeForm() {
        val result = HttpResult(
            code = 200,
            headers = mapOf(
                "Content-Range" to "bytes 0-99/4096",
                "Content-Length" to "100",
            ),
            body = ByteArray(100),
        )
        assertThat(result.totalLength()).isEqualTo(4096)
    }

    @Test
    fun totalLengthFallsBackToContentLength() {
        val result = HttpResult(
            code = 200,
            headers = mapOf("content-length" to "512"),
            body = ByteArray(512),
        )
        assertThat(result.totalLength()).isEqualTo(512)
    }

    @Test
    fun headerLookupIsCaseInsensitive() {
        val result = HttpResult(
            code = 200,
            headers = mapOf("Content-Type" to "application/octet-stream"),
            body = ByteArray(0),
        )
        assertThat(result.header("content-type")).isEqualTo("application/octet-stream")
        assertThat(result.header("X-Missing")).isNull()
    }
}
