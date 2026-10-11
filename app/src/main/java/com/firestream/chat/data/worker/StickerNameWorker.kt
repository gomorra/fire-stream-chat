package com.firestream.chat.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.firestream.chat.data.remote.source.SendErrorClassifier
import com.firestream.chat.data.remote.source.SendFailure
import com.firestream.chat.data.sticker.StickerNameRun
import com.firestream.chat.data.sticker.StickerNameSync
import com.firestream.chat.data.util.rethrowIfCancellation
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One run of automatic sticker names: [StickerNameSync.run] asks for the
 * stickers that may be named and stores the answers that are there.
 *
 * The rows are the state, so a run that is stopped loses nothing.
 *
 * - Requests that have no answer yet end the run in `Result.retry()`. The
 *   naming service is a computer that may be busy or switched off, and
 *   WorkManager's backoff comes back for the answers, later each time.
 * - A failure that may pass, such as the network, ends it in a retry too.
 *   [SendErrorClassifier]'s rules for a send fit here.
 * - A refusal by the backend ends it in `Result.failure()`. Its rules are not
 *   deployed, and a retry would be refused again. The next reason to run
 *   queues a new run (`StickerNameScheduler`).
 */
@HiltWorker
class StickerNameWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val nameSync: StickerNameSync,
    private val errorClassifier: SendErrorClassifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val run = try {
            nameSync.run()
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Log.w(TAG, "sticker naming stopped", e)
            StickerNameRun(failures = listOf(e))
        }
        return when {
            run.refused -> Result.failure()
            run.waiting > 0 -> Result.retry()
            run.failures.isEmpty() -> Result.success()
            run.failures.any { errorClassifier.classify(it) == SendFailure.TRANSIENT } -> Result.retry()
            else -> Result.failure()
        }
    }

    private companion object {
        const val TAG = "StickerNameWorker"
    }
}
