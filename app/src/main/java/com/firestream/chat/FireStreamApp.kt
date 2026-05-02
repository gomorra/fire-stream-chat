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
import com.firestream.chat.data.util.CurrentActivityHolder
import com.firestream.chat.data.worker.MediaBackfillWorker
import com.firestream.chat.data.worker.UpdateCheckWorker
import com.firestream.chat.di.FlavorBootstrap
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FireStreamApp : Application(), Configuration.Provider {

    @Inject
    lateinit var appLifecycleObserver: AppLifecycleObserver

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var currentActivityHolder: CurrentActivityHolder

    @Inject
    lateinit var flavorBootstraps: @JvmSuppressWildcards Set<FlavorBootstrap>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
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
        Executors.newSingleThreadExecutor().execute { cleanOldSharedMedia() }
        scheduleUpdateCheck()
        scheduleMediaBackfill()
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
