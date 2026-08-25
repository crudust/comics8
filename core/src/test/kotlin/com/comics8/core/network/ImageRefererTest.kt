package com.comics8.core.network

import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.StubComicSource
import com.comics8.core.source.hostSuffixes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageRefererTest {
    private val source = StubComicSource(
        id = "demo",
        origin = "https://demo.example",
        defaultPolicy = RequestPolicy(userAgent = "test", referer = "https://demo.example/"),
        ownedHost = hostSuffixes("demo.example", "cdn.example"),
        fallbacks = { url ->
            buildList {
                if (url.endsWith(".webp")) add(url.replace(".webp", ".avif"))
                if (url.contains("old.cdn.example")) {
                    add(url.replace("old.cdn.example", "cdn.example"))
                }
            }
        },
    )
    private val registry = SourceRegistry(listOf(source))

    @Test
    fun owningSourceSuppliesReferer() {
        val url = "https://img.cdn.example/x.webp"
        assertThat(ImageReferer.forUrl(url, registry)).isEqualTo("https://demo.example/")
        assertThat(ImageReferer.forUrl("https://demo.example/cover.png", registry))
            .isEqualTo("https://demo.example/")
    }

    @Test
    fun unknownHostHasEmptyReferer() {
        assertThat(ImageReferer.forUrl("https://example.invalid/x.png", registry)).isEmpty()
    }

    @Test
    fun fallbacksComeFromOwningSource() {
        assertThat(ImageFallbacks.forUrl("https://img.cdn.example/hash.webp", registry))
            .containsExactly("https://img.cdn.example/hash.avif")
        assertThat(ImageFallbacks.forUrl("https://old.cdn.example/hash.webp", registry))
            .containsExactly(
                "https://old.cdn.example/hash.avif",
                "https://cdn.example/hash.webp",
            )
    }

    @Test
    fun fetchBytesRetriesFallbackUrl() {
        val webp = "https://img.cdn.example/hash.webp"
        val avif = "https://img.cdn.example/hash.avif"
        val bytes = ImageFallbacks.fetchBytes(webp, registry) { url ->
            if (url == avif) "ok".toByteArray() else error("HTTP 404")
        }
        assertThat(bytes.toString(Charsets.UTF_8)).isEqualTo("ok")
        assertThat(ImageFallbacks.urlsToTry(webp, registry)).containsExactly(webp, avif).inOrder()
    }
}
