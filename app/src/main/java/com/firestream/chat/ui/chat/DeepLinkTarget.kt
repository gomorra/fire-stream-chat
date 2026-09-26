package com.firestream.chat.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import com.firestream.chat.domain.model.MessageAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class DeepLinkTargetOutcome {
    /** The message is in the list — jump to it. */
    FOUND,

    /** The backend confirmed it is gone — say so. */
    GONE,

    /** Never arrived, but nothing says it is gone — stay quiet. */
    NOT_ARRIVED,
}

/**
 * Waits for a deep-linked message (notification / reminder tap) to reach the
 * chat's list, and decides what to tell the user when it doesn't.
 *
 * Not seeing the message after [quickWaitMs] is no proof it is gone: after a
 * cold start from a notification the listener still has to initialise Signal,
 * take its first snapshot and decrypt, which can outlast any fixed wait. So a
 * miss asks the backend instead of concluding, and only a [MessageAvailability.GONE]
 * answer surfaces "no longer available". Anything else keeps waiting up to
 * [syncWaitMs] and then gives up silently.
 *
 * [isLoaded] must emit the current state on collection and then every change.
 */
internal suspend fun awaitDeepLinkTarget(
    isLoaded: Flow<Boolean>,
    checkAvailability: suspend () -> MessageAvailability,
    quickWaitMs: Long = 3_000L,
    syncWaitMs: Long = 15_000L,
): DeepLinkTargetOutcome {
    suspend fun arrivesWithin(ms: Long) = withTimeoutOrNull(ms) { isLoaded.first { it } } != null

    if (arrivesWithin(quickWaitMs)) return DeepLinkTargetOutcome.FOUND
    if (checkAvailability() == MessageAvailability.GONE) {
        // The backend read can take seconds; the listener may have landed it meanwhile.
        return if (isLoaded.first()) DeepLinkTargetOutcome.FOUND else DeepLinkTargetOutcome.GONE
    }
    return if (arrivesWithin(syncWaitMs)) DeepLinkTargetOutcome.FOUND else DeepLinkTargetOutcome.NOT_ARRIVED
}

/**
 * How far up from the newest message, as a fraction of the viewport height, the
 * user may have scrolled and still be taken to a deep-link target. Tighter than
 * the scroll-to-bottom FAB's 20%: a target can land seconds after the chat
 * opened, and by then any real scroll means the user is reading something else.
 */
internal const val DEEP_LINK_JUMP_THRESHOLD = 0.1f

/**
 * Whether the deep-link jump may still move the list. A targeted open always
 * lands on the newest message, so "scrolled up from the bottom" is "moved away
 * from where the chat opened" — and a jump then would yank the user out of
 * what they chose to read. Skipped silently: the message is there, the user is
 * just elsewhere.
 */
internal fun shouldJumpToDeepLinkTarget(listState: LazyListState, totalItems: Int): Boolean =
    !isScrolledUpPastThreshold(listState, totalItems, thresholdFraction = DEEP_LINK_JUMP_THRESHOLD)
