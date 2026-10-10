package com.firestream.chat.domain.util

/**
 * What the message sync asks the backend for one chat: everything, or only the
 * tail after a point in time.
 */
object MessageSyncPlan {

    /**
     * The generation a restored chat's state row carries. Raising it makes every
     * phone fetch each chat whole once more.
     */
    const val RESTORE_GENERATION = 1

    /**
     * How far behind the cursor a tail fetch starts. A message carries its
     * sender's clock, and a queued one lands later than that clock says, so a
     * fetch from the cursor itself would pass it.
     */
    const val TAIL_OVERLAP_MS = 3L * 24 * 60 * 60 * 1000

    sealed interface Fetch {
        /** The whole chat: it was never restored on this install, or under an older generation. */
        data object Everything : Fetch

        /** The messages with a `timestamp` above [timestamp]. */
        data class After(val timestamp: Long) : Fetch
    }

    /**
     * The fetch for a chat whose state row holds [restoreGeneration] and
     * [cursorMs]. Both are `null` for a chat without a row.
     */
    fun fetchFor(restoreGeneration: Int?, cursorMs: Long?): Fetch =
        if (restoreGeneration != RESTORE_GENERATION || cursorMs == null) {
            Fetch.Everything
        } else {
            Fetch.After((cursorMs - TAIL_OVERLAP_MS).coerceAtLeast(0L))
        }

    /**
     * The cursor a fetch earns: the highest of the fetched [timestamps], or
     * `null` when it fetched none. A message's `timestamp` is its sender's
     * clock, so the cursor stops at [nowMs], this phone's clock. One sender
     * whose clock runs weeks ahead then cannot put the cursor past everything
     * the others write meanwhile.
     */
    fun cursorFrom(timestamps: List<Long>, nowMs: Long): Long? =
        timestamps.maxOrNull()?.coerceAtMost(nowMs)
}
