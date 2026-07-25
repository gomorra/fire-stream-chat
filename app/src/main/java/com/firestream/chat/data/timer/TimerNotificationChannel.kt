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
    private const val CHANNEL_ID_PREFIX: String = "timer_alarm_v2_"

    /**
     * Every channel this feature has ever owned starts with this. Retirement works
     * by *subtraction* — anything matching that isn't currently live gets deleted —
     * so bumping [CHANNEL_ID_PREFIX] for the next frozen-property change is a
     * one-line edit instead of a fresh round of "remember to delete the old ids".
     * Note the pre-v2 channel was `timer_alarms`, which this prefix also covers.
     */
    private const val OWNED_CHANNEL_PREFIX: String = "timer_alarm"

    /** ~6.8s: six 800ms buzzes separated by 400ms gaps. */
    private val ALARM_VIBRATION_PATTERN: LongArray =
        longArrayOf(0, 800, 400, 800, 400, 800, 400, 800, 400, 800, 400, 800)

    /** ~1.2s: two short taps. A "gentle" alarm that buzzed for 7s wouldn't be. */
    private val GENTLE_VIBRATION_PATTERN: LongArray =
        longArrayOf(0, 300, 300, 300)

    /** Channel carrying [sound]. Stable across launches — it's part of the id. */
    fun channelIdFor(sound: TimerAlarmSound): String =
        CHANNEL_ID_PREFIX + sound.name.lowercase()

    fun ensureCreated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val liveIds = TimerAlarmSound.entries.map(::channelIdFor).toSet()
        retireStaleChannels(nm, liveIds)

        val alarmAudio: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        for (sound in TimerAlarmSound.entries) {
            val id = channelIdFor(sound)
            if (nm.getNotificationChannel(id) != null) continue

            val spec = specFor(sound)
            val channel = NotificationChannel(
                id,
                spec.name,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = spec.description
                setSound(resolveUri(spec.ringtoneType), alarmAudio)
                enableVibration(true)
                vibrationPattern = spec.vibrationPattern
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Drops channels this feature owns but no longer posts to. Read-then-delete
     * rather than an unconditional delete: after the first upgraded launch there
     * is nothing to remove, and `deleteNotificationChannel` isn't free on the
     * system side (it rescans notifications and schedules a policy-file write).
     */
    private fun retireStaleChannels(nm: NotificationManager, liveIds: Set<String>) {
        nm.notificationChannels
            .map { it.id }
            .filter { it.startsWith(OWNED_CHANNEL_PREFIX) && it !in liveIds }
            .forEach(nm::deleteNotificationChannel)
    }

    /**
     * Everything a channel freezes at creation, in one place.
     *
     * A channel owns *(sound, vibration, importance)*, not just sound — keeping the
     * triple together is what lets [TimerAlarmSound.GENTLE] actually be gentle
     * rather than a soft chime followed by seven seconds of alarm-grade buzzing.
     */
    private data class ChannelSpec(
        val ringtoneType: Int,
        val name: String,
        val description: String,
        val vibrationPattern: LongArray,
    )

    private fun specFor(sound: TimerAlarmSound): ChannelSpec = when (sound) {
        TimerAlarmSound.ALARM -> ChannelSpec(
            RingtoneManager.TYPE_ALARM,
            "Timer alarms",
            "Chat timers that ring with the alarm sound",
            ALARM_VIBRATION_PATTERN,
        )
        TimerAlarmSound.RINGTONE -> ChannelSpec(
            RingtoneManager.TYPE_RINGTONE,
            "Timer alarms (ringtone)",
            "Chat timers that ring with the phone ringtone",
            ALARM_VIBRATION_PATTERN,
        )
        TimerAlarmSound.GENTLE -> ChannelSpec(
            RingtoneManager.TYPE_NOTIFICATION,
            "Timer alarms (gentle)",
            "Chat timers that play a short chime",
            GENTLE_VIBRATION_PATTERN,
        )
    }

    /**
     * Resolves the symbolic choice to a system sound on *this* device — the reason
     * [TimerAlarmSound] travels as an enum rather than a URI.
     *
     * A default can be null when the user has set that category to "None", so we
     * fall back to the alarm default and finally to null, which leaves the channel
     * silent rather than crashing at creation.
     */
    private fun resolveUri(ringtoneType: Int): Uri? =
        RingtoneManager.getDefaultUri(ringtoneType)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
}
