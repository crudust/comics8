package com.comics8.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class DesktopOpenFileEventsTest {
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
}
