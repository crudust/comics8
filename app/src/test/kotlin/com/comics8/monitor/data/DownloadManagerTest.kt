package com.comics8.monitor.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.ComicSource
import com.comics8.core.source.DownloadLayout
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.core.source.local.LocalSource
import com.comics8.core.source.local.ThumbEncoder
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.DownloadedEpisode
import com.comics8.monitor.data.local.get
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DownloadManagerTest {
    @Test
    fun migrateMovesLegacyDirAndUpdatesLocalDirPath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val downloads = File(context.filesDir, "dl-test-${System.nanoTime()}").apply { mkdirs() }
            val legacy = DownloadLayout.legacyEpisodeDir(downloads, "123", "999")
            legacy.mkdirs()
            File(legacy, "0001.jpg").writeText("img")
            db.downloadedEpisodeDao().upsert(
                DownloadedEpisode(
                    sourceId = WorkId.DEFAULT_SOURCE,
                    toonId = "123",
                    wrId = "999",
                    toonTitle = "원피스",
                    toonThumbUrl = "t",
                    toonHref = "h",
                    episodeTitle = "1화",
                    episodeHref = "e",
                    imageCount = 1,
                    totalBytes = 3,
                    downloadedAt = 1L,
                    localDirPath = legacy.absolutePath,
                ),
            )

            val manager = DownloadManager(
                context,
                db,
                ToonClient(isProxyEnabled = false, sources = emptyLocator()),
                baseDir = downloads,
                sources = SourceRegistry(),
            )
            manager.migrateLegacyDownloads()

            val neu = DownloadLayout.episodeDir(downloads, WorkId.eleven("123"), "999")
            assertThat(File(neu, "0001.jpg").readText()).isEqualTo("img")
            assertThat(legacy.exists()).isFalse()
            val stored = db.downloadedEpisodeDao().get(WorkId.eleven("123"), "999")
            assertThat(stored?.localDirPath).isEqualTo(neu.absolutePath)
            val images = manager.getLocalEpisodeImages(WorkId.eleven("123"), "999")
            assertThat(images).isNotNull()
            assertThat(images!!.single()).endsWith("/0001.jpg")
        } finally {
            db.close()
        }
    }

    @Test
    fun incompleteDestDoesNotRewriteLocalDirPath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val downloads = File(context.filesDir, "dl-partial-${System.nanoTime()}").apply { mkdirs() }
            val workId = WorkId.eleven("123")
            val dest = DownloadLayout.episodeDir(downloads, workId, "999")
            dest.mkdirs()
            File(dest, "0001.jpg").writeText("partial")
            val legacy = DownloadLayout.legacyEpisodeDir(downloads, "123", "999")
            legacy.mkdirs()
            File(legacy, "0001.jpg").writeText("1")
            File(legacy, "0002.jpg").writeText("2")
            db.downloadedEpisodeDao().upsert(
                DownloadedEpisode(
                    sourceId = WorkId.DEFAULT_SOURCE,
                    toonId = "123",
                    wrId = "999",
                    toonTitle = "원피스",
                    toonThumbUrl = "t",
                    toonHref = "h",
                    episodeTitle = "1화",
                    episodeHref = "e",
                    imageCount = 2,
                    totalBytes = 2,
                    downloadedAt = 1L,
                    localDirPath = legacy.absolutePath,
                ),
            )

            val manager = DownloadManager(
                context,
                db,
                ToonClient(isProxyEnabled = false, sources = emptyLocator()),
                baseDir = downloads,
                sources = SourceRegistry(),
            )
            manager.migrateLegacyDownloads()

            val stored = db.downloadedEpisodeDao().get(workId, "999")
            assertThat(stored?.localDirPath).isEqualTo(legacy.absolutePath)
            assertThat(File(legacy, "0002.jpg").exists()).isTrue()
            val images = manager.getLocalEpisodeImages(workId, "999")
            assertThat(images).hasSize(2)
        } finally {
            db.close()
        }
    }

    @Test
    fun hitomiResolveImagesRetriesWebpWithAvif() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val downloads = File(context.filesDir, "dl-hitomi-${System.nanoTime()}").apply { mkdirs() }
            val sources = SourceRegistry(listOf(FallbackSource()))
            val client = ToonClient(hitomiClient(), isProxyEnabled = false, sources = SourceLocator { sources })
            val manager = DownloadManager(
                context,
                db,
                client,
                isSourceEnabled = { true },
                baseDir = downloads,
                sources = sources,
            )
            val toonId = "gallery:1001"
            manager.enqueueEpisodes(
                ToonItem(
                    id = toonId,
                    title = "hitomi-gal",
                    thumbUrl = "",
                    href = "https://hitomi.la/galleries/1001.html",
                    sourceId = "hitomi",
                ),
                listOf(
                    EpisodeItem(
                        wrId = "1001",
                        title = "ep",
                        date = null,
                        thumbUrl = null,
                        href = "https://hitomi.la/reader/1001.html",
                    ),
                ),
            )
            val workId = WorkId("hitomi", toonId)
            val dest = DownloadLayout.episodeDir(downloads, workId, "1001")
            val img = File(dest, "0001.jpg")
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val running = manager.progress.value.isRunning || manager.progress.value.queueSize > 0
                if (img.exists() && img.length() > 0 && !running &&
                    db.downloadedEpisodeDao().get(workId, "1001") != null
                ) {
                    break
                }
                Thread.sleep(50)
            }
            assertThat(img.exists()).isTrue()
            assertThat(img.readText()).isEqualTo("avif-bytes")
            val stored = db.downloadedEpisodeDao().get(workId, "1001")
            assertThat(stored?.sourceId).isEqualTo("hitomi")
        } finally {
            db.close()
        }
    }

    @Test
    fun enqueueLocalDoesNotCreateDownloadFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val downloads = File(context.filesDir, "dl-local-${System.nanoTime()}").apply { mkdirs() }
            val thumbs = CoverThumbCache(
                File(context.cacheDir, "thumbs-local-${System.nanoTime()}"),
                ThumbEncoder { bytes, _, _ -> bytes },
            )
            val registry = SourceRegistry(
                listOf(LocalSource(roots = { emptyList() }, thumbs = thumbs)),
            )
            val manager = DownloadManager(
                context,
                db,
                ToonClient(isProxyEnabled = false, sources = SourceLocator { registry }),
                isSourceEnabled = { true },
                baseDir = downloads,
                sources = registry,
                installedIds = { setOf(WorkId.LOCAL_SOURCE) },
            )
            val series = ToonItem(
                id = "zip:/tmp/a.zip",
                title = "Local",
                thumbUrl = "",
                href = "",
                sourceId = WorkId.LOCAL_SOURCE,
            )
            manager.enqueueEpisodes(
                series,
                listOf(
                    EpisodeItem(
                        wrId = "/tmp/a.zip",
                        title = "a",
                        date = null,
                        thumbUrl = null,
                        href = "",
                    ),
                ),
            )
            Thread.sleep(100)
            assertThat(File(downloads, WorkId.LOCAL_SOURCE).exists()).isFalse()
            assertThat(
                DownloadLayout.episodeDir(downloads, WorkId.local(series.id), "/tmp/a.zip").exists(),
            ).isFalse()
        } finally {
            db.close()
        }
    }

    private fun hitomiClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val (code, body) = when (url) {
                    WEBP -> 404 to "missing"
                    AVIF -> 200 to "avif-bytes"
                    else -> 404 to "no"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(body.toByteArray().toResponseBody("text/html".toMediaType()))
                    .build()
            })
            .build()

    private class FallbackSource : ComicSource {
        override val id: String = "hitomi"
        override val displayName: String = "Hitomi"
        override val origin: String = "https://hitomi.la"
        override val catalogs: List<SourceCatalog> = emptyList()
        override val defaultPolicy: RequestPolicy = RequestPolicy(userAgent = "test")

        override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage =
            ListingPage(emptyList(), page, page)

        override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> = emptyList()

        override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage =
            EpisodePage(emptyList(), page, page)

        override suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp): List<String> =
            listOf(WEBP)

        override fun imageFallbacks(url: String): List<String> =
            if (url == WEBP) listOf(AVIF) else emptyList()

        override fun ownsHost(host: String): Boolean {
            val key = host.lowercase()
            return key == "hitomi.la" ||
                key.endsWith(".hitomi.la") ||
                key == "gold-usergeneratedcontent.net" ||
                key.endsWith(".gold-usergeneratedcontent.net")
        }
    }

    companion object {
        private fun emptyLocator(): SourceLocator = SourceLocator { SourceRegistry() }

        private const val HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc"
        private const val WEBP =
            "https://w2.gold-usergeneratedcontent.net/1690000000/3243/$HASH.webp"
        private const val AVIF =
            "https://a2.gold-usergeneratedcontent.net/1690000000/3243/$HASH.avif"
    }
}
