// region: AGENT-NOTE
// Responsibility: Automatic sticker names — asking the naming service about
//   the library's stickers, and bringing its answers into Room.
// Owns: who is asked for. A sticker is looked up only while the person's
//   switch is on, the sticker allows it, every pack that holds it allows it,
//   some pack holds it, it is a WebP, its file is on the backend and it has no
//   automatic name. It is asked for only when this device has not looked
//   within the last day. Also owns the order "look first, ask only when the
//   backend has no record", and what a record that is no usable answer costs:
//   one read per run, and never a second request.
// Collaborators: StickerDao (getNameCandidates holds everything of the rule
//   that a row says, and this class only the day between two requests;
//   setAutoName writes the `auto…` columns and nothing else),
//   StickerNameSource (one record per sticker id, read one at a time),
//   StickerNameAnswer (the check of an answer), PreferencesDataStore (the
//   switch, and whether the backend refused), StickerNameWorker (runs this),
//   StickerNameScheduler (queues the run).
// Don't put here: the words of a person (StickerDao.setStickerWords), the
//   three consent writes (StickerRepositoryImpl), anything that lists the
//   records. `firestore.rules` refuses a list, and a sticker id fetches its file.
// endregion

package com.firestream.chat.data.sticker

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.StickerNameCandidate
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.RemoteStickerName
import com.firestream.chat.data.remote.source.StickerNameSource
import com.firestream.chat.data.remote.source.StickerNamingRefusedException
import com.firestream.chat.data.util.rethrowIfCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one run left behind.
 *
 * [waiting] counts the requests that are on the backend without an answer.
 * [failures] are the looks that failed for a reason of their own, such as the
 * network. [refused] says the backend refused, and the run stopped there.
 */
data class StickerNameRun(
    val waiting: Int = 0,
    val failures: List<Throwable> = emptyList(),
    val refused: Boolean = false,
)

@Singleton
class StickerNameSync @Inject constructor(
    private val stickerDao: StickerDao,
    private val nameSource: StickerNameSource,
    private val preferences: PreferencesDataStore,
    private val authSource: AuthSource,
) {

    /** One run at a time. A run that replaces another may start before the first has stopped. */
    private val runLock = Mutex()

    /**
     * Looks up every sticker that may be named and has no automatic name, in
     * batches of [BATCH_SIZE]. An answer is stored. A sticker without a record
     * is asked for, unless this device looked within the last day.
     *
     * The switch and the rows are read again before every batch. Switching
     * naming off, or closing a pack or a sticker, therefore stops a run within
     * one batch.
     *
     * A refusal by the backend ends the run and is remembered for the manager
     * to show. It switches nothing off.
     */
    suspend fun run(now: Long = System.currentTimeMillis()): StickerNameRun = runLock.withLock {
        if (!nameSource.isSupported) return@withLock StickerNameRun()
        val looked = HashSet<String>()
        val failures = ArrayList<Throwable>()
        var waiting = 0
        var answered = false
        while (true) {
            val batch = nextBatch(looked)
            if (batch.isEmpty()) break
            batch.forEach { looked += it.id }
            val outcomes = coroutineScope { batch.map { sticker -> async { lookUp(sticker, now) } }.awaitAll() }
            if (Outcome.Refused in outcomes) {
                // A sign-out in the middle of a run is refused too, and says nothing about the backend.
                if (authSource.currentUserId != null) preferences.setStickerNamingRefused(true)
                return@withLock StickerNameRun(waiting, failures, refused = true)
            }
            outcomes.forEach { outcome ->
                when (outcome) {
                    Outcome.Waiting -> waiting++
                    is Outcome.Failed -> failures += outcome.cause
                    else -> Unit
                }
            }
            answered = answered || outcomes.any { it !is Outcome.Failed }
            // A backend that answers nothing is not tried for every sticker. The retry starts where this stopped.
            if (outcomes.all { it is Outcome.Failed }) break
        }
        if (answered) preferences.setStickerNamingRefused(false)
        StickerNameRun(waiting, failures)
    }

    /** The next stickers to look up that this run has not looked at, or none when nothing may be asked any more. */
    private suspend fun nextBatch(looked: Set<String>): List<StickerNameCandidate> {
        if (authSource.currentUserId == null || !preferences.autoNameStickers()) return emptyList()
        return stickerDao.getNameCandidates().asSequence()
            // An id becomes a document path.
            .filter { it.id !in looked && StickerFiles.isValidId(it.id) }
            .take(BATCH_SIZE)
            .toList()
    }

    /** Not within a day of the last look. A time in the future is a clock that was set back, and counts as long ago. */
    private fun mayAsk(sticker: StickerNameCandidate, now: Long): Boolean {
        val lookedAt = sticker.nameRequestedAt ?: return true
        return now - lookedAt !in 0 until ASK_AGAIN_AFTER_MS
    }

    private suspend fun lookUp(sticker: StickerNameCandidate, now: Long): Outcome {
        val mayAsk = mayAsk(sticker, now)
        try {
            val record = nameSource.fetch(sticker.id)
            if (record == null) {
                // Asked within the day, and the record is gone. The next day asks again.
                if (!mayAsk) return Outcome.Settled
                nameSource.request(sticker.id, sticker.format)
                stickerDao.setNameRequestedAt(sticker.id, now)
                return Outcome.Waiting
            }
            val found = record.takeIf { it.status == RemoteStickerName.STATUS_DONE }?.let { StickerNameAnswer.of(it.fields) }
            when {
                found != null -> stickerDao.setAutoName(sticker.id, found)
                // No answer this build can use: a request that still waits, a sticker the service skipped, a
                // malformed answer. The rules let no phone replace the record, so nothing is asked. The day
                // starts over, and a later look finds what the service wrote since.
                mayAsk -> stickerDao.setNameRequestedAt(sticker.id, now)
            }
            return if (record.status == RemoteStickerName.STATUS_PENDING) Outcome.Waiting else Outcome.Settled
        } catch (e: StickerNamingRefusedException) {
            return Outcome.Refused
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            return Outcome.Failed(e)
        }
    }

    private sealed interface Outcome {
        /** A request is on the backend and has no answer yet. */
        data object Waiting : Outcome

        /** Nothing to wait for: an answer was stored, or the record cannot be used. */
        data object Settled : Outcome

        data object Refused : Outcome

        class Failed(val cause: Throwable) : Outcome
    }

    internal companion object {
        /** How many stickers are looked up at once, and how often the switch is read again. */
        const val BATCH_SIZE = 10

        /** A sticker without a usable answer is asked for, or looked at, again after this long. */
        const val ASK_AGAIN_AFTER_MS = 24 * 60 * 60 * 1000L
    }
}
