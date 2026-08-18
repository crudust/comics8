package com.comics8.core.network

import com.comics8.core.parser.ComicSite
import com.comics8.core.source.hitomi.HitomiUrls
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageRefererTest {
    @Test
    fun hitomiHostsUseHitomiReferer() {
        assertThat(ImageReferer.forUrl("https://tn.hitomi.la/webpsmalltn/c/ab/x.webp"))
            .isEqualTo(HitomiUrls.REFERER)
        assertThat(ImageReferer.forUrl("https://w2.gold-usergeneratedcontent.net/1/c/ab/x.webp"))
            .isEqualTo(HitomiUrls.REFERER)
        assertThat(ImageReferer.forUrl("https://ltn.gold-usergeneratedcontent.net/gg.js"))
            .isEqualTo(HitomiUrls.REFERER)
    }

    @Test
    fun elevenHostsKeepOrigin() {
        assertThat(ImageReferer.forUrl("https://www.pl3040.com/kr/1.png")).isEqualTo(ComicSite.ORIGIN)
    }
}
