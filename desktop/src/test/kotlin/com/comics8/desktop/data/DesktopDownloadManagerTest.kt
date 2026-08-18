package com.comics8.desktop.data

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.DownloadLayout
import com.comics8.core.source.WorkId
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
        val db = DesktopDatabase(File(root, "comics8.db"))
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

        val manager = DesktopDownloadManager(db, ToonClient(isProxyEnabled = false), downloads)
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
    fun hitomiWebpFallsBackToAvif() = runBlocking {
        val root = createTempDirectory("desk-hitomi").toFile()
        root.deleteOnExit()
        val downloads = File(root, "downloads").apply { mkdirs() }
        val db = DesktopDatabase(File(root, "comics8.db"))
        val webp = "https://w2.gold-usergeneratedcontent.net/1690000000/c/ab/hash.webp"
        val avif = "https://a2.gold-usergeneratedcontent.net/1690000000/c/ab/hash.avif"
        val client = ToonClient(imageClient(webp, avif), isProxyEnabled = false)
        val manager = DesktopDownloadManager(db, client, downloads)

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
