package com.comics8.core.model

/**
 * Total episode count of a work, which only exists once the whole episode listing has been counted
 * page by page.
 *
 * There is deliberately no "estimated" or "at least" variant: a number derived from
 * `pages * pageSize` is a guess about pages nobody read, and once it reached storage it was
 * displayed as fact (a 199-episode work showed 199/200) and could never be corrected because every
 * write was monotonic. A work whose listing has not been counted simply has no total, and no
 * progress badge is rendered until it does.
 *
 * [lastPage] and [pageSize] record the listing shape the count was taken from and act as the cache
 * validity token: if either changes, this row no longer describes the listing and it is recounted.
 *
 * [delta] is the correction learned by the sweep. The naive page formula
 * `(lastPage - 1) * pageSize + itemsOnTailPage` assumes every page but the last is full, so it
 * overcounts as soon as a page in the middle is short. `delta` absorbs those gaps at once:
 *
 *   delta = count - ((lastPage - 1) * pageSize + itemsOnTailAtCount)
 *   total = (lastPage - 1) * pageSize + itemsOnTailNow + delta
 *
 * The tail count stays live, so a newest episode being added or removed moves the total even when
 * the page count does not change; only a changed page count or page size forces a recount.
 */
data class EpisodeListingTotal(
    val count: Int,
    val lastPage: Int,
    val pageSize: Int,
    /** Zero when every page but the last is full; negative when a page in the middle is short. */
    val delta: Int = 0,
) {
    val isValid: Boolean get() = count > 0 && lastPage >= 1 && pageSize >= 1

    /**
     * Whether this count still describes the listing currently served. A changed page count means
     * episodes were added or removed somewhere; a changed page size invalidates the arithmetic the
     * count was validated against.
     */
    fun describes(lastPage: Int, pageSize: Int): Boolean =
        isValid && this.lastPage == lastPage.coerceAtLeast(1) && this.pageSize == pageSize.coerceAtLeast(1)

    /** Unread episodes exist only when the count is known and exceeds what was read. */
    fun needsRefresh(lastReadOrder: Int): Boolean = isValid && lastReadOrder < count

    companion object {
        /** A stored row is only trusted when it carries the shape and correction it was counted with. */
        fun fromStored(count: Int, lastPage: Int, pageSize: Int, delta: Int): EpisodeListingTotal? =
            EpisodeListingTotal(count, lastPage, pageSize, delta).takeIf { it.isValid }
    }
}
