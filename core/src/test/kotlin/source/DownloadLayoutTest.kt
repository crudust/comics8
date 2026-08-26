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

    @Test
    fun migrateMovesLegacyToonTreeUnderEleven() {
        val base = createTempDirectory("dl-mig").toFile()
        base.deleteOnExit()
        val legacy = DownloadLayout.legacyEpisodeDir(base, "123", "999")
        legacy.mkdirs()
        File(legacy, "0001.jpg").writeText("img")

        val results = DownloadLayout.migrateLegacyElevenDirs(base)
        assertThat(results).hasSize(1)
        assertThat(results[0].moved).isTrue()

        val neu = DownloadLayout.episodeDir(base, WorkId.eleven("123"), "999")
        assertThat(File(neu, "0001.jpg").readText()).isEqualTo("img")
        assertThat(legacy.exists()).isFalse()
        assertThat(DownloadLayout.legacyToonDir(base, "123").exists()).isFalse()
    }

    @Test
    fun migrateLeavesExistingElevenDirAndKeepsLegacyReadable() {
        val base = createTempDirectory("dl-mig-skip").toFile()
        base.deleteOnExit()
        val workId = WorkId.eleven("123")
        val neu = DownloadLayout.episodeDir(base, workId, "999")
        neu.mkdirs()
        File(neu, "0001.jpg").writeText("new")
        val legacy = DownloadLayout.legacyEpisodeDir(base, "123", "999")
        legacy.mkdirs()
        File(legacy, "0001.jpg").writeText("old")

        val results = DownloadLayout.migrateLegacyElevenDirs(base)
        assertThat(results.single().moved).isFalse()
        assertThat(File(neu, "0001.jpg").readText()).isEqualTo("new")
        assertThat(File(legacy, "0001.jpg").readText()).isEqualTo("old")
    }

    @Test
    fun migrateDoesNotTouchLocalTree() {
        val base = createTempDirectory("dl-local").toFile()
        base.deleteOnExit()
        val local = DownloadLayout.episodeDir(base, WorkId.local("folder"), "1")
        local.mkdirs()
        File(local, "0001.jpg").writeText("l")

        DownloadLayout.migrateLegacyElevenDirs(base)
        assertThat(File(local, "0001.jpg").readText()).isEqualTo("l")
    }

    @Test
    fun migrateDoesNotTouchHitomiTree() {
        val base = createTempDirectory("dl-hitomi").toFile()
        base.deleteOnExit()
        val hitomi = DownloadLayout.episodeDir(base, WorkId("hitomi", "abc"), "1")
        hitomi.mkdirs()
        File(hitomi, "0001.jpg").writeText("h")

        DownloadLayout.migrateLegacyElevenDirs(base)
        assertThat(File(hitomi, "0001.jpg").readText()).isEqualTo("h")
    }

    @Test
    fun incompleteDestDoesNotWinOverCompleteLegacy() {
        val base = createTempDirectory("dl-partial").toFile()
        base.deleteOnExit()
        val workId = WorkId.eleven("123")
        val neu = DownloadLayout.episodeDir(base, workId, "999")
        neu.mkdirs()
        File(neu, "0001.jpg").writeText("partial")
        val legacy = DownloadLayout.legacyEpisodeDir(base, "123", "999")
        legacy.mkdirs()
        File(legacy, "0001.jpg").writeText("1")
        File(legacy, "0002.jpg").writeText("2")

        val results = DownloadLayout.migrateLegacyElevenDirs(base)
        assertThat(results.single().moved).isFalse()
        assertThat(DownloadLayout.resolveEpisodeDir(base, workId, "999")).isEqualTo(legacy)
        assertThat(File(legacy, "0002.jpg").readText()).isEqualTo("2")
    }

    @Test
    fun failedCopyDeletesIncompleteDestAndKeepsSource() {
        val root = createTempDirectory("dl-copy-fail").toFile()
        root.deleteOnExit()
        val from = File(root, "from").apply { mkdirs() }
        File(from, "0001.jpg").writeText("1")
        File(from, "0002.jpg").writeText("2")
        val to = File(root, "to")
        to.writeText("blocker")

        assertThat(DownloadLayout.moveDir(from, to)).isFalse()
        assertThat(File(from, "0001.jpg").exists()).isTrue()
        assertThat(File(from, "0002.jpg").exists()).isTrue()
        assertThat(to.exists()).isFalse()
    }
}
