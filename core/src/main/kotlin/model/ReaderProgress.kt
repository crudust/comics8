package com.comics8.core.model

object ReaderProgress {
    fun isCompleted(totalImages: Int, seenThroughPage: Int): Boolean =
        totalImages > 0 && seenThroughPage >= totalImages - 1

    fun isCompleted(persistedPage: Int): Boolean =
        persistedPage < 0

    fun encodePage(page: Int, completed: Boolean): Int {
        val nonNegative = page.coerceAtLeast(0)
        return if (completed) -(nonNegative + 1) else nonNegative
    }

    fun decodePage(persistedPage: Int): Int =
        if (persistedPage < 0) -persistedPage - 1 else persistedPage.coerceAtLeast(0)

    fun startPageOnOpen(persistedPage: Int): Int =
        if (isCompleted(persistedPage)) 0 else decodePage(persistedPage)

    /**
     * Page to persist when leaving or scrolling the reader.
     * Preserves the exact last seen page index while marking completion status via encoded sign.
     */
    fun persistPage(page: Int, totalImages: Int, seenThroughPage: Int = page): Int {
        val completed = isCompleted(totalImages, seenThroughPage)
        val targetPage = if (completed && totalImages > 0) {
            (totalImages - 1).coerceAtLeast(page)
        } else {
            page
        }
        return encodePage(targetPage, completed)
    }
}

