package com.firestream.chat.data.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import com.firestream.chat.domain.model.TimerAlarmSound

/**
 * Owns the notification channels used by [TimerAlarmReceiver] — **one per
 * [TimerAlarmSound]**, because a channel's sound and vibration are frozen by the
 * system at creation time and can never be edited afterwards. Per-timer variation
 * therefore has to be per-*channel*; the sender's synced choice picks which one to
 * post on via [channelIdFor].
 *
 * Every channel routes through ALARM-grade [AudioAttributes] regardless of which
 * sound was chosen. That is deliberate and load-bearing: `USAGE_ALARM` puts the
 * sound on `STREAM_ALARM`, so it plays at alarm volume and survives vibrate/silent
 * ringer mode, and `CATEGORY_ALARM` on the notification gets it past Do Not
 * Disturb. Even [TimerAlarmSound.GENTLE] keeps that routing — the user picked a
 * softer *sound*, not permission for the system to swallow their timer. Opting out
 * of noise entirely is what `TimerAlarmStyle.SILENT` is for.
 *
 * The vibration pattern is ~6.8s rather than the 3s one-shot the original channel
 * shipped with, which is the "vibrate longer" half of making the alarm hard to
 * miss. It does not depend on `FLAG_INSISTENT` also repeating the waveform (which
 * varies by OEM) — the pattern is simply long on its own.
 */
internal object TimerNotificationChannel {

    /**
     * The pre-v2 channel: `IMPORTANCE_HIGH`, default alarm sound, and a 3-second
     * one-shot vibration. Superseded by the per-sound channels below and deleted
     * on startup, since a frozen channel can't be upgraded in place and leaving it
     * behind just shows the user a stale entry in system settings that controls
     * nothing.
     */
    private const val LEGACY_CHANNEL_ID: String = "timer_alarms"

    private const val CHANNEL_ID_PREFIX: String = "timer_alarm_v2_"

    /** ~6.8s: six 800ms buzzes separated by 400ms gaps. */
    private val ALARM_VIBRATION_PATTERN: LongArray =
        longArrayOf(0, 800, 400, 800, 400, 800, 400, 800, 400, 800, 400, 800)

    /** Channel carrying [sound]. Stable across launches — it's part of the id. */
    fun channelIdFor(sound: TimerAlarmSound): String =
        CHANNEL_ID_PREFIX + sound.name.lowercase()

    fun ensureCreated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        // Idempotent and safe when the channel was never created (fresh install).
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        val alarmAudio: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        for (sound in TimerAlarmSound.entries) {
            val id = channelIdFor(sound)
            if (nm.getNotificationChannel(id) != null) continue

            val channel = NotificationChannel(
                id,
                nameFor(sound),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = descriptionFor(sound)
                setSound(uriFor(sound), alarmAudio)
                enableVibration(true)
                vibrationPattern = ALARM_VIBRATION_PATTERN
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Resolves the symbolic choice to a system sound on *this* device — the reason
     * [TimerAlarmSound] travels as an enum rather than a URI.
     *
     * Each default can be null when the user has set that category to "None", so
     * we fall back to the alarm default and finally to null, which leaves the
     * channel silent rather than crashing at creation.
     */
    private fun uriFor(sound: TimerAlarmSound): Uri? {
        val type = when (sound) {
            TimerAlarmSound.ALARM -> RingtoneManager.TYPE_ALARM
            TimerAlarmSound.RINGTONE -> RingtoneManager.TYPE_RINGTONE
            TimerAlarmSound.GENTLE -> RingtoneManager.TYPE_NOTIFICATION
        }
        return RingtoneManager.getDefaultUri(type)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }

    private fun nameFor(sound: TimerAlarmSound): String = when (sound) {
        TimerAlarmSound.ALARM -> "Timer alarms"
        TimerAlarmSound.RINGTONE -> "Timer alarms (ringtone)"
        TimerAlarmSound.GENTLE -> "Timer alarms (gentle)"
    }

    private fun descriptionFor(sound: TimerAlarmSound): String = when (sound) {
        TimerAlarmSound.ALARM -> "Chat timers that ring with the alarm sound"
        TimerAlarmSound.RINGTONE -> "Chat timers that ring with the phone ringtone"
        TimerAlarmSound.GENTLE -> "Chat timers that play a short chime"
    }
}
