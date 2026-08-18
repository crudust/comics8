package com.comics8.core.source.hitomi

import com.comics8.core.source.HttpResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NozomiIndexTest {
    @Test
    fun parseIdsReadsBigEndianUint32NewestFirst() {
        val body = byteArrayOf(
            0x00, 0x00, 0x03, 0xE8.toByte(),
            0x00, 0x01, 0x00, 0x00,
            0x12, 0x34, 0x56, 0x78,
        )
        assertThat(NozomiIndex.parseIds(body)).containsExactly(1000L, 65536L, 0x12345678L).inOrder()
    }

    @Test
    fun lastPageUsesContentRangeTotal() {
        val result = HttpResult(
            code = 206,
            headers = mapOf("Content-Range" to "bytes 0-99/400"),
            body = ByteArray(100),
        )
        val ids = List(25) { it.toLong() }
        assertThat(NozomiIndex.lastPage(1, ids, result.totalLength())).isEqualTo(4)
    }

    @Test
    fun lastPageFallbackWhenNoLength() {
        assertThat(NozomiIndex.lastPage(2, List(25) { it.toLong() }, null)).isEqualTo(3)
        assertThat(NozomiIndex.lastPage(2, List(10) { it.toLong() }, null)).isEqualTo(2)
    }

    @Test
    fun rangeHeaderMatchesPageWindow() {
        assertThat(HitomiUrls.rangeHeader(1)).isEqualTo("bytes=0-99")
        assertThat(HitomiUrls.rangeHeader(2)).isEqualTo("bytes=100-199")
        assertThat(HitomiUrls.rangeHeader(3)).isEqualTo("bytes=200-299")
    }
}
