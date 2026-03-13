package com.firestream.chat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class FireStreamApp : Application() {

    override fun onCreate() {
        super.onCreate()
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

