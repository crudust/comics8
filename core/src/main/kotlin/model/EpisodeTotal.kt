package com.comics8.core.model

/**
 * Verified shape of an episode listing, cached so that later refreshes need a single page request.
 *
 * Only the two numbers that a listing cannot be derived without are kept: how many pages exist and
 * how many episodes the *last* page holds. Those are exactly the unknowns in
 * `(lastPage - 1) * pageSize + lastPageItemCount`, which is why the total itself does not need to be
 * re-measured page by page on every refresh.
 */
data class EpisodeListingSnapshot(
    val lastPage: Int,
    val lastPageItemCount: Int,
) {
    val isValid: Boolean get() = lastPage >= 1 && lastPageItemCount >= 0
}

/**
 * Result of a full listing sweep.
 *
 * [snapshot] is present only when every page was read successfully. A partial sweep therefore
 * degrades to a lower bound and can never overwrite a verified total.
 */
data class EpisodeTotalObservation(
    val total: EpisodeTotal,
    val snapshot: EpisodeListingSnapshot? = null,
)

/**
 * Observed total episode count of a work.
 *
 * - [Exact] the number is derived from the last page of the listing, either because that page was
 *   read directly or because a [EpisodeListingSnapshot] proves its item count. It may correct a
 *   previous value *downward* because sources delete or rename episodes.
 * - [AtLeast] only some pages were inspected: a lower bound that must never be shown or stored as
 *   if it were the real total.
 */
sealed interface EpisodeTotal {
    val count: Int

    data class Exact(override val count: Int) : EpisodeTotal

    data class AtLeast(override val count: Int) : EpisodeTotal

    val isExact: Boolean get() = this is Exact

    /**
     * Whether more episodes than [lastReadOrder] are known to exist (or cannot be ruled out).
     * A lower bound never proves a work is fully read.
     */
    fun needsRefresh(lastReadOrder: Int): Boolean = !isExact || lastReadOrder < count

    /** Denominator for progress badges: exact totals are bare, lower bounds carry a `+`. */
    fun totalLabel(): String = if (isExact) "$count" else "$count+"

    companion object {
        val Unknown: EpisodeTotal = AtLeast(0)

        /** A cached or synced value that lost its exactness flag can only be trusted as a lower bound. */
        fun fromStored(count: Int, exact: Boolean = false): EpisodeTotal = when {
            count <= 0 -> Unknown
            exact -> Exact(count)
            else -> AtLeast(count)
        }

        /**
         * Reads the total out of one page of an episode listing.
         *
         * The page count is authoritative (the site reports it), so only the last page's item count
         * is unknown. A [snapshot] supplies it and makes this a single-request calculation; without
         * one, an incomplete view stays a lower bound instead of being multiplied up to a page-sized
         * estimate.
         */
        fun from(
            page: EpisodePage,
            pageSize: Int,
            snapshot: EpisodeListingSnapshot? = null,
        ): EpisodeTotal {
            val lastPage = page.lastPage.coerceAtLeast(1)
            val currentPage = page.currentPage.coerceIn(1, lastPage)
            val itemCount = page.items.size
            if (itemCount <= 0) return Unknown
            if (lastPage == 1) return Exact(itemCount)

            val safePageSize = pageSize.coerceAtLeast(1)
            if (currentPage == lastPage) {
                return Exact((lastPage - 1) * safePageSize + itemCount)
            }
            // The snapshot is only trusted when the listing shape still matches: same page count and
            // a full first page (if page 1 is not full while more pages exist, `pageSize` is off).
            if (
                snapshot != null &&
                snapshot.isValid &&
                snapshot.lastPage == lastPage &&
                // A listing reported as multi-page cannot have an empty tail.
                snapshot.lastPageItemCount in 1..safePageSize &&
                itemCount >= safePageSize
            ) {
                // The shape is only meaningful if it can reproduce the observed listing size.
                val derived = (lastPage - 1) * safePageSize + snapshot.lastPageItemCount
                if (derived >= (currentPage - 1) * safePageSize + itemCount) {
                    return Exact(derived)
                }
            }
            return AtLeast((currentPage - 1) * safePageSize + itemCount)
        }

        /**
         * Single merge rule shared by local storage, sync apply and the server:
         * an exact observation always wins, a lower bound may only raise a lower bound.
         */
        fun merge(previous: EpisodeTotal?, observation: EpisodeTotal): EpisodeTotal = when {
            observation is Exact -> observation
            previous is Exact -> previous
            else -> AtLeast(maxOf(previous?.count ?: 0, observation.count))
        }
    }
}
