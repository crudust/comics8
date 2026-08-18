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
import com.comics8.core.source.hitomi.HitomiUrls
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
                ToonClient(isProxyEnabled = false),
                baseDir = downloads,
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
                ToonClient(isProxyEnabled = false),
                baseDir = downloads,
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
            val client = ToonClient(hitomiClient(), isProxyEnabled = false)
            val manager = DownloadManager(
                context,
                db,
                client,
                hitomiEnabled = { true },
                baseDir = downloads,
            )
            val toonId = "gallery:1001"
            manager.enqueueEpisodes(
                ToonItem(
                    id = toonId,
                    title = "hitomi-gal",
                    thumbUrl = "",
                    href = HitomiUrls.galleryPage("1001"),
                    sourceId = HitomiUrls.SOURCE_ID,
                ),
                listOf(
                    EpisodeItem(
                        wrId = "1001",
                        title = "ep",
                        date = null,
                        thumbUrl = null,
                        href = HitomiUrls.reader("1001"),
                    ),
                ),
            )
            val workId = WorkId(HitomiUrls.SOURCE_ID, toonId)
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
            assertThat(stored?.sourceId).isEqualTo(HitomiUrls.SOURCE_ID)
        } finally {
            db.close()
        }
    }

    private fun hitomiClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val (code, body) = when {
                    url.endsWith("/galleries/1001.js") -> 200 to ONE_FILE_GALLERY
                    url.endsWith("/gg.js") -> 200 to GG_JS
                    url == WEBP -> 404 to "missing"
                    url == AVIF -> 200 to "avif-bytes"
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

    companion object {
        private const val HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc"
        private const val WEBP =
            "https://w2.gold-usergeneratedcontent.net/1690000000/3243/$HASH.webp"
        private const val AVIF =
            "https://a2.gold-usergeneratedcontent.net/1690000000/3243/$HASH.avif"
        private const val GG_JS = """
var gg = {
m: function(g) {
switch (g) {
case 3243:
return 1;
case 291:
return 1;
}
return 0;
},
b: "1690000000/",
s: function(h) {
var m = /(..)(.)$/.exec(h);
return parseInt(m[2]+m[1], 16).toString(10);
}
};
"""
        private const val ONE_FILE_GALLERY = """
var galleryinfo = {
  "id": 1001,
  "title": "Single Artist Gallery",
  "type": "doujinshi",
  "language": "korean",
  "date": "2024-08-15 12:34:56",
  "artists": [{"artist": "Demo Artist"}],
  "files": [
    {
      "hash": "$HASH",
      "name": "01.jpg",
      "haswebp": 1,
      "hasavif": 1
    }
  ]
};
"""
    }
}
