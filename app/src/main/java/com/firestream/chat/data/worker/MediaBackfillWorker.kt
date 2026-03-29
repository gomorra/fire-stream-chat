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
        if (!isManual) {
            when (preferencesDataStore.autoDownloadFlow.first()) {
                AutoDownloadOption.NEVER -> return Result.success()
                AutoDownloadOption.WIFI_ONLY -> if (!isOnWifi()) return Result.success()
                AutoDownloadOption.ALWAYS -> Unit
            }
        }

        val messages = messageDao.getMessagesWithoutLocalMedia()
        val total = messages.size
        Log.d(TAG, "Backfill: found $total messages without local media")
        if (total == 0) return Result.success()

        var done = 0
        for (msg in messages) {
            try {
                Log.d(TAG, "Backfill: downloading ${msg.id} from ${msg.mediaUrl}")
                val file = mediaFileManager.downloadAndSave(msg.chatId, msg.id, msg.mediaUrl!!)
                messageDao.updateLocalUri(msg.id, file.absolutePath)
                Log.d(TAG, "Backfill: saved ${msg.id} → ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Backfill: failed ${msg.id}: ${e.message}")
            } finally {
                done++
                setProgress(workDataOf("done" to done, "total" to total))
            }
        }
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
