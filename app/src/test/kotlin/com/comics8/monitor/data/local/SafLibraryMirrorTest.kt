package com.comics8.monitor.data.local

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.comics8.core.source.local.LibraryScanner
import com.comics8.core.source.local.LocalEpisodeKind
import com.comics8.core.source.local.LocalWorkKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SafLibraryMirrorTest {
    @Test
    fun materializeFollowsSectionCScanRules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val src = File(context.cacheDir, "saf-src-${System.nanoTime()}").apply { mkdirs() }
        writeZip(File(src, "standalone.zip"), "001.jpg" to "a")
        File(src, "skip-me.jpg").writeText("loose")
        File(src, "__MACOSX").mkdirs()
        File(File(src, "__MACOSX"), "leftover.jpg").writeText("x")
        File(File(src, "deep"), "nested").mkdirs()
        writeZip(File(src, "deep/nested/foo.zip"), "p.jpg" to "n")
        val imgdir = File(src, "imgdir").apply { mkdirs() }
        File(imgdir, "1.jpg").writeText("1")
        File(imgdir, "10.jpg").writeText("10")
        File(imgdir, "2.jpg").writeText("2")
        val series = File(src, "series").apply { mkdirs() }
        writeZip(File(series, "vol1.zip"), "a.jpg" to "v")
        File(series, "vol2").mkdirs()
        File(File(series, "vol2"), "page.jpg").writeText("p")
        File(series, "loose.jpg").writeText("ignore")

        val destRoot = File(context.cacheDir, "saf-dest-${System.nanoTime()}").apply { mkdirs() }
        val dest = File(destRoot, "out")
        val mirror = SafLibraryMirror(context, destRoot)
        mirror.materializeDocument(DocumentFile.fromFile(src), dest)

        val works = LibraryScanner().scan(dest)
        assertThat(works.map { it.title }).containsExactly("imgdir", "series", "standalone").inOrder()
        assertThat(works.none { it.path.name == "skip-me.jpg" }).isTrue()
        assertThat(works.none { it.path.name == "__MACOSX" }).isTrue()
        assertThat(works.none { it.path.name == "deep" }).isTrue()
        assertThat(File(dest, "skip-me.jpg").exists()).isFalse()
        assertThat(File(dest, "deep").exists()).isFalse()

        val seriesWork = works.first { it.title == "series" }
        assertThat(seriesWork.kind).isEqualTo(LocalWorkKind.SERIES)
        assertThat(seriesWork.episodes.map { it.title }).containsExactly("vol1", "vol2").inOrder()
        assertThat(seriesWork.episodes.map { it.kind })
            .containsExactly(LocalEpisodeKind.ZIP, LocalEpisodeKind.DIR)
            .inOrder()
        assertThat(File(dest, "series/loose.jpg").exists()).isFalse()
    }

    @Test
    fun destForIsStableForSameUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mirror = SafLibraryMirror(context)
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AComics"
        assertThat(mirror.destFor(uri)).isEqualTo(mirror.destFor(uri))
        assertThat(mirror.destFor(uri).name).matches("[0-9a-f]{64}")
    }

    @Test
    fun fileUriTreeMaterializesWithoutPersistableGrant() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val src = File(context.cacheDir, "saf-file-${System.nanoTime()}").apply { mkdirs() }
        writeZip(File(src, "one.cbz"), "cover.jpg" to "c")
        val destRoot = File(context.cacheDir, "saf-file-dest-${System.nanoTime()}")
        val mirror = SafLibraryMirror(context, destRoot)
        val roots = mirror.materialize(listOf(Uri.fromFile(src).toString()))
        assertThat(roots).hasSize(1)
        assertThat(LibraryScanner().scan(roots.single()).map { it.title }).containsExactly("one")
    }

    private fun writeZip(file: File, vararg entries: Pair<String, String>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
    }
}
