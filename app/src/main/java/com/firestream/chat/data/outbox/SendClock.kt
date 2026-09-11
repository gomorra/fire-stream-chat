// region: AGENT-NOTE
// Responsibility: The timestamp an own message is composed with — strictly
//   increasing within the process, so two sends never share a millisecond.
// Owns: the last timestamp handed out.
// Collaborators: MessageRepositoryImpl (every send path that stamps a new or
//   merged message), PollRepositoryImpl, CallRepositoryImpl.
// Don't put here: receipt, edit or delete times — they order nothing.
// endregion

package com.firestream.chat.data.outbox

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Hands out send timestamps as `max(now, last + 1)`.
 *
 * Messages are displayed in timestamp order, and the chat preview only ever
 * moves to a newer timestamp (`ChatDao.updateLastMessage`, the Firestore preview
 * transaction). Sends run in parallel and finish in any order, so a batch that
 * shared one millisecond — several photos picked at once — would leave both the
 * order of its bubbles and which of them the preview shows to whichever write
 * finished last. A distinct timestamp per message makes both deterministic, and
 * a wall clock set back mid-conversation cannot sort a new message above the
 * ones before it.
 */
@Singleton
class SendClock internal constructor(private val nowMs: () -> Long) {

    @Inject
    constructor() : this(System::currentTimeMillis)

    private val last = AtomicLong(0L)

    fun next(): Long {
        val now = nowMs()
        return last.updateAndGet { max(now, it + 1) }
    }
}
