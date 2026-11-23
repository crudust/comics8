package com.comics8.desktop.ui

import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ComicImageInterceptor
import com.comics8.core.source.ComicSource
import com.comics8.core.source.LocalImageUri
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.local.ZipImageUri
import com.comics8.desktop.ui.util.DesktopImageCache
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class DesktopImageCacheTest {
    @Test
    fun loadsLocalFileUrlWithColonInPath() {
        val root = createTempDirectory("desk-img").toFile()
        root.deleteOnExit()
        val file = File(root, "hitomi/artist:asanagi/0001.jpg")
        file.parentFile.mkdirs()
        file.writeBytes(ONE_PIXEL_PNG)

        val encoded = LocalImageUri.fromFile(file)
        val raw = "file://" + file.absolutePath
        for (url in listOf(encoded, raw)) {
            assertThat(DesktopImageCache.readImageBytes(url)).isEqualTo(ONE_PIXEL_PNG)
        }
    }

    @Test
    fun loadsComics8ZipUri() {
        val root = createTempDirectory("desk-zip-img").toFile()
        root.deleteOnExit()
        val zip = File(root, "book.cbz")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("nested/0001.png"))
            zos.write(ONE_PIXEL_PNG)
            zos.closeEntry()
        }
        val url = ZipImageUri.encode(zip, "nested/0001.png")
        assertThat(url).startsWith("${ZipImageUri.SCHEME}:")
        assertThat(DesktopImageCache.readImageBytes(url)).isEqualTo(ONE_PIXEL_PNG)
    }

    @Test
    fun decodesAvifImage() {
        val file = File("/tmp/test_001.avif")
        if (file.exists()) {
            val bytes = file.readBytes()
            val avif = org.glavo.avif.AvifImage.read(bytes)
            val frame = avif.firstFrame()
            assertThat(frame.width()).isEqualTo(2036)
            assertThat(frame.height()).isEqualTo(2880)

            val w = frame.width()
            val h = frame.height()
            val argb = frame.intPixels()
            val rgba = ByteArray(w * h * 4)
            var srcIdx = 0
            var dstIdx = 0
            while (srcIdx < argb.size) {
                val pixel = argb[srcIdx++]
                rgba[dstIdx++] = ((pixel ushr 16) and 0xFF).toByte()
                rgba[dstIdx++] = ((pixel ushr 8) and 0xFF).toByte()
                rgba[dstIdx++] = (pixel and 0xFF).toByte()
                rgba[dstIdx++] = ((pixel ushr 24) and 0xFF).toByte()
            }
            val info = org.jetbrains.skia.ImageInfo(
                width = w,
                height = h,
                colorType = org.jetbrains.skia.ColorType.RGBA_8888,
                alphaType = org.jetbrains.skia.ColorAlphaType.UNPREMUL,
            )
            val skiaImage = org.jetbrains.skia.Image.makeRaster(info, rgba, w * 4)
            assertThat(skiaImage).isNotNull()
            assertThat(skiaImage.width).isEqualTo(2036)
            assertThat(skiaImage.height).isEqualTo(2880)
            skiaImage.close()

            val webp = com.comics8.desktop.ui.util.AwtThumbEncoder.webp(bytes, 320, 80)
            assertThat(webp).isNotEmpty()
        }
    }

    @Test
    fun decodesReaderWithShortEdgeRetention() {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(200, 3000)
        val image = surface.makeImageSnapshot()
        val data = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, 100)!!
        val bytes = data.bytes
        surface.close()
        image.close()

        val decodedReader = DesktopImageCache.decodeForDisplay(bytes, com.comics8.core.image.ImageCacheRole.READER)
        assertThat(decodedReader).isNotNull()
        assertThat(decodedReader!!.width).isEqualTo(200)
        assertThat(decodedReader.height).isEqualTo(3000)

        val decodedGrid = DesktopImageCache.decodeForDisplay(bytes, com.comics8.core.image.ImageCacheRole.GRID)
        assertThat(decodedGrid).isNotNull()
        assertThat(decodedGrid!!.height).isEqualTo(480)
        assertThat(decodedGrid.width).isEqualTo(32)
    }

    @Test
    fun corruptImageBytesFallBackToAlternateUrl() = runBlocking {
        val prevClient = DesktopImageCache.httpClient
        val unique = System.nanoTime()
        val origUrl = "https://host-a.example/corrupt-$unique.png"
        val fbUrl = "https://host-b.example/corrupt-$unique.png"
        val reg = SourceRegistry(
            listOf(
                testFallbackSource(
                    id = "desk-corrupt",
                    origin = "https://host-a.example",
                    hosts = setOf("host-a.example", "host-b.example"),
                    fallbacks = { url -> if (url == origUrl) listOf(fbUrl) else emptyList() },
                ),
            ),
        )
        val requested = mutableListOf<String>()
        DesktopImageCache.registry = reg
        DesktopImageCache.shareHttpClient(
            OkHttpClient.Builder()
                .addInterceptor(ComicImageInterceptor(reg))
                .addInterceptor { chain ->
                    val req = chain.request()
                    val url = req.url.toString()
                    requested += url
                    val body = if (url == origUrl) {
                        "not-a-valid-image".toByteArray().toResponseBody("application/octet-stream".toMediaType())
                    } else {
                        ONE_PIXEL_PNG.toResponseBody("image/png".toMediaType())
                    }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build()
                }
                .build(),
        )
        try {
            val bitmap = DesktopImageCache.loadImage(ImageCacheRole.READER, origUrl, forceRetry = false)
            assertThat(bitmap).isNotNull()
            assertThat(requested).containsExactly(origUrl, fbUrl).inOrder()
        } finally {
            DesktopImageCache.evict(ImageCacheRole.READER, origUrl)
            DesktopImageCache.shareHttpClient(prevClient)
        }
    }

    @Test
    fun forceRetrySendsManualRetryHeaderAndRecoversAfterTimeout() = runBlocking {
        val prevClient = DesktopImageCache.httpClient
        val unique = System.nanoTime()
        val origUrl = "https://host-a.example/timeout-$unique.png"
        val fb1 = "https://host-b.example/timeout-$unique.png"
        val fb2 = "https://host-c.example/timeout-$unique.png"
        val fb3 = "https://host-d.example/timeout-$unique.png"
        val reg = SourceRegistry(
            listOf(
                testFallbackSource(
                    id = "desk-manual",
                    origin = "https://host-a.example",
                    hosts = setOf("host-a.example", "host-b.example", "host-c.example", "host-d.example"),
                    fallbacks = { url -> if (url == origUrl) listOf(fb1, fb2, fb3) else emptyList() },
                ),
            ),
        )
        val requested = mutableListOf<String>()
        DesktopImageCache.registry = reg
        DesktopImageCache.shareHttpClient(
            OkHttpClient.Builder()
                .addInterceptor(ComicImageInterceptor(reg, maxFallbacks = 2))
                .addInterceptor { chain ->
                    val req = chain.request()
                    val url = req.url.toString()
                    requested += url
                    assertThat(req.header(ComicImageInterceptor.HEADER_RETRY_MODE)).isNull()
                    when (url) {
                        origUrl -> throw SocketTimeoutException("Read timed out")
                        fb1, fb2 -> Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                            .build()
                        else -> Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ONE_PIXEL_PNG.toResponseBody("image/png".toMediaType()))
                            .build()
                    }
                }
                .build(),
        )
        try {
            // Auto mode must fail on SocketTimeoutException without touching fallbacks
            val autoResult = DesktopImageCache.loadImage(ImageCacheRole.READER, origUrl, forceRetry = false)
            assertThat(autoResult).isNull()
            assertThat(requested).containsExactly(origUrl)

            // Manual retry (forceRetry = true) triggers fallback on timeout and tries >2 fallbacks
            requested.clear()
            val manualResult = DesktopImageCache.loadImage(ImageCacheRole.READER, origUrl, forceRetry = true)
            assertThat(manualResult).isNotNull()
            assertThat(requested).containsExactly(origUrl, fb1, fb2, fb3).inOrder()
        } finally {
            DesktopImageCache.evict(ImageCacheRole.READER, origUrl)
            DesktopImageCache.shareHttpClient(prevClient)
        }
    }

    private fun testFallbackSource(
        id: String,
        origin: String,
        hosts: Set<String>,
        fallbacks: (String) -> List<String>,
    ): ComicSource = object : ComicSource {
        override val id: String = id
        override val displayName: String = id
        override val origin: String = origin
        override val catalogs: List<SourceCatalog> = emptyList()
        override val defaultPolicy: RequestPolicy = RequestPolicy(userAgent = "test-agent")
        override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp) =
            ListingPage(emptyList(), 1, 1)
        override suspend fun search(query: SearchQuery, http: SourceHttp) = emptyList<ToonItem>()
        override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp) =
            EpisodePage(emptyList(), 1, 1)
        override suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp) =
            emptyList<String>()
        override fun imageFallbacks(url: String): List<String> = fallbacks(url)
        override fun ownsHost(host: String): Boolean = host.lowercase() in hosts
    }

    companion object {
        private val ONE_PIXEL_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
            0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
            0x00, 0x03, 0x00, 0x01, 0x00, 0x05, 0xFE.toByte(), 0xD4.toByte(),
            0xEF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
