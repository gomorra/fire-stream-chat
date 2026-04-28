package com.firestream.chat

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import com.firestream.chat.data.util.CurrentActivityHolder
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
