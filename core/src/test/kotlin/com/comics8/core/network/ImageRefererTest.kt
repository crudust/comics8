package com.comics8.core.network

import com.comics8.core.source.SourceRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageRefererTest {
    @Test
    fun hitomiHostsUseHitomiReferer() {
        val expected = SourceRegistry.defaults().sourceForUrl(
            "https://tn.gold-usergeneratedcontent.net/webpsmalltn/c/ab/x.webp",
        ).imageReferer("https://tn.gold-usergeneratedcontent.net/webpsmalltn/c/ab/x.webp")
        assertThat(ImageReferer.forUrl("https://tn.gold-usergeneratedcontent.net/webpsmalltn/c/ab/x.webp"))
            .isEqualTo(expected)
        assertThat(ImageReferer.forUrl("https://w2.gold-usergeneratedcontent.net/1/3243/x.webp"))
            .isEqualTo(expected)
        assertThat(ImageReferer.forUrl("https://ltn.gold-usergeneratedcontent.net/gg.js"))
            .isEqualTo(expected)
    }

    @Test
    fun elevenHostsKeepOrigin() {
        val url = "https://www.pl3040.com/kr/1.png"
        assertThat(ImageReferer.forUrl(url))
            .isEqualTo(SourceRegistry.defaults().sourceForUrl(url).imageReferer(url))
    }

    @Test
    fun fallbacksIncludeElevenAndHitomiAlts() {
        val eleven = ImageFallbacks.forUrl("https://www.pl3040.com/kr/1.png")
        assertThat(eleven).contains("https://www.pl4050.com/kr/1.png")
        val hitomi = ImageFallbacks.forUrl(
            "https://w2.gold-usergeneratedcontent.net/1690000000/3243/hash.webp",
        )
        assertThat(hitomi).containsExactly(
            "https://a2.gold-usergeneratedcontent.net/1690000000/3243/hash.avif",
        )
        val oldThumb = ImageFallbacks.forUrl("https://tn.hitomi.la/webpsmalltn/c/ab/hash.webp")
        assertThat(oldThumb).contains("https://tn.gold-usergeneratedcontent.net/webpsmalltn/c/ab/hash.webp")
    }

    @Test
    fun fetchBytesRetriesHitomiWebpWithAvif() {
        val webp = "https://w2.gold-usergeneratedcontent.net/1690000000/3243/hash.webp"
        val avif = "https://a2.gold-usergeneratedcontent.net/1690000000/3243/hash.avif"
        val bytes = ImageFallbacks.fetchBytes(webp) { url ->
            if (url == avif) "ok".toByteArray() else error("HTTP 404")
        }
        assertThat(bytes.toString(Charsets.UTF_8)).isEqualTo("ok")
        assertThat(ImageFallbacks.urlsToTry(webp)).containsExactly(webp, avif).inOrder()
    }
}
