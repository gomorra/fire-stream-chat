// region: AGENT-NOTE
// Responsibility: The WorkManager side of the sticker library's backup — one
//   unique StickerSyncWorker run whenever a pack differs from the backend.
// Owns: the unique-work name (sticker-sync), the KEEP policy, the connected
//   constraint and the backoff.
// Collaborators: StickerRepositoryImpl (after every pack change),
//   StickerLibrarySync (when a restore starts and after each merge, and the
//   cancel on sign-out), FireStreamApp (on start), StickerSyncWorker (what the
//   run does), StickerDao, StickerPackSource.
// Don't put here: what a run uploads (StickerSyncWorker), the restore
//   (StickerLibrarySync).
// endregion

package com.firestream.chat.data.worker

import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.util.rethrowIfCancellation
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Queues the [StickerSyncWorker] run that uploads changed packs and deletes
 * the backend's copy of deleted ones.
 *
 * One unique work, KEEP: a burst of changes queues a single run, and the run
 * itself keeps going until no pack is left unsynced. A change that lands in the
 * moment a run ends keeps its pack pending, and the next call here picks it up:
 * the next change, the next restore or the next app start.
 */
@Singleton
class StickerSyncScheduler @Inject constructor(
    private val workManager: Provider<WorkManager>,
    private val stickerDao: StickerDao,
    private val packSource: StickerPackSource,
) {

    /**
     * Queues a run when a pack is unsynced. Does nothing on a backend that keeps no packs.
     *
     * It does not throw. The change it is called for is in Room either way, so a
     * run that cannot be queued is not the caller's failure. The pack stays
     * pending, and the next call queues it.
     */
    suspend fun syncIfPending() {
        try {
            if (!packSource.isSupported || stickerDao.countUnsyncedPacks() == 0) return
            val request = OneTimeWorkRequestBuilder<StickerSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            workManager.get().enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Log.w(TAG, "sticker sync not queued", e)
        }
    }

    /** Stops a queued or running sync. The library it would upload is being cleared. */
    fun cancel() {
        workManager.get().cancelUniqueWork(WORK_NAME)
    }

    companion object {
        private const val TAG = "StickerSyncScheduler"
        const val WORK_NAME = "sticker-sync"
        const val INITIAL_BACKOFF_SECONDS = 30L
    }
}
