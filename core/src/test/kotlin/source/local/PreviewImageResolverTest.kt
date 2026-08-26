package com.comics8.core.source.local

import com.comics8.core.source.network.NetworkImageUri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class PreviewImageResolverTest {
    @Test
    fun resolvesLocalPreviewUri() {
        val file = File("/dummy/test.zip")
        val uri = LocalPreviewUri.encode(file, LocalPreviewUri.Kind.ZIP, 320)
        val spec = PreviewImageResolver.resolve(uri)

        assertThat(spec).isNotNull()
        assertThat(spec!!.thumbnailPx).isEqualTo(320)
        assertThat(spec.key.path).isEqualTo("local|${file.absolutePath}|ZIP")
    }

    @Test
    fun resolvesNetworkImagePreviewUri() {
        val uri = NetworkImageUri.encode(
            sourceId = "network-smb-1",
            path = "folder/manga.zip",
            size = 1000L,
            preview = NetworkImageUri.PreviewKind.ZIP_FIRST,
            modifiedAt = 12345L,
            thumbnailPx = 192,
        )
        val spec = PreviewImageResolver.resolve(uri)

        assertThat(spec).isNotNull()
        assertThat(spec!!.thumbnailPx).isEqualTo(192)
        assertThat(spec.key.path).isEqualTo("network-smb-1|folder/manga.zip|ZIP_FIRST")
        assertThat(spec.key.mtimeEpochMs).isEqualTo(12345L)
        assertThat(spec.key.sizeBytes).isEqualTo(1000L)
    }

    @Test
    fun returnsNullForNonPreviewUri() {
        assertThat(PreviewImageResolver.resolve("")).isNull()
        assertThat(PreviewImageResolver.resolve("https://example.com/cover.jpg")).isNull()
        assertThat(PreviewImageResolver.resolve("comics8-local:abc")).isNull()
    }
}
