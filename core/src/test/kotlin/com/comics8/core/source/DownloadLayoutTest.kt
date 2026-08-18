package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DownloadLayoutTest {
    @Test
    fun emptyNewDirFallsThroughToLegacyImages() {
        val base = createTempDirectory("dl-layout").toFile()
        base.deleteOnExit()
        val workId = WorkId.eleven("123")
        DownloadLayout.episodeDir(base, workId, "999").mkdirs()
        val legacy = DownloadLayout.legacyEpisodeDir(base, "123", "999")
        legacy.mkdirs()
        File(legacy, "0001.jpg").writeText("img")

        val resolved = DownloadLayout.resolveEpisodeDir(base, workId, "999")
        assertThat(resolved).isEqualTo(legacy)
    }

    @Test
    fun populatedNewDirWinsOverLegacy() {
        val base = createTempDirectory("dl-layout").toFile()
        base.deleteOnExit()
        val workId = WorkId.eleven("123")
        val neu = DownloadLayout.episodeDir(base, workId, "999")
        neu.mkdirs()
        File(neu, "0001.jpg").writeText("new")
        val legacy = DownloadLayout.legacyEpisodeDir(base, "123", "999")
        legacy.mkdirs()
        File(legacy, "0001.jpg").writeText("old")

        val resolved = DownloadLayout.resolveEpisodeDir(base, workId, "999")
        assertThat(resolved).isEqualTo(neu)
    }
}
