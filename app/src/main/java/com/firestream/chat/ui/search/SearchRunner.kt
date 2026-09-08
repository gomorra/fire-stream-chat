package com.firestream.chat.ui.search

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Only typing is debounced. A chip tap is a discrete action with a settled
// intent behind it, so it re-queries at once — 300 ms of nothing after a tap
// reads as a dropped tap.
private const val TYPING_DEBOUNCE_MS = 300L

/**
 * Issues searches on behalf of one search surface, in-chat ([chatId] set) or
 * global ([chatId] null).
 *
 * Both surfaces have the same two inputs on different timings — typing
 * debounces, a chip tap does not — and the same cancellation contract, whose
 * failure mode is invisible when it is wrong: a superseded job that clears the
 * results the job replacing it is about to write. That contract lives here
 * once rather than in each surface.
 *
 * Holds no state and never touches a UI state object: it hands results to
 * [onResults] and lets the caller decide where they land, which keeps
 * `ChatSearchManager`'s ownership of the overlays slice intact
 * (docs/PATTERNS.md#chat-manager-slice-ownership).
 */
internal class SearchRunner(
    private val scope: CoroutineScope,
    private val searchMessagesUseCase: SearchMessagesUseCase,
    private val chatId: String?,
    private val onResults: (messages: List<Message>, truncated: Boolean) -> Unit,
) {
    private var job: Job? = null

    /**
     * Re-issues the search from [query] and [filter] — the single path both
     * inputs funnel through, so neither can go stale against the other.
     *
     * With nothing selected on either axis the use case would return empty
     * anyway, but short-circuiting keeps the results from flickering through a
     * round trip on the way back to empty.
     */
    fun run(query: String, filter: MessageSearchFilter, debounce: Boolean) {
        job?.cancel()
        if (!isSearchSelecting(query, filter)) {
            onResults(emptyList(), false)
            return
        }
        job = scope.launch {
            if (debounce) delay(TYPING_DEBOUNCE_MS)
            try {
                val results = searchMessagesUseCase(query, chatId, filter)
                onResults(results.messages, results.truncated)
            } catch (e: CancellationException) {
                // A superseded job must not clear the results the job that
                // replaced it is about to write.
                throw e
            } catch (_: Exception) {
                onResults(emptyList(), false)
            }
        }
    }

    /** Drops any in-flight search without writing a result. */
    fun cancel() {
        job?.cancel()
    }
}

/**
 * Whether the user has selected anything on either axis.
 *
 * The browse-mode rule in one place: a blank query with an active filter is a
 * real search ("show me the photos"), a blank query with no filter is not.
 * Below it a search pane shows a hint rather than "nothing found" — an empty
 * screen reporting a failed search, before anything was asked for, reads as a
 * broken search.
 */
internal fun isSearchSelecting(query: String, filter: MessageSearchFilter): Boolean =
    query.isNotBlank() || filter.isActive
