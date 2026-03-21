package com.firestream.chat

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FireStreamApp : Application() {

    @Inject
    lateinit var appLifecycleObserver: AppLifecycleObserver

    override fun onCreate() {
        super.onCreate()
        // Register process-level lifecycle observer for online/offline presence.
        // Must happen after super.onCreate() so Hilt completes injection.
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
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
