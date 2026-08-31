package com.comics8.core.source.local

import com.comics8.core.source.FileRevision
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.io.path.createTempDirectory

class LibraryScanIndexTest {
    @Test
    fun persistsAndInvalidatesIndexedWorksBySignature() {
        val file = createTempDirectory("library-index").resolve("index.json").toFile()
        val index = LibraryScanIndex(file)
        val works = listOf(
            IndexedLibraryWork(
                id = "zip:/book.cbz",
                title = "Book",
                path = "/book.cbz",
                kind = "ZIP",
                episodes = listOf(
                    IndexedLibraryEpisode("/book.cbz", "Book", true, FileRevision(123L, 456L, "book-v1")),
                ),
            ),
        )

        index.save("same", works)
        assertThat(index.load("same")).containsExactlyElementsIn(works)
        assertThat(index.load("changed")).isNull()

        index.clear()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun signatureIsOrderIndependent() {
        assertThat(LibraryScanIndex.signature(listOf("b", "a")))
            .isEqualTo(LibraryScanIndex.signature(listOf("a", "b")))
    }
}
