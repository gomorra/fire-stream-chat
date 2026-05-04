package com.firestream.chat.data.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

/**
 * Owns the `timer_alarms` notification channel used by [TimerAlarmReceiver].
 *
 * IMPORTANCE_HIGH so the system shows a heads-up notification while the screen
 * is on and routes the full-screen intent on lock screens. Sound is the system
 * default *alarm* (not notification) URI plumbed through ALARM-grade
 * [AudioAttributes] so volume tracks the alarm slider — matches what the
 * AOSP DeskClock does. Vibration mimics the standard alarm pattern.
 *
 * Channel settings are immutable after first creation by the system; bumping
 * the channel id is the only way to change importance/sound after install.
 */
internal object TimerNotificationChannel {

    const val CHANNEL_ID: String = "timer_alarms"
    private const val CHANNEL_NAME: String = "Timer alarms"
    private const val CHANNEL_DESCRIPTION: String =
        "Plays an alarm-style ring when a chat timer fires"

    private val ALARM_VIBRATION_PATTERN: LongArray =
        longArrayOf(0, 1_000, 500, 1_000, 500)

    fun ensureCreated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val alarmAudio: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = CHANNEL_DESCRIPTION
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAudio)
            enableVibration(true)
            vibrationPattern = ALARM_VIBRATION_PATTERN
        }

        nm.createNotificationChannel(channel)
    }
}
