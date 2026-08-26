package com.comics8.core.source.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZipImageNamesTest {
    @Test
    fun skipsMacosxAndDotfiles() {
        assertThat(ZipImageNames.isJunkEntry("__MACOSX/._001.jpg")).isTrue()
        assertThat(ZipImageNames.isJunkEntry("nested/__MACOSX/foo.jpg")).isTrue()
        assertThat(ZipImageNames.isJunkEntry(".DS_Store")).isTrue()
        assertThat(ZipImageNames.isJunkEntry("pages/.hidden.jpg")).isTrue()
        assertThat(ZipImageNames.isJunkEntry("pages/001.jpg")).isFalse()
    }

    @Test
    fun imageEntriesUseKnownExtensions() {
        assertThat(ZipImageNames.isImageEntry("001.JPG")).isTrue()
        assertThat(ZipImageNames.isImageEntry("a.webp")).isTrue()
        assertThat(ZipImageNames.isImageEntry("a.avif")).isTrue()
        assertThat(ZipImageNames.isImageEntry("a.gif")).isTrue()
        assertThat(ZipImageNames.isImageEntry("a.png")).isTrue()
        assertThat(ZipImageNames.isImageEntry("a.jpeg")).isTrue()
        assertThat(ZipImageNames.isImageEntry("a.txt")).isFalse()
        assertThat(ZipImageNames.isImageEntry("pages/")).isFalse()
        assertThat(ZipImageNames.isImageEntry("__MACOSX/._001.jpg")).isFalse()
    }

    @Test
    fun zipNamesAcceptZipAndCbz() {
        assertThat(ZipImageNames.isZipName("foo.CBZ")).isTrue()
        assertThat(ZipImageNames.isZipName("foo.zip")).isTrue()
        assertThat(ZipImageNames.isZipName("foo.rar")).isFalse()
        assertThat(ZipImageNames.isZipName(".secret.zip")).isFalse()
    }
}
