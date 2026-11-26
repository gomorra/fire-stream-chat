package com.firestream.chat

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.outbox.OutboxScheduler
import com.firestream.chat.data.reminder.ReminderNotificationChannel
import com.firestream.chat.data.timer.TimerNotificationChannel
import com.firestream.chat.data.util.CurrentActivityHolder
import com.firestream.chat.data.util.ImageEditRasterizer
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.data.worker.BackupScheduler
import com.firestream.chat.data.worker.MediaBackfillWorker
import com.firestream.chat.data.worker.StickerSyncScheduler
import com.firestream.chat.data.worker.UpdateCheckWorker
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.di.FlavorBootstrap
import com.firestream.chat.domain.repository.AuthRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FireStreamApp : Application(), Configuration.Provider, ImageLoaderFactory {

    // Every @Inject field on FireStreamApp MUST stay dagger.Lazy<T>.
    // RestartActivity runs in an isolated `:restart` process where
    // FirebaseInitProvider does not run; Hilt_FireStreamApp.onCreate() injects
    // these fields before FireStreamApp.onCreate() can return early, so any
    // eager Firebase/Room/WorkManager field crashes `:restart` on startup.
    @Inject
    lateinit var appLifecycleObserver: dagger.Lazy<AppLifecycleObserver>

    @Inject
    lateinit var okHttpClient: dagger.Lazy<OkHttpClient>

    @Inject
    lateinit var workerFactory: dagger.Lazy<HiltWorkerFactory>

    @Inject
    lateinit var currentActivityHolder: dagger.Lazy<CurrentActivityHolder>

    @Inject
    lateinit var flavorBootstraps: dagger.Lazy<@JvmSuppressWildcards Set<FlavorBootstrap>>

    @Inject
    lateinit var outboxScheduler: dagger.Lazy<OutboxScheduler>

    @Inject
    lateinit var stickerSyncScheduler: dagger.Lazy<StickerSyncScheduler>

    @Inject
    lateinit var imageEditRasterizer: dagger.Lazy<ImageEditRasterizer>

    @Inject
    lateinit var authRepository: dagger.Lazy<AuthRepository>

    @Inject
    lateinit var profileImageManager: dagger.Lazy<ProfileImageManager>

    @Inject
    lateinit var backupScheduler: dagger.Lazy<BackupScheduler>

    @Inject
    lateinit var preferencesDataStore: dagger.Lazy<PreferencesDataStore>

    @Inject
    @ApplicationScope
    lateinit var appScope: dagger.Lazy<CoroutineScope>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory.get())
            .build()

    // Coil's app-wide ImageLoader. Without this, avatars run on Coil's defaults
    // with no stable cache key, so they re-decode/re-fetch (and flash blank) on
    // every appearance. A generous in-memory cache plus per-avatar cache keys
    // (see rememberAvatarRequest) keeps each decoded avatar warm; the local-file
    // layer (ProfileImageManager) still owns persistent/offline storage and
    // change invalidation. respectCacheHeaders(false): Firebase Storage URLs are
    // immutable per download token, so we never want Coil to revalidate — a
    // changed photo rotates the token, which rotates our cache key instead.
    // Called lazily on first Coil use (after onCreate), so okHttpClient is ready.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient.get())
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()

    override fun onCreate() {
        super.onCreate()
        // RestartActivity runs in a short-lived `:restart` process solely to kill
        // the main process and relaunch MainActivity after a DB restore; skip all
        // app initialization there.
        if (getProcessName().endsWith(":restart")) return
        // Register process-level lifecycle observer for online/offline presence.
        // Must happen after super.onCreate() so Hilt completes injection.
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver.get())
        // Run any flavor-specific eager init (pocketbase contributes the SSE
        // realtime lifecycle hook; firebase contributes nothing in v0).
        flavorBootstraps.get().forEach { it.start() }
        currentActivityHolder.get().register(this)
        TimerNotificationChannel.ensureCreated(this)
        ReminderNotificationChannel.ensureCreated(this)
        Executors.newSingleThreadExecutor().execute {
            cleanOldSharedMedia()
            // The image editor's rasterized steps. Undo can no longer free the
            // file it steps off (redo has to be able to walk forward again), so
            // an unswept edit cache grows per edit *step* and never shrinks;
            // sweepStale keeps only what a recent send might still retry.
            imageEditRasterizer.get().sweepStale()
            profileImageManager.get().deleteLegacyExternalCache()
        }
        requeueQueuedSends()
        announceCallVideoLine()
        syncPendingStickerPacks()
        scheduleUpdateCheck()
        scheduleMediaBackfill()
        reconcileScheduledBackup()
    }

    private fun reconcileScheduledBackup() {
        appScope.get().launch {
            runCatching {
                val freq = preferencesDataStore.get().backupFrequencyFlow.first()
                backupScheduler.get().reconcile(freq)
            }.onFailure { Log.w("FireStreamApp", "reconcileScheduledBackup failed", it) }
        }
    }

    // A caller offers a video line only to a user whose document says their app takes one. An
    // app without video crashes on such an offer. Every process start says it again, so an
    // updated app is offered video from its first start on, a start by a push included. The
    // repository logs a failure; the next start is the retry.
    private fun announceCallVideoLine() {
        appScope.get().launch { authRepository.get().announceCallVideoLine() }
    }

    // An own row still SENDING belongs to the outbox: WorkManager already holds
    // its work across a process death or a reboot, and requeueAll (KEEP) is the
    // belt to those braces. It also sweeps staged inputs no queued row owns, and
    // fails the SENDING rows of types the outbox cannot send — a timer whose
    // await died with the process — so their retry affordance returns.
    private fun requeueQueuedSends() {
        appScope.get().launch {
            runCatching { outboxScheduler.get().requeueAll() }
                .onFailure { Log.w("FireStreamApp", "requeueAll failed", it) }
        }
    }

    // A sticker pack changed while offline, or in the moment a sync run ended,
    // is still marked pending. This queues the run that backs it up.
    private fun syncPendingStickerPacks() {
        appScope.get().launch { stickerSyncScheduler.get().syncIfPending() }
    }

    private fun scheduleUpdateCheck() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    // Daily backfill clears stale localUri rows whose underlying file is gone
    // and re-downloads anything still missing. The auto-download path on
    // message receive covers the happy case; this catches the long tail where
    // a file got deleted out from under us or an earlier download failed.
    private fun scheduleMediaBackfill() {
        val request = PeriodicWorkRequestBuilder<MediaBackfillWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "media_backfill_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun cleanOldSharedMedia() {
        val sharedMediaDir = File(cacheDir, "shared_media")
        if (!sharedMediaDir.exists()) return
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
        sharedMediaDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }
}
