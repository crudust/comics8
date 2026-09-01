package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderProgressTest {
    @Test
    fun completionRequiresSeeingLastImage() {
        assertThat(ReaderProgress.isCompleted(totalImages = 10, seenThroughPage = 9)).isTrue()
        assertThat(ReaderProgress.isCompleted(totalImages = 10, seenThroughPage = 8)).isFalse()
        assertThat(ReaderProgress.isCompleted(totalImages = 0, seenThroughPage = 0)).isFalse()
    }

    @Test
    fun lastImagePersistsEncodedCompletedPage() {
        assertThat(ReaderProgress.persistPage(page = 9, totalImages = 10)).isEqualTo(-10)
        assertThat(ReaderProgress.persistPage(page = 0, totalImages = 1)).isEqualTo(-1)
        assertThat(ReaderProgress.isCompleted(-10)).isTrue()
        assertThat(ReaderProgress.decodePage(-10)).isEqualTo(9)
        assertThat(ReaderProgress.startPageOnOpen(-10)).isEqualTo(0)
    }

    @Test
    fun lastSpreadPersistsWhenLastImageWasSeen() {
        assertThat(ReaderProgress.persistPage(page = 8, totalImages = 10, seenThroughPage = 9)).isEqualTo(-10)
    }

    @Test
    fun midEpisodeKeepsPage() {
        assertThat(ReaderProgress.persistPage(page = 8, totalImages = 10, seenThroughPage = 8)).isEqualTo(8)
        assertThat(ReaderProgress.persistPage(page = 3, totalImages = 10)).isEqualTo(3)
        assertThat(ReaderProgress.isCompleted(3)).isFalse()
        assertThat(ReaderProgress.decodePage(3)).isEqualTo(3)
        assertThat(ReaderProgress.startPageOnOpen(3)).isEqualTo(3)
    }

    @Test
    fun emptyReaderKeepsNonNegativePage() {
        assertThat(ReaderProgress.persistPage(page = 4, totalImages = 0)).isEqualTo(4)
        assertThat(ReaderProgress.persistPage(page = -1, totalImages = 0)).isEqualTo(0)
    }
}
