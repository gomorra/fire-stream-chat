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
import com.firestream.chat.data.worker.MediaBackfillWorker
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Register process-level lifecycle observer for online/offline presence.
        // Must happen after super.onCreate() so Hilt completes injection.
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        Executors.newSingleThreadExecutor().execute { cleanOldSharedMedia() }

        // Enqueue periodic media backfill worker to download missing local media
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "media_backfill",
            ExistingPeriodicWorkPolicy.REPLACE,
            PeriodicWorkRequestBuilder<MediaBackfillWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
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
