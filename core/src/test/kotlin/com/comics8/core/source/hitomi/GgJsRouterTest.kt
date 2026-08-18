package com.comics8.core.source.hitomi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GgJsRouterTest {
    @Test
    fun pinExpectedWebpUrlFromFixture() {
        val gg = GgJsRouter.parse(readFixture("hitomi/gg.js"))
        val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc"
        assertThat(GgJsRouter.pathFromHash(hash)).isEqualTo("c/ab")
        assertThat(GgJsRouter.gFromHash(hash)).isEqualTo(0xCAB)
        assertThat(gg.m(0xCAB)).isEqualTo(1)
        assertThat(gg.subdomainIndex(hash)).isEqualTo(2)
        assertThat(HitomiUrls.imageUrl(hash, gg.b, gg.subdomainIndex(hash)))
            .isEqualTo(
                "https://w2.gold-usergeneratedcontent.net/1690000000/c/ab/" +
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc.webp",
            )
    }

    @Test
    fun defaultMIsZero() {
        val gg = GgJsRouter.parse(readFixture("hitomi/gg.js"))
        val hash = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba987654def"
        assertThat(gg.m(GgJsRouter.gFromHash(hash))).isEqualTo(0)
        assertThat(gg.subdomainIndex(hash)).isEqualTo(1)
    }

    @Test
    fun thumbUsesLastThreeHashChars() {
        val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc"
        assertThat(HitomiUrls.thumbUrl(hash))
            .isEqualTo("https://tn.hitomi.la/webpsmalltn/c/ab/$hash.webp")
    }

    @Test
    fun parseIncludesArrayForm() {
        val js = """var o = [10, 20, 30]; function m(g) { return o.includes(g) ? 1 : 0 } b: "1/" """
        val gg = GgJsRouter.parse(js)
        assertThat(gg.m(20)).isEqualTo(1)
        assertThat(gg.m(99)).isEqualTo(0)
        assertThat(gg.b).isEqualTo("1/")
    }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().readText()
}
