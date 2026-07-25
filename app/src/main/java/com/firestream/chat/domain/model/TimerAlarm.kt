package com.firestream.chat.domain.model

/**
 * How insistently a timer announces itself when it fires.
 *
 * Chosen by the sender in `.timer.set` and synced to every participant, so a
 * timer two people share rings the same way on both phones — the urgency is
 * the sender's intent, not a per-device preference.
 *
 * Supersedes the older boolean `timerSilent`, which lives on only as a wire
 * field: it is still *written* alongside the enum (`SILENT` ⇒ `true`) so a
 * client that predates this type keeps honouring the silent/not-silent
 * distinction, and still *read* as the fallback for documents written before
 * the enum existed. Both directions are handled at the data boundary
 * (`resolveTimerAlarmStyle`), so `Message` carries only this.
 */
enum class TimerAlarmStyle {
    /** No notification at all — the bubble still flips to COMPLETED. */
    SILENT,

    /** One pass of the channel's sound and vibration pattern, then quiet. */
    NORMAL,

    /**
     * Keeps ringing until dismissed (`FLAG_INSISTENT`), backstopped by an
     * auto-silence timeout — the platform imposes no maximum of its own, so
     * an unattended timer would otherwise ring until the battery died.
     */
    INSISTENT;

    val isSilent: Boolean get() = this == SILENT

    companion object {
        val DEFAULT: TimerAlarmStyle = NORMAL

        /** Style for a message written before the field existed. */
        fun fromLegacySilent(silent: Boolean): TimerAlarmStyle = if (silent) SILENT else NORMAL
    }
}

/**
 * Which sound a fired timer plays, as a *symbolic* choice rather than a URI.
 *
 * This is deliberate: a `content://` ringtone URI is device-local and would
 * resolve to nothing on the recipient's phone, so syncing one would break the
 * shared-timer model. Each device maps the symbol to its own system sound —
 * see `TimerNotificationChannel`, which owns the resolution because
 * `RingtoneManager` is an Android dependency and this layer must stay pure.
 *
 * Every option resolves to a stock system sound, so there is nothing to bundle
 * and nothing that can be missing on a given device.
 */
enum class TimerAlarmSound {
    /** The device's default *alarm* sound — the clock-app default. */
    ALARM,

    /** The device's incoming-call ringtone: the hardest of the three to ignore. */
    RINGTONE,

    /** The device's notification sound — a short chime for low-stakes timers. */
    GENTLE;

    companion object {
        val DEFAULT: TimerAlarmSound = ALARM
    }
}
