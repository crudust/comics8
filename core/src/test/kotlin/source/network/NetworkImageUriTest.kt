package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkImageUriTest {
    @Test
    fun roundTripsPreviewRevision() {
        val url = NetworkImageUri.encode(
            sourceId = "network-test",
            path = "series/book.cbz",
            preview = NetworkImageUri.PreviewKind.ZIP_FIRST,
            revision = FileRevision(1_000_000_000L, 1234L, "book-v1"),
            thumbnailPx = 320,
        )

        assertThat(NetworkImageUri.parse(url)).isEqualTo(
            NetworkImageUri.Ref(
                sourceId = "network-test",
                path = "series/book.cbz",
                preview = NetworkImageUri.PreviewKind.ZIP_FIRST,
                revision = FileRevision(1_000_000_000L, 1234L, "book-v1"),
                thumbnailPx = 320,
            ),
        )
    }
}
