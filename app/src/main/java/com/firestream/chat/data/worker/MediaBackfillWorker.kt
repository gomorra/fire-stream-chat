package com.firestream.chat.data.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

        // Migrate files from old storage locations to Pictures/FireStream Images/
        val migrated = mediaFileManager.migrateOldStorage()
        if (migrated > 0) {
            val allMedia = messageDao.getAllMediaMessages()
            for (msg in allMedia) {
                val oldUri = msg.localUri ?: continue
                val ext = oldUri.substringAfterLast(".", "jpg")
                val newFile = mediaFileManager.getLocalFile(msg.chatId, msg.id, ext)
                if (newFile.exists() && newFile.absolutePath != oldUri) {
                    messageDao.updateLocalUri(msg.id, newFile.absolutePath)
                }
            }
        }

        // Clear stale localUri values where file no longer exists
        for (msg in messageDao.getAllMediaMessages()) {
            val uri = msg.localUri ?: continue
            if (!java.io.File(uri).exists()) {
                messageDao.updateLocalUri(msg.id, null)
            }
        }

        val messages = messageDao.getMessagesWithoutLocalMedia()
        val total = messages.size
        if (total == 0) return Result.success()

        var done = 0
        for (msg in messages) {
            try {
                val file = mediaFileManager.downloadAndSave(msg.chatId, msg.id, msg.mediaUrl!!)
                messageDao.updateLocalUri(msg.id, file.absolutePath)
            } catch (_: Exception) { }
            done++
            setProgress(workDataOf("done" to done, "total" to total))
        }
        return Result.success()
    }

    private fun isOnWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
