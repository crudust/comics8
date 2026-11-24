package com.comics8.shared.image

import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.asImage
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toUri
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.image.ImageMemoryPolicy
import com.comics8.core.model.ImageTile
import com.comics8.core.source.LocalImageUri
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.core.source.local.ZipImageUri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class SharedImagePipelineTest {
    @Test
    fun rolePartitionedMemoryCacheIsolatesEvictionByRole() {
        val cache = RolePartitionedMemoryCache(ImageMemoryPolicy.partition(4L * 1024L * 1024L))
        val bmp = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(2, 2)) }
        val img = bmp.asImage()
        val gridKey = MemoryCache.Key(ImageCacheRole.GRID.cacheKey("https://example.com/cover.jpg"))
        val tile0Key = MemoryCache.Key(
            ImageCacheRole.READER.cacheKey(readerTileIdentity("https://example.com/p1.jpg", ImageTile(0, 0, 2))),
        )
        val tile1Key = MemoryCache.Key(
            ImageCacheRole.READER.cacheKey(readerTileIdentity("https://example.com/p1.jpg", ImageTile(0, 1, 2))),
        )
        val otherReaderKey = MemoryCache.Key(
            ImageCacheRole.READER.cacheKey("https://example.com/p2.jpg"),
        )

        cache[gridKey] = MemoryCache.Value(img)
        cache[tile0Key] = MemoryCache.Value(img)
        cache[tile1Key] = MemoryCache.Value(img)
        cache[otherReaderKey] = MemoryCache.Value(img)

        // Evicting p1's slices keeps both GRID and p2 intact.
        val removed = cache.removeMatching(ImageCacheRole.READER, readerTileIdentityPrefix("https://example.com/p1.jpg"))
        assertThat(removed).isEqualTo(2)
        assertThat(cache[gridKey]).isNotNull()
        assertThat(cache[otherReaderKey]).isNotNull()
        assertThat(cache[tile0Key]).isNull()

        // Clearing READER keeps GRID intact.
        cache.clear(ImageCacheRole.READER)
        assertThat(cache[otherReaderKey]).isNull()
        assertThat(cache[gridKey]).isNotNull()
    }

    @Test
    fun skiaReaderTileDecoderSplitsTallImageIntoExpectedSlices() = runBlocking {
        val root = createTempDirectory("shared-tile-test").toFile().apply { deleteOnExit() }
        val pngFile = File(root, "strip.png")
        val surface = Surface.makeRasterN32Premul(100, 1000)
        val snapshot = surface.makeImageSnapshot()
        pngFile.writeBytes(snapshot.encodeToData(EncodedImageFormat.PNG, 100)!!.bytes)
        snapshot.close()
        surface.close()

        val loader = buildComicsImageLoader(
            context = PlatformContext.INSTANCE,
            httpClient = OkHttpClient(),
            coverThumbs = CoverThumbCache(File(root, "thumbs"), SkiaThumbEncoder),
            readerCacheDir = File(root, "reader"),
            diskCacheDir = File(root, "disk"),
            diskCacheMaxBytes = 8L * 1024L * 1024L,
            memoryCache = RolePartitionedMemoryCache(ImageMemoryPolicy.partition(8L * 1024L * 1024L)),
            fallbacksProvider = { emptyList() },
            extraComponents = { add(SkiaReaderTileDecoder.Factory()) },
        )
        try {
            val url = LocalImageUri.fromFile(pngFile)
            val tile0 = ImageTile(imageIndex = 0, tileIndex = 0, tileCount = 4)
            val res0 = loader.execute(
                coil3.request.ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data(url)
                    .imageCacheRole(ImageCacheRole.READER, readerTileIdentity(url, tile0))
                    .readerTile(tile0)
                    .readerImageSizing(Size(1800, 16384))
                    .build(),
            ) as SuccessResult
            assertThat(res0.image.width).isEqualTo(100)
            assertThat(res0.image.height).isEqualTo(250)
            assertThat(res0.image).isInstanceOf(BitmapImage::class.java)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun localAndZipFetchersHandleColonPathsAndArchiveEntries() = runBlocking {
        val root = createTempDirectory("shared-fetchers").toFile().apply { deleteOnExit() }
        val surface = Surface.makeRasterN32Premul(2, 3)
        val snapshot = surface.makeImageSnapshot()
        val pngBytes = snapshot.encodeToData(EncodedImageFormat.PNG, 100)!!.bytes
        snapshot.close()
        surface.close()

        val colonFile = File(root, "author:foo/page.png").apply {
            parentFile.mkdirs()
            writeBytes(pngBytes)
        }
        val zipFile = File(root, "chap.cbz")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("001.webp"))
            zos.write("webp".toByteArray())
            zos.closeEntry()
        }

        val loader = buildComicsImageLoader(
            context = PlatformContext.INSTANCE,
            httpClient = OkHttpClient(),
            coverThumbs = CoverThumbCache(File(root, "thumbs"), SkiaThumbEncoder),
            readerCacheDir = File(root, "reader"),
            diskCacheDir = File(root, "disk"),
            diskCacheMaxBytes = 8L * 1024L * 1024L,
            memoryCache = RolePartitionedMemoryCache(ImageMemoryPolicy.partition(8L * 1024L * 1024L)),
            fallbacksProvider = { emptyList() },
        )
        try {
            val fileFetcher = LocalFileImageFetcher.Factory()
                .create(("file://" + colonFile.absolutePath).toUri(), Options(PlatformContext.INSTANCE), loader)
            assertThat(fileFetcher).isNotNull()
            val fileResult = fileFetcher!!.fetch() as SourceFetchResult
            assertThat(fileResult.mimeType).isEqualTo("image/png")
            assertThat(fileResult.source.source().readByteArray()).isEqualTo(pngBytes)

            val singleSlashResult = loader.execute(
                coil3.request.ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data(LocalImageUri.fromFile(colonFile))
                    .build(),
            ) as SuccessResult
            assertThat(singleSlashResult.image.width).isEqualTo(2)
            assertThat(singleSlashResult.image.height).isEqualTo(3)

            val zipUri = ZipImageUri.encode(zipFile, "001.webp").toUri()
            val zipFetcher = ZipImageFetcher.Factory().create(zipUri, Options(PlatformContext.INSTANCE), loader)
            assertThat(zipFetcher).isNotNull()
            val zipResult = zipFetcher!!.fetch() as SourceFetchResult
            assertThat(zipResult.mimeType).isEqualTo("image/webp")
            assertThat(zipResult.source.source().readUtf8()).isEqualTo("webp")
        } finally {
            loader.shutdown()
        }
    }
}
