package com.comics8.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI

class DesktopOpenFileEventsTest {
    @Before
    fun setUp() {
        DesktopOpenFileEvents.resetForTest()
    }

    @After
    fun tearDown() {
        DesktopOpenFileEvents.resetForTest()
    }

    @Test
    fun queuesFilesUntilReaderIsReady() {
        val file = File("/tmp/pending.cbz")
        DesktopOpenFileEvents.accept(listOf(file))

        val opened = mutableListOf<File>()
        DesktopOpenFileEvents.listen(opened::add).use {
            assertThat(opened).containsExactly(file)
        }
    }

    @Test
    fun forwardsFilesToActiveReader() {
        val opened = mutableListOf<File>()
        DesktopOpenFileEvents.listen(opened::add).use {
            DesktopOpenFileEvents.accept(listOf(File("/tmp/one.zip"), File("/tmp/two.cbz")))
        }

        assertThat(opened.map(File::getName)).containsExactly("one.zip", "two.cbz").inOrder()
    }

    @Test
    fun parsesFileUriAndPercentEncodedPaths() {
        val path = DesktopOpenFileEvents.parsePathOrUri("file:///tmp/hello%20world.zip")
        assertThat(path.path).isEqualTo("/tmp/hello world.zip")
    }

    @Test
    fun parsesSmbUrlToMountedVolumeCandidates() {
        val resolved = DesktopOpenFileEvents.parsePathOrUri("smb://192.168.0.100/ssd/manga/test.zip")
        // If /Volumes/ssd/manga/test.zip exists, it will use that; otherwise fallback to /Volumes/ssd/manga/test.zip
        assertThat(resolved.path).isEqualTo("/Volumes/ssd/manga/test.zip")
    }

    @Test
    fun acceptArgsForwardsValidPathsAndIgnoresFlags() {
        val opened = mutableListOf<File>()
        DesktopOpenFileEvents.listen(opened::add).use {
            DesktopOpenFileEvents.acceptArgs(listOf("-psn_0_123456", "--option", "  /tmp/first.zip  ", "", "file:///tmp/second.cbz"))
        }

        assertThat(opened.map(File::getPath)).containsExactly("/tmp/first.zip", "/tmp/second.cbz")
    }

    @Test
    fun parsesFileUriWithLocalhost() {
        val path = DesktopOpenFileEvents.parsePathOrUri("file://localhost/tmp/hello.zip")
        assertThat(path.path).isEqualTo("/tmp/hello.zip")
    }

    @Test
    fun acceptUriForwardsResolvedFile() {
        val opened = mutableListOf<File>()
        DesktopOpenFileEvents.listen(opened::add).use {
            DesktopOpenFileEvents.acceptUri(URI.create("file:///tmp/from-uri.zip"))
        }

        assertThat(opened.map(File::getPath)).containsExactly("/tmp/from-uri.zip")
    }

    @Test
    fun triggersBringToFrontOnAccept() {
        var broughtToFront = false
        DesktopOpenFileEvents.onBringToFront = { broughtToFront = true }
        try {
            DesktopOpenFileEvents.accept(listOf(File("/tmp/front-test.zip")))
            assertThat(broughtToFront).isTrue()
        } finally {
            DesktopOpenFileEvents.onBringToFront = null
        }
    }
}
