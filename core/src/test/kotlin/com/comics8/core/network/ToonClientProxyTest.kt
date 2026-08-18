package com.comics8.core.network

import com.comics8.core.sync.SyncConstants
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToonClientProxyTest {

    @Test
    fun testProxyUrlGeneration() {
        val target = "http://103.204.13.68:8904/bbs/board.php?bo_table=toons"
        val proxyUrl = SyncConstants.proxyUrl(target)
        assertThat(proxyUrl).startsWith("https://homeassistant.tail1946af.ts.net/api/comics8/proxy?url=")
        assertThat(proxyUrl).contains("103.204.13.68%3A8904")
    }

    @Test
    fun testProxyBaseUrlCalculation() {
        val base1 = SyncConstants.proxyBaseUrl("https://homeassistant.tail1946af.ts.net/api/comics8/sync")
        assertThat(base1).isEqualTo("https://homeassistant.tail1946af.ts.net/api/comics8/proxy")

        val base2 = SyncConstants.proxyBaseUrl("http://192.168.0.136:8905")
        assertThat(base2).isEqualTo("http://192.168.0.136:8905/proxy")

        // Crucial bugfix test: already ends with /proxy must not duplicate to /proxy/proxy
        val base3 = SyncConstants.proxyBaseUrl("https://homeassistant.tail1946af.ts.net/api/comics8/proxy")
        assertThat(base3).isEqualTo("https://homeassistant.tail1946af.ts.net/api/comics8/proxy")

        val proxyUrlFromBase = SyncConstants.proxyUrl("http://103.204.13.68:8904/bbs/board.php", base3)
        assertThat(proxyUrlFromBase).startsWith("https://homeassistant.tail1946af.ts.net/api/comics8/proxy?url=")
        assertThat(proxyUrlFromBase).doesNotContain("/proxy/proxy")
    }

    @Test
    fun hitomiHostsGoDirect() {
        assertThat(ToonClient.shouldUseProxy("https://hitomi.la/galleries/1.html")).isFalse()
        assertThat(ToonClient.shouldUseProxy("https://tn.hitomi.la/webpsmalltn/1.webp")).isFalse()
        assertThat(ToonClient.shouldUseProxy("https://aa.gold-usergeneratedcontent.net/1.webp")).isFalse()
        assertThat(ToonClient.shouldUseProxy("https://gold-usergeneratedcontent.net/1.webp")).isFalse()
        assertThat(ToonClient.shouldUseProxy("https://evilgold-usergeneratedcontent.net/1.webp")).isTrue()
        assertThat(ToonClient.shouldUseProxy("http://103.204.13.68:8904/bbs/board.php")).isTrue()
        assertThat(ToonClient.shouldUseProxy("https://www.pl3040.com/kr/1.png")).isTrue()
    }
}
