package com.comics8.monitor.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.DownloadLayout
import com.comics8.core.source.WorkId
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

            val manager = DownloadManager(context, db, ToonClient(isProxyEnabled = false), downloads)
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
    fun hitomiWebpFallsBackToAvif() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val downloads = File(context.filesDir, "dl-hitomi-${System.nanoTime()}").apply { mkdirs() }
            val webp = "https://w2.gold-usergeneratedcontent.net/1690000000/c/ab/hash.webp"
            val avif = "https://a2.gold-usergeneratedcontent.net/1690000000/c/ab/hash.avif"
            val client = ToonClient(imageClient(webp, avif), isProxyEnabled = false)
            val manager = DownloadManager(context, db, client, downloads)
            manager.enqueueEpisodes(
                ToonItem(id = "123", title = "hitomi-gal", thumbUrl = "", href = "https://example.test/toon"),
                listOf(
                    EpisodeItem(
                        wrId = "1",
                        title = "ep",
                        date = null,
                        thumbUrl = null,
                        href = "https://example.test/ep",
                    ),
                ),
            )
            val dest = DownloadLayout.episodeDir(downloads, WorkId.eleven("123"), "1")
            val img = File(dest, "0001.jpg")
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && (!img.exists() || img.length() == 0L)) {
                Thread.sleep(50)
            }
            assertThat(img.exists()).isTrue()
            assertThat(img.readText()).isEqualTo("avif-bytes")
        } finally {
            db.close()
        }
    }

    private fun imageClient(webp: String, avif: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val (code, body) = when {
                    url.contains("/ep") -> 200 to """<script>var img_list = ["$webp"];</script>"""
                    url == webp -> 404 to "missing"
                    url == avif -> 200 to "avif-bytes"
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
}
