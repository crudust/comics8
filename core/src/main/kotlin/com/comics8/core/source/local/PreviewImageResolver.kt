package com.comics8.core.source.local

import com.comics8.core.source.network.NetworkImageUri
import com.comics8.core.source.network.NetworkSourceRuntime
import java.io.InputStream

data class PreviewImageSpec(
    val key: ThumbKey,
    val thumbnailPx: Int,
    val openSource: () -> InputStream,
) {
    fun readSourceBytes(): ByteArray = openSource().use { it.readBytes() }
}

object PreviewImageResolver {
    fun resolve(url: String): PreviewImageSpec? {
        if (url.isBlank()) return null
        val localRef = LocalPreviewUri.parse(url)
        val networkRef = NetworkImageUri.parse(url)
        if (localRef == null && networkRef?.preview == null) return null

        val thumbnailPx = localRef?.thumbnailPx
            ?: networkRef!!.thumbnailPx.takeIf { it > 0 }
            ?: 320

        val key = if (localRef != null) {
            ThumbKey("local|${localRef.path}|${localRef.kind}", localRef.modifiedAt, localRef.size)
        } else {
            ThumbKey(
                "${networkRef!!.sourceId}|${networkRef.path}|${networkRef.preview}",
                networkRef.modifiedAt,
                networkRef.size,
            )
        }

        val openSource: () -> InputStream = {
            if (localRef != null) {
                LocalPreviewUri.open(localRef)
            } else {
                NetworkSourceRuntime.open(url)
            }
        }

        return PreviewImageSpec(key, thumbnailPx, openSource)
    }
}
