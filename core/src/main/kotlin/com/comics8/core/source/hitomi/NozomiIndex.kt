package com.comics8.core.source.hitomi

object NozomiIndex {
    const val PAGE = HitomiUrls.PAGE_SIZE
    const val BYTES = HitomiUrls.ID_BYTES

    fun parseIds(body: ByteArray): List<Long> {
        val count = body.size / BYTES
        if (count <= 0) return emptyList()
        val ids = ArrayList<Long>(count)
        var i = 0
        while (i + BYTES <= body.size) {
            val id = ((body[i].toLong() and 0xFF) shl 24) or
                ((body[i + 1].toLong() and 0xFF) shl 16) or
                ((body[i + 2].toLong() and 0xFF) shl 8) or
                (body[i + 3].toLong() and 0xFF)
            ids += id
            i += BYTES
        }
        return ids
    }

    fun lastPage(page: Int, ids: List<*>, totalLength: Long?): Int {
        val current = page.coerceAtLeast(1)
        if (totalLength != null && totalLength > 0) {
            val idCount = totalLength / BYTES
            val pages = ((idCount + PAGE - 1) / PAGE).toInt()
            return pages.coerceAtLeast(1)
        }
        return if (ids.size < PAGE) current else current + 1
    }
}
