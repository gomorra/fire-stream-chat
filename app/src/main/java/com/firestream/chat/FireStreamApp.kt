package com.firestream.chat

import android.app.Application
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
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.reminder.ReminderNotificationChannel
import com.firestream.chat.data.timer.TimerNotificationChannel
import com.firestream.chat.data.util.CurrentActivityHolder
import com.firestream.chat.data.worker.MediaBackfillWorker
import com.firestream.chat.data.worker.UpdateCheckWorker
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.di.FlavorBootstrap
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FireStreamApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var appLifecycleObserver: AppLifecycleObserver

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var currentActivityHolder: CurrentActivityHolder

    @Inject
    lateinit var flavorBootstraps: @JvmSuppressWildcards Set<FlavorBootstrap>

    @Inject
    lateinit var messageDao: MessageDao

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
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
            .okHttpClient(okHttpClient)
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
        // Register process-level lifecycle observer for online/offline presence.
        // Must happen after super.onCreate() so Hilt completes injection.
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        // Run any flavor-specific eager init (pocketbase contributes the SSE
        // realtime lifecycle hook; firebase contributes nothing in v0).
        flavorBootstraps.forEach { it.start() }
        currentActivityHolder.register(this)
        TimerNotificationChannel.ensureCreated(this)
        ReminderNotificationChannel.ensureCreated(this)
        Executors.newSingleThreadExecutor().execute { cleanOldSharedMedia() }
        recoverOrphanedSends()
        scheduleUpdateCheck()
        scheduleMediaBackfill()
    }

    // A message left at SENDING by a process that died or was killed mid-send is
    // never retried and never marked failed. On startup, flip any such orphan to
    // FAILED so it regains the manual-retry affordance. At this point no send is
    // in flight (the process just started), so only genuine orphans are caught.
    // The deferred auto-retry/durable-outbox follow-up is in TECH_DEBT.md.
    private fun recoverOrphanedSends() {
        appScope.launch { runCatching { messageDao.failStuckSendingMessages() } }
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
