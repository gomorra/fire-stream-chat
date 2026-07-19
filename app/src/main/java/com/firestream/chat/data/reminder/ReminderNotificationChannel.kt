package com.firestream.chat.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

/**
 * Owns the `message_reminders` notification channel used by
 * [ReminderAlarmReceiver] and [ReminderNotificationPoster].
 *
 * Deliberately **notification-grade, NOT alarm-grade** — unlike the timer
 * feature's `timer_alarms` channel. A snooze reminder is a gentle nudge, not an
 * alarm the user must dismiss: IMPORTANCE_HIGH (so it heads-up while the screen
 * is on) but with the system default *notification* sound routed through
 * USAGE_NOTIFICATION audio attributes (volume tracks the notification slider,
 * not the alarm slider) and the standard default vibration — no custom alarm
 * pattern and no full-screen intent.
 *
 * Channel settings are immutable after first creation by the system; bumping the
 * channel id is the only way to change importance/sound after install.
 */
internal object ReminderNotificationChannel {

    const val CHANNEL_ID: String = "message_reminders"
    private const val CHANNEL_NAME: String = "Message reminders"
    private const val CHANNEL_DESCRIPTION: String =
        "Notifies you at the time you snoozed a message"

    fun ensureCreated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val notificationAudio: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = CHANNEL_DESCRIPTION
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                notificationAudio,
            )
            enableVibration(true)
        }

        nm.createNotificationChannel(channel)
    }
}
