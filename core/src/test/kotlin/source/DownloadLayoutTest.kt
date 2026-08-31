package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DownloadLayoutTest {
    @Test
    fun resolvesPopulatedEpisodeDir() {
        val base = createTempDirectory("dl-layout").toFile()
        base.deleteOnExit()
        val workId = WorkId.eleven("123")
        val dir = DownloadLayout.episodeDir(base, workId, "999")
        dir.mkdirs()
        File(dir, "0001.jpg").writeText("img")

        val resolved = DownloadLayout.resolveEpisodeDir(base, workId, "999")
        assertThat(resolved).isEqualTo(dir)
    }

    @Test
    fun returnsNullWhenDirIsEmpty() {
        val base = createTempDirectory("dl-empty").toFile()
        base.deleteOnExit()
        val workId = WorkId.eleven("123")
        DownloadLayout.episodeDir(base, workId, "999").mkdirs()

        val resolved = DownloadLayout.resolveEpisodeDir(base, workId, "999")
        assertThat(resolved).isNull()
    }

    @Test
    fun resolvesCustomStoredPathIfPopulated() {
        val base = createTempDirectory("dl-custom").toFile()
        base.deleteOnExit()
        val custom = File(base, "custom/dir").apply { mkdirs() }
        File(custom, "0001.jpg").writeText("img")

        val workId = WorkId.eleven("123")
        val resolved = DownloadLayout.resolveEpisodeDir(base, workId, "999", custom.absolutePath)
        assertThat(resolved).isEqualTo(custom)
    }
}

