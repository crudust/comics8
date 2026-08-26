package com.comics8.core.source.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkImageUriTest {
    @Test
    fun roundTripsLazyPreviewMetadata() {
        val url = NetworkImageUri.encode(
            sourceId = "network-test",
            path = "series/book.cbz",
            size = 1_000_000_000L,
            preview = NetworkImageUri.PreviewKind.ZIP_FIRST,
            modifiedAt = 1234L,
            thumbnailPx = 320,
        )

        assertThat(NetworkImageUri.parse(url)).isEqualTo(
            NetworkImageUri.Ref(
                sourceId = "network-test",
                path = "series/book.cbz",
                size = 1_000_000_000L,
                preview = NetworkImageUri.PreviewKind.ZIP_FIRST,
                modifiedAt = 1234L,
                thumbnailPx = 320,
            ),
        )
    }
}
