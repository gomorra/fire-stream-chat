// region: AGENT-NOTE
// Responsibility: The per-peer block-list read in front of a send, cached for
//   30 s and shared by the repository and the worker.
// Owns: the pair cache and its TTL. Does not fail open: a fetch error propagates.
// Collaborators: MessageRepositoryImpl (blockVerdict — refuse or queue),
//   OutboxWorker (the authoritative re-check), UserSource.
// Don't put here: the block *list* cache that filters received messages (it fails
//   open and lives in MessageRepositoryImpl), or the decision what an unanswerable
//   check means. Cites "Sends are idempotent by client id and drained by
//   OutboxWorker" (docs/PATTERNS.md).
// endregion

package com.firestream.chat.data.outbox

import com.firestream.chat.data.remote.source.UserSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a sender has blocked a 1:1 peer, read from the backend and cached for
 * [TTL_MS]. Blocking is a human-speed action, so a few seconds of staleness cost
 * nothing, while the read sat in front of every send — and, now that
 * `OutboxWorker` asks again before each attempt, would otherwise run twice per
 * message, the second time milliseconds after the first.
 *
 * Does *not* fail open: a fetch error propagates, and the caller decides whether
 * that refuses the send (a timer, written directly) or queues it for the worker
 * to ask again once it is online (`MessageRepositoryImpl`).
 */
@Singleton
class BlockCheck @Inject constructor(private val userSource: UserSource) {

    private val mutex = Mutex()
    private val cache = HashMap<String, Pair<Boolean, Long>>()

    suspend fun isBlocked(senderId: String, peerId: String): Boolean {
        val key = "$senderId|$peerId"
        mutex.withLock {
            cache[key]?.takeIf { System.currentTimeMillis() - it.second < TTL_MS }?.let { return it.first }
            return userSource.isUserBlocked(senderId, peerId).also {
                cache[key] = it to System.currentTimeMillis()
            }
        }
    }

    companion object {
        const val TTL_MS = 30_000L
    }
}
