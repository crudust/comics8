package com.comics8.desktop.data

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.DownloadLayout
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.core.source.hitomi.HitomiUrls
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DesktopDownloadManagerTest {
    @Test
    fun migrateMovesLegacyDirAndUpdatesLocalDirPath() = runBlocking {
        val root = createTempDirectory("desk-dl").toFile()
        root.deleteOnExit()
        val downloads = File(root, "downloads").apply { mkdirs() }
        val db = DesktopDatabase(File(root, "comics8.db")).apply {
            isSourceEnabled = { true }
            installedIds = { SourceRegistry.forTests().knownIds() }
        }
        val legacy = DownloadLayout.legacyEpisodeDir(downloads, "123", "999")
        legacy.mkdirs()
        File(legacy, "0001.jpg").writeText("img")
        db.saveDownloadedEpisode(
            DownloadedEpisodeRecord(
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

        val manager = DesktopDownloadManager(
            db,
            ToonClient(isProxyEnabled = false, sources = testLocator()),
            baseDir = downloads,
            sources = SourceRegistry.forTests(),
        )
        manager.migrateLegacyDownloads()

        val neu = DownloadLayout.episodeDir(downloads, WorkId.eleven("123"), "999")
        assertThat(File(neu, "0001.jpg").readText()).isEqualTo("img")
        assertThat(legacy.exists()).isFalse()
        val stored = db.getDownloadedEpisode(WorkId.eleven("123"), "999")
        assertThat(stored?.localDirPath).isEqualTo(neu.absolutePath)
        val images = manager.getLocalEpisodeImages(WorkId.eleven("123"), "999")
        assertThat(images).isNotNull()
        assertThat(images!!.single()).endsWith("/0001.jpg")
    }

    @Test
    fun incompleteDestDoesNotRewriteLocalDirPath() = runBlocking {
        val root = createTempDirectory("desk-partial").toFile()
        root.deleteOnExit()
        val downloads = File(root, "downloads").apply { mkdirs() }
        val db = DesktopDatabase(File(root, "comics8.db")).apply {
            isSourceEnabled = { true }
            installedIds = { SourceRegistry.forTests().knownIds() }
        }
        val workId = WorkId.eleven("123")
        val dest = DownloadLayout.episodeDir(downloads, workId, "999")
        dest.mkdirs()
        File(dest, "0001.jpg").writeText("partial")
        val legacy = DownloadLayout.legacyEpisodeDir(downloads, "123", "999")
        legacy.mkdirs()
        File(legacy, "0001.jpg").writeText("1")
        File(legacy, "0002.jpg").writeText("2")
        db.saveDownloadedEpisode(
            DownloadedEpisodeRecord(
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

        val manager = DesktopDownloadManager(
            db,
            ToonClient(isProxyEnabled = false, sources = testLocator()),
            baseDir = downloads,
            sources = SourceRegistry.forTests(),
        )
        manager.migrateLegacyDownloads()

        val stored = db.getDownloadedEpisode(workId, "999")
        assertThat(stored?.localDirPath).isEqualTo(legacy.absolutePath)
        assertThat(File(legacy, "0002.jpg").exists()).isTrue()
        val images = manager.getLocalEpisodeImages(workId, "999")
        assertThat(images).hasSize(2)
    }

    @Test
    fun hitomiResolveImagesRetriesWebpWithAvif() = runBlocking {
        val root = createTempDirectory("desk-hitomi").toFile()
        root.deleteOnExit()
        val downloads = File(root, "downloads").apply { mkdirs() }
        val db = DesktopDatabase(File(root, "comics8.db")).apply {
            isSourceEnabled = { true }
            installedIds = { SourceRegistry.forTests().knownIds() }
        }
        val client = ToonClient(hitomiClient(), isProxyEnabled = false, sources = testLocator())
        val manager = DesktopDownloadManager(
            db,
            client,
            isSourceEnabled = { true },
            baseDir = downloads,
            sources = SourceRegistry.forTests(),
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
            if (img.exists() && img.length() > 0 && !running && db.getDownloadedEpisode(workId, "1001") != null) {
                break
            }
            Thread.sleep(50)
        }
        assertThat(img.exists()).isTrue()
        assertThat(img.readText()).isEqualTo("avif-bytes")
        val stored = db.getDownloadedEpisode(workId, "1001")
        assertThat(stored?.sourceId).isEqualTo(HitomiUrls.SOURCE_ID)
    }

    private fun testLocator(): SourceLocator = SourceLocator { SourceRegistry.forTests() }

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
