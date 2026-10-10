// region: AGENT-NOTE
// Responsibility: The restore of the sticker library — a listener on the
//   signed-in user's own pack manifests, merged into Room newer-only.
// Owns: when the listener runs (while something collects `whileObserved`, which
//   StickerRepositoryImpl.observePacks does); the fence that keeps a restore
//   from writing into a library that is being cleared on sign-out, or that
//   belongs to the next user by now.
// Collaborators: StickerPackSource (the listener), StickerDao.applyRemotePack
//   and removeIfSynced (the merge rules), StickerManifest (what of a manifest is
//   acceptable), StickerSyncScheduler (a merge can leave a pack to upload or a
//   tombstone), AuthSource, AuthRepositoryImpl (signingOut).
// Don't put here: the upload (StickerSyncWorker), sticker files — a restored
//   row's file is fetched when it is first shown (StickerRepositoryImpl.ensureFile).
// endregion

package com.firestream.chat.data.sticker

import android.util.Log
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.StickerPackChanges
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.worker.StickerSyncScheduler
import com.firestream.chat.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StickerLibrarySync @Inject constructor(
    private val stickerDao: StickerDao,
    private val packSource: StickerPackSource,
    private val authSource: AuthSource,
    private val scheduler: StickerSyncScheduler,
    private val preferences: PreferencesDataStore,
    @ApplicationScope appScope: CoroutineScope,
) {

    /** Held while a change is merged, and for the whole of a sign-out. */
    private val fence = Mutex()

    /**
     * Keeps the library in step with the backend for as long as it is
     * collected. It never emits. One listener serves every collector, starts
     * with the first and stops with the last, so each start reads who is signed
     * in afresh.
     *
     * A listener that fails ends quietly, and the next start tries again: the
     * rules may not be deployed yet, or the user has just signed out.
     */
    val whileObserved: Flow<Nothing> = flow<Nothing> { restore() }
        .catch { e -> Log.w(TAG, "restore stopped", e) }
        .shareIn(appScope, SharingStarted.WhileSubscribed(), replay = 0)

    private suspend fun restore() {
        val ownerId = authSource.currentUserId ?: return
        if (!packSource.isSupported) return
        // A pack an earlier run left pending, for whatever reason.
        scheduler.syncIfPending()
        packSource.observeOwnPacks(ownerId).collect { changes -> apply(ownerId, changes) }
    }

    /**
     * Merges [changes] to [ownerId]'s packs into the library. Nothing is written
     * once [ownerId] is no longer the signed-in user: the tables were cleared for
     * the next one.
     */
    internal suspend fun apply(ownerId: String, changes: StickerPackChanges) = fence.withLock {
        if (authSource.currentUserId != ownerId) return@withLock
        val now = System.currentTimeMillis()
        changes.upserted.forEach { remote ->
            // The listener asks for this user's packs only. A manifest that says otherwise is not one of them.
            if (remote.ownerId != ownerId || !StickerManifest.isValidPackId(remote.id)) return@forEach
            stickerDao.applyRemotePack(StickerManifest.packOf(remote), StickerManifest.stickersOf(remote, now), now)
        }
        changes.removedIds.forEach { stickerDao.removeIfSynced(it) }
        // Two packs merged into one leave a tombstone, and a survivor that gained stickers.
        scheduler.syncIfPending()
    }

    /**
     * Runs [signOut], which clears the library, with no restore writing during
     * it or after it. The sync work is cancelled, and the recents and the Klipy
     * id are cleared: they belong to the user who is leaving.
     *
     * It runs to its end once called. The settings screen leaves in the click
     * that signs out, which cancels the caller's scope. Without this, a sign-out
     * that waits for a merge would never happen, and the recents would stay
     * ("DataStore writes need @ApplicationScope", docs/PATTERNS.md).
     */
    suspend fun signingOut(signOut: suspend () -> Unit) = withContext(NonCancellable) {
        fence.withLock {
            scheduler.cancel()
            signOut()
            preferences.clearRecentStickers()
            preferences.clearKlipyCustomerId()
        }
    }

    private companion object {
        const val TAG = "StickerLibrarySync"
    }
}
