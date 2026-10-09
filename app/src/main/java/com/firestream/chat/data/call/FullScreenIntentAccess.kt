package com.firestream.chat.data.call

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.firestream.chat.domain.util.FULL_SCREEN_ACCESS_MIN_API
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The special access *Full screen notifications* of Android 14 and later.
 *
 * With it, an incoming call and a timer alarm wake the display and show over the lock screen.
 * Without it, the system drops the notification's full-screen intent and shows only the
 * notification. A sideloaded install starts without the access, and only the user can grant it.
 */
@Singleton
class FullScreenIntentAccess @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val apiLevel: Int get() = Build.VERSION.SDK_INT

    /** Whether this Android version has the access at all. */
    val isSupported: Boolean get() = apiLevel >= FULL_SCREEN_ACCESS_MIN_API

    /** Always true below Android 14, where the manifest permission is enough. */
    fun isGranted(): Boolean =
        !isSupported || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /**
     * Opens the app's *Full screen notifications* page in the system settings. It carries
     * `FLAG_ACTIVITY_NEW_TASK`, so it can be started from any context.
     */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Opens that page. A device without it gets the app's notification settings. A device with
     * neither page gets nothing, and the tap does not crash.
     */
    fun openSettings() {
        val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(settingsIntent()) && !start(notificationSettings)) {
            Log.w(TAG, "No settings page for the full-screen notification access")
        }
    }

    private fun start(intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }

    private companion object {
        const val TAG = "FullScreenIntentAccess"
    }
}
