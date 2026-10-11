// region: AGENT-NOTE
// Responsibility: The WorkManager side of automatic sticker names — one unique
//   StickerNameWorker run whenever there may be a sticker to ask for or an
//   answer to fetch.
// Owns: the unique-work name (sticker-names), the REPLACE policy, the connected
//   constraint and the backoff that brings a run back while requests wait.
// Collaborators: StickerRepositoryImpl (the switch goes on, a pack or a sticker
//   is opened, the manager opens), StickerSyncWorker (a backup uploaded files),
//   StickerLibrarySync (the cancel on sign-out), StickerNameWorker and
//   StickerNameSync (what a run does), PreferencesDataStore (the switch).
// Don't put here: who is asked for (StickerNameSync).
// endregion

package com.firestream.chat.data.worker

import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.source.StickerNameSource
import com.firestream.chat.data.util.rethrowIfCancellation
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Queues the [StickerNameWorker] run that asks the naming service about
 * stickers and fetches its answers.
 *
 * One unique work, REPLACE. A run whose requests are not answered yet ends in a
 * retry, and WorkManager's backoff brings it back later each time. A new reason
 * to run must not wait for that backoff, so it replaces the waiting run and the
 * backoff starts over. A run that is replaced while it works loses nothing: the
 * rows say what was asked for.
 */
@Singleton
class StickerNameScheduler @Inject constructor(
    private val workManager: Provider<WorkManager>,
    private val nameSource: StickerNameSource,
    private val preferences: PreferencesDataStore,
) {

    /**
     * Queues a run, unless the person has naming switched off or the backend
     * has no naming service. The run itself checks both again.
     *
     * It does not throw. A run that cannot be queued is not the caller's
     * failure, and the next call queues it.
     */
    suspend fun runIfWanted() {
        try {
            if (!nameSource.isSupported || !preferences.autoNameStickers()) return
            val request = OneTimeWorkRequestBuilder<StickerNameWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            workManager.get().enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Log.w(TAG, "sticker naming not queued", e)
        }
    }

    /** Stops a queued or running run. Naming was switched off, or the library is being cleared. */
    fun cancel() {
        workManager.get().cancelUniqueWork(WORK_NAME)
    }

    companion object {
        private const val TAG = "StickerNameScheduler"
        const val WORK_NAME = "sticker-names"

        /** The first wait for an answer. It doubles with every run that still waits, up to WorkManager's five hours. */
        const val INITIAL_BACKOFF_SECONDS = 60L
    }
}
