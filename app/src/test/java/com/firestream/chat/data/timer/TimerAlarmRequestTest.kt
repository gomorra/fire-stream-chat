package com.firestream.chat.data.timer

import android.content.Intent
import android.os.Build
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Decoding of the alarm parameters carried on a scheduler intent.
 *
 * The legacy cases are the ones that matter most: `PendingIntent`s scheduled by
 * an older build survive an app update and get delivered to the *new* receiver,
 * so an already-running timer must not change how it behaves just because the
 * app was upgraded underneath it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    manifest = Config.NONE,
    application = android.app.Application::class,
)
class TimerAlarmRequestTest {

    private fun intent(build: Intent.() -> Unit = {}) = Intent().apply {
        putExtra(TimerAlarmScheduler.EXTRA_MESSAGE_ID, "m1")
        putExtra(TimerAlarmScheduler.EXTRA_CHAT_ID, "c1")
        build()
    }

    @Test
    fun `full intent decodes every field`() {
        val request = TimerAlarmRequest.from(
            intent {
                putExtra(TimerAlarmScheduler.EXTRA_CAPTION, "Pizza")
                putExtra(TimerAlarmScheduler.EXTRA_OTHER_USER_ID, "them")
                putExtra(TimerAlarmScheduler.EXTRA_ALARM_STYLE, "INSISTENT")
                putExtra(TimerAlarmScheduler.EXTRA_ALARM_SOUND, "RINGTONE")
                putExtra(TimerAlarmScheduler.EXTRA_REALERT_ATTEMPT, 2)
            },
        )!!

        assertEquals("m1", request.messageId)
        assertEquals("c1", request.chatId)
        assertEquals("Pizza", request.caption)
        assertEquals("them", request.otherUserId)
        assertEquals(TimerAlarmStyle.INSISTENT, request.style)
        assertEquals(TimerAlarmSound.RINGTONE, request.sound)
        assertEquals(2, request.realertAttempt)
    }

    @Test
    fun `intent without a message id or chat id is rejected`() {
        assertNull(TimerAlarmRequest.from(Intent()))
        assertNull(
            TimerAlarmRequest.from(
                Intent().apply { putExtra(TimerAlarmScheduler.EXTRA_MESSAGE_ID, "m1") },
            ),
        )
    }

    @Test
    fun `legacy silent alarm scheduled before the enum existed stays silent`() {
        // The failure this guards: updating the app mid-timer makes every running
        // silent timer suddenly ring, because the old PendingIntent carries only
        // EXTRA_SILENT and the new receiver looked for a style that isn't there.
        val request = TimerAlarmRequest.from(
            intent { putExtra(TimerAlarmScheduler.EXTRA_SILENT, true) },
        )!!

        assertEquals(TimerAlarmStyle.SILENT, request.style)
    }

    @Test
    fun `legacy non-silent alarm becomes NORMAL`() {
        val request = TimerAlarmRequest.from(intent())!!

        assertEquals(TimerAlarmStyle.NORMAL, request.style)
        assertEquals(TimerAlarmSound.DEFAULT, request.sound)
        assertEquals(0, request.realertAttempt)
    }

    @Test
    fun `unrecognised style falls back to the legacy boolean rather than ringing`() {
        val request = TimerAlarmRequest.from(
            intent {
                putExtra(TimerAlarmScheduler.EXTRA_ALARM_STYLE, "THERMONUCLEAR")
                putExtra(TimerAlarmScheduler.EXTRA_SILENT, true)
            },
        )!!

        assertEquals(TimerAlarmStyle.SILENT, request.style)
    }

    @Test
    fun `unrecognised sound falls back to the default`() {
        val request = TimerAlarmRequest.from(
            intent { putExtra(TimerAlarmScheduler.EXTRA_ALARM_SOUND, "AIR_HORN") },
        )!!

        assertEquals(TimerAlarmSound.DEFAULT, request.sound)
    }

    @Test
    fun `each sound maps to its own channel`() {
        val ids = TimerAlarmSound.entries.map { TimerNotificationChannel.channelIdFor(it) }

        assertEquals(
            "one channel per sound — they carry frozen, differing sounds",
            TimerAlarmSound.entries.size,
            ids.toSet().size,
        )
        // Distinct from the superseded channel, which is deleted at startup; reusing
        // that id would silently inherit its frozen 3-second vibration.
        assertEquals(false, ids.contains("timer_alarms"))
    }
}
