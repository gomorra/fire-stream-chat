// region: AGENT-NOTE
// Responsibility: The WorkManager side of a failed media download — one unique
//   one-time MediaBackfillWorker run, constrained by the auto-download preference.
// Owns: the unique-work name (media-download-retry), the KEEP policy, the
//   preference → network-constraint mapping (never = no run).
// Collaborators: MessageRepositoryImpl (retryDownloads after a failed
//   tryAutoDownload or per-chat scan), MediaBackfillWorker (what the run does),
//   PreferencesDataStore.
// Don't put here: the download itself (MediaFileManager), the periodic run
//   (FireStreamApp) or the manual one (SettingsViewModel) — both pre-date this
//   class and stay where they are (TECH_DEBT "Three MediaBackfillWorker request
//   builders").
// endregion

package com.firestream.chat.data.worker

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "MediaBackfillScheduler"

/**
 * Queues one [MediaBackfillWorker] run for the media a download just failed to
 * fetch — a received photo the auto-download could not pull while the network
 * was going away, say — so it lands once there is a network again, whether or
 * not the chat is ever opened. The daily periodic run stays the long-tail sweep.
 *
 * One unique work, KEEP: a burst of failures queues a single run. The constraint
 * mirrors the auto-download preference — unmetered for "Wi-Fi only", any
 * network otherwise — and "never" queues nothing, since the worker would only
 * return at once. The worker re-reads the preference when it runs, so a
 * preference changed meanwhile is still honoured.
 */
@Singleton
class MediaBackfillScheduler @Inject constructor(
    private val workManager: Provider<WorkManager>,
    private val preferencesDataStore: PreferencesDataStore,
) {

    suspend fun retryDownloads() {
        val networkType = when (preferencesDataStore.autoDownloadFlow.first()) {
            AutoDownloadOption.NEVER -> return
            AutoDownloadOption.WIFI_ONLY -> NetworkType.UNMETERED
            AutoDownloadOption.ALWAYS -> NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<MediaBackfillWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .build()
        workManager.get().enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        Log.d(TAG, "retryDownloads: queued, network=$networkType")
    }

    companion object {
        const val RETRY_WORK_NAME = "media-download-retry"
    }
}
