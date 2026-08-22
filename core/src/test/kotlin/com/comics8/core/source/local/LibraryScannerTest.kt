package com.comics8.core.source.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LibraryScannerTest {
    private val scanner = LibraryScanner()

    @Test
    fun zipAtRootIsOneWorkOneEpisode() {
        val root = tempLibrary()
        val zip = File(root, "foo.cbz")
        LocalTestZips.write(zip, listOf("001.jpg" to "a"))
        val works = scanner.scan(root)
        val work = works.single()
        assertThat(work.kind).isEqualTo(LocalWorkKind.ZIP)
        assertThat(work.title).isEqualTo("foo")
        assertThat(work.toonId).isEqualTo(LocalWorkId.zip(zip))
        assertThat(work.episodes).hasSize(1)
        assertThat(work.episodes.single().kind).isEqualTo(LocalEpisodeKind.ZIP)
        assertThat(work.episodes.single().wrId).isEqualTo(zip.canonicalPath)
    }

    @Test
    fun imageFolderIsOneWork() {
        val root = tempLibrary()
        val dir = File(root, "bar").apply { mkdirs() }
        File(dir, "10.jpg").writeText("10")
        File(dir, "2.jpg").writeText("2")
        File(dir, "1.jpg").writeText("1")
        File(dir, ".DS_Store").writeText("junk")
        val work = scanner.scan(root).single()
        assertThat(work.kind).isEqualTo(LocalWorkKind.DIR)
        assertThat(work.title).isEqualTo("bar")
        assertThat(work.toonId).isEqualTo(LocalWorkId.dir(dir))
        assertThat(work.episodes.single().wrId).isEqualTo(dir.canonicalPath)
        assertThat(scanner.listFolderImages(dir).map { it.name })
            .containsExactly("1.jpg", "2.jpg", "10.jpg")
            .inOrder()
    }

    @Test
    fun folderOfZipsAndImageFoldersIsSeriesAndIgnoresLooseImages() {
        val root = fixture("local/library")
        val works = scanner.scan(root)
        assertThat(works.map { it.title }).containsExactly("imgdir", "series", "standalone").inOrder()

        val imgdir = works.first { it.title == "imgdir" }
        assertThat(imgdir.kind).isEqualTo(LocalWorkKind.DIR)
        assertThat(scanner.listFolderImages(imgdir.path).map { it.name })
            .containsExactly("1.jpg", "2.jpg", "10.jpg")
            .inOrder()

        val standalone = works.first { it.title == "standalone" }
        assertThat(standalone.kind).isEqualTo(LocalWorkKind.ZIP)
        assertThat(standalone.episodes).hasSize(1)

        val series = works.first { it.title == "series" }
        assertThat(series.kind).isEqualTo(LocalWorkKind.SERIES)
        assertThat(series.toonId).isEqualTo(LocalWorkId.series(File(root, "series")))
        assertThat(series.episodes.map { it.title }).containsExactly("vol1", "vol2").inOrder()
        assertThat(series.episodes.map { it.kind })
            .containsExactly(LocalEpisodeKind.ZIP, LocalEpisodeKind.DIR)
            .inOrder()
        assertThat(series.episodes.none { it.path.name.startsWith("loose") }).isTrue()
        assertThat(works.none { it.title == "skip-me" }).isTrue()
        assertThat(works.none { it.path.name == "__MACOSX" }).isTrue()
        assertThat(works.none { it.path.name == "deep" }).isTrue()
    }

    @Test
    fun seriesLooseFixtureIgnoresTopLevelImages() {
        val root = fixture("local/series-root")
        val series = File(root, "series-loose")
        val work = scanner.scan(root).single()
        assertThat(work.kind).isEqualTo(LocalWorkKind.SERIES)
        assertThat(work.title).isEqualTo("series-loose")
        assertThat(work.episodes.map { it.title }).containsExactly("ch01", "ch02").inOrder()
        assertThat(work.episodes.map { it.kind })
            .containsExactly(LocalEpisodeKind.ZIP, LocalEpisodeKind.DIR)
            .inOrder()
        assertThat(File(series, "loose.jpg").isFile).isTrue()
        assertThat(work.episodes.map { it.path.name }).doesNotContain("loose.jpg")
    }

    @Test
    fun ignoresRootLooseImagesHiddenAndEmptyFolders() {
        val root = tempLibrary()
        File(root, "oops.jpg").writeText("no")
        File(root, ".hidden.zip").writeText("no")
        File(root, "__MACOSX").mkdirs()
        File(root, "empty").mkdirs()
        assertThat(scanner.scan(root)).isEmpty()
    }

    @Test
    fun doesNotRecursePastSeriesImageFolders() {
        val root = tempLibrary()
        val series = File(root, "nested").apply { mkdirs() }
        val chapter = File(series, "ch1").apply { mkdirs() }
        File(chapter, "001.jpg").writeText("p")
        File(File(chapter, "deeper").apply { mkdirs() }, "002.jpg").writeText("no")
        val work = scanner.scan(root).single()
        assertThat(work.kind).isEqualTo(LocalWorkKind.SERIES)
        assertThat(work.episodes.single().title).isEqualTo("ch1")
        assertThat(scanner.listFolderImages(chapter).map { it.name }).containsExactly("001.jpg")
    }

    private fun tempLibrary(): File {
        val root = createTempDirectory("library-scan").toFile()
        root.deleteOnExit()
        return root
    }

    private fun fixture(path: String): File {
        val url = checkNotNull(javaClass.classLoader?.getResource(path)) { "missing $path" }
        return File(url.toURI())
    }
}
