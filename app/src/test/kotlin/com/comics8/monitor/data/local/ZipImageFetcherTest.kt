package com.comics8.monitor.data.local

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.fetch.SourceResult
import coil.request.Options
import com.comics8.core.source.local.ZipImageUri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ZipImageFetcherTest {
    @Test
    fun factoryMatchesComics8ZipSchemeAndReadsEntry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val zip = File(context.cacheDir, "fetcher-${System.nanoTime()}.cbz")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("nested/001.jpg"))
            zos.write("jpeg-bytes".toByteArray())
            zos.closeEntry()
        }
        val url = ZipImageUri.encode(zip, "nested/001.jpg")
        assertThat(url).startsWith("${ZipImageUri.SCHEME}:")
        val parsed = Uri.parse(url)
        assertThat(parsed.scheme).isEqualTo(ZipImageUri.SCHEME)
        assertThat(ZipImageUri.parse(parsed.toString())?.entry).isEqualTo("nested/001.jpg")

        val loader = ImageLoader.Builder(context).build()
        val fetcher = ZipImageFetcher.Factory().create(parsed, Options(context), loader)
        assertThat(fetcher).isNotNull()
        val result = fetcher!!.fetch() as SourceResult
        assertThat(result.mimeType).isEqualTo("image/jpeg")
        assertThat(result.source.source().readUtf8()).isEqualTo("jpeg-bytes")
    }

    @Test
    fun factoryIgnoresHttpAndFileUris() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = ImageLoader.Builder(context).build()
        val factory = ZipImageFetcher.Factory()
        assertThat(factory.create(Uri.parse("https://example.com/a.jpg"), Options(context), loader)).isNull()
        assertThat(factory.create(Uri.parse("file:///tmp/a.jpg"), Options(context), loader)).isNull()
    }
}
