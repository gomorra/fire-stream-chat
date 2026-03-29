package com.firestream.chat.data.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.util.MediaFileManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class MediaBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val messageDao: MessageDao,
    private val mediaFileManager: MediaFileManager,
    private val preferencesDataStore: PreferencesDataStore,
    private val connectivityManager: ConnectivityManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val isManual = inputData.getBoolean("manual", false)
        Log.w(TAG, "Backfill started — manual=$isManual")

        if (!isManual) {
            val option = preferencesDataStore.autoDownloadFlow.first()
            when (option) {
                AutoDownloadOption.NEVER -> {
                    Log.w(TAG, "Backfill skipped: AutoDownload=NEVER")
                    return Result.success()
                }
                AutoDownloadOption.WIFI_ONLY -> if (!isOnWifi()) {
                    Log.w(TAG, "Backfill skipped: WIFI_ONLY but not on WiFi")
                    return Result.success()
                }
                AutoDownloadOption.ALWAYS -> Unit
            }
        }

        // Migrate old files from filesDir/media/ to externalMediaDirs
        val migrated = mediaFileManager.migrateFromInternalStorage()
        if (migrated > 0) {
            Log.w(TAG, "Backfill: migrated $migrated files to external storage")
            // Update localUri paths in Room to point to new location
            val allMedia = messageDao.getAllMediaMessages()
            for (msg in allMedia) {
                val oldUri = msg.localUri ?: continue
                if (!oldUri.contains("/files/media/")) continue
                val ext = oldUri.substringAfterLast(".", "jpg")
                val newFile = mediaFileManager.getLocalFile(msg.chatId, msg.id, ext)
                if (newFile.exists()) {
                    messageDao.updateLocalUri(msg.id, newFile.absolutePath)
                }
            }
        }

        val messages = messageDao.getMessagesWithoutLocalMedia()
        val total = messages.size
        Log.w(TAG, "Backfill: found $total messages without local media (need download)")
        if (total == 0) return Result.success()

        var done = 0
        for (msg in messages) {
            try {
                Log.w(TAG, "Backfill: downloading ${msg.id} url=${msg.mediaUrl?.take(80)}")
                val file = mediaFileManager.downloadAndSave(msg.chatId, msg.id, msg.mediaUrl!!)
                messageDao.updateLocalUri(msg.id, file.absolutePath)
                Log.w(TAG, "Backfill: saved ${msg.id} → ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Backfill: failed ${msg.id}: ${e.message}", e)
            } finally {
                done++
                setProgress(workDataOf("done" to done, "total" to total))
            }
        }
        Log.w(TAG, "Backfill complete: $done/$total")
        return Result.success()
    }

    companion object {
        private const val TAG = "MediaBackfillWorker"
    }

    private fun isOnWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
