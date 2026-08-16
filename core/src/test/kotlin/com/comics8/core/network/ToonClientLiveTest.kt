package com.comics8.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToonClientLiveTest {
    @Test
    fun fetchImagesWithFallbackCdn() {
        val client = ToonClient()
        val url = "http://103.204.13.68:8904/bbs/board.php?bo_table=toons&wr_id=1827519&stx=%EB%B3%80%EA%B2%BD%EC%82%B4%EC%9D%B4%20%ED%95%98%EB%8A%94%20%EB%A7%88%EC%99%95,%20%EC%A0%84%EC%83%9D%ED%95%B4%EC%84%9C%20%EC%B5%9C%EA%B0%95%EC%9D%98%20%EB%A7%88%EC%88%A0%EC%82%AC%EA%B0%80%20%EB%90%9C%EB%8B%A4&is=24658"
        val images = client.fetchImages(url)
        println("Images count: ${images.size}")
        images.take(3).forEach { println("Image: $it") }
        assertThat(images).isNotEmpty()
        assertThat(images[0]).contains("pl4050.com")
    }
}
