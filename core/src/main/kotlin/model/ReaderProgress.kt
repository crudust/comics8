package com.comics8.core.model

object ReaderProgress {
    /**
     * Page to persist when leaving or scrolling the reader.
     * If the last image has been seen, the saved position is cleared (0)
     * so the next open starts at the beginning.
     */
    fun persistPage(page: Int, totalImages: Int, seenThroughPage: Int = page): Int {
        if (totalImages > 0 && seenThroughPage >= totalImages - 1) return 0
        return page.coerceAtLeast(0)
    }
}
