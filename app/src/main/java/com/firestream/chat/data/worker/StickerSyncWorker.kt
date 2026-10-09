package com.firestream.chat.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.local.entity.StickerSyncState
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.SendErrorClassifier
import com.firestream.chat.data.remote.source.SendFailure
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.sticker.StickerManifest
import com.firestream.chat.data.sticker.StickerUploads
import com.firestream.chat.data.util.parseStickerFormat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The backup of the sticker library: every pack whose state the backend does
 * not have is brought there.
 *
 * A changed pack ([StickerSyncState.PENDING]) has every sticker's file uploaded
 * first, each at most once ([StickerUploads]). Then its manifest is written, and
 * the pack is marked synced. A pack that changed during the upload is not marked
 * (`StickerDao.markSynced`). A deleted pack ([StickerSyncState.DELETED]) has its
 * manifest deleted, and then its tombstone row dropped.
 *
 * The rows are the state, so a run that is stopped loses nothing. The files it
 * uploaded keep their `remoteUrl`, and the packs it did not reach stay unsynced
 * for the next run. A run repeats until nothing is unsynced, which takes in the
 * packs that changed while it worked.
 *
 * A failure is sorted by [SendErrorClassifier], whose rules for a send fit a
 * backup too, and does not stop the other packs. A transient one ends the run
 * in `Result.retry()`. A permanent one, such as rules that refuse the write,
 * ends it in `Result.failure()`. The pack then stays pending until the
 * scheduler is asked again.
 */
@HiltWorker
class StickerSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val stickerDao: StickerDao,
    private val stickerFiles: StickerFiles,
    private val stickerUploads: StickerUploads,
    private val packSource: StickerPackSource,
    private val authSource: AuthSource,
    private val errorClassifier: SendErrorClassifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Read once. A manifest is written as this user's, whoever is signed in by then,
        // and the rules refuse it for anyone else.
        val ownerId = authSource.currentUserId ?: return Result.success()
        val failed = HashMap<String, SendFailure>()
        repeat(MAX_PASSES) {
            val unsynced = stickerDao.getUnsyncedPacks().filterNot { it.id in failed }
            if (unsynced.isEmpty()) return verdict(failed.values)
            for (pack in unsynced) {
                try {
                    if (pack.syncState == StickerSyncState.DELETED.name) delete(pack) else upload(pack.id, ownerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "pack ${pack.id} not synced", t)
                    failed[pack.id] = errorClassifier.classify(t)
                }
            }
        }
        // Packs kept changing under every pass. Come back for them.
        return Result.retry()
    }

    private fun verdict(failures: Collection<SendFailure>): Result = when {
        failures.isEmpty() -> Result.success()
        SendFailure.TRANSIENT in failures -> Result.retry()
        else -> Result.failure()
    }

    private suspend fun upload(packId: String, ownerId: String) {
        // Read again, as one transaction: the pack and its stickers as they are now.
        val (pack, stickers) = stickerDao.getPackWithStickers(packId) ?: return
        if (pack.syncState != StickerSyncState.PENDING.name) return
        backUpFiles(stickers)
        // The uploads can take minutes. A pack that changed meanwhile, by an edit or by a
        // restore, is not written as it was. The next pass reads it again.
        if (stickerDao.getPackRow(packId)?.updatedAt != pack.updatedAt) return
        packSource.writePack(StickerManifest.of(pack, stickers, ownerId))
        stickerDao.markSynced(pack.id, pack.updatedAt)
    }

    /**
     * Uploads the files the backend may not hold yet. A sticker with a
     * `remoteUrl` is there already. One without a file on this device came from
     * a manifest and has not been fetched yet, so it is there too.
     */
    private suspend fun backUpFiles(stickers: List<StickerEntity>) = withContext(Dispatchers.IO) {
        val permits = Semaphore(UPLOADS_AT_ONCE)
        stickers
            .filter { it.remoteUrl == null && StickerFiles.isValidId(it.id) }
            .filter { stickerFiles.fileFor(it.id, parseStickerFormat(it.format)).isFile }
            .map { sticker -> async { permits.withPermit { stickerUploads.ensureUploaded(sticker.id) } } }
            .awaitAll()
    }

    private suspend fun delete(pack: StickerPackEntity) {
        packSource.deletePack(pack.id)
        stickerDao.purgeTombstone(pack.id)
    }

    companion object {
        private const val TAG = "StickerSyncWorker"

        /** How often a run looks for unsynced packs before it leaves the rest to a later run. */
        const val MAX_PASSES = 5
        private const val UPLOADS_AT_ONCE = 4
    }
}
