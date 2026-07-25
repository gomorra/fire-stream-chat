package com.firestream.chat.data.local.entity

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alarm style/sound fields are *synced* — the sender's choice rings on every
 * participant's phone — so the two phones in a conversation can be running
 * different app versions. These tests pin the resulting compatibility contract:
 *
 *  - a message with no style at all (written before the enum existed) resolves
 *    from the legacy `timerSilent` boolean;
 *  - a style name this build doesn't recognise (written by a *newer* client)
 *    degrades the same way rather than crashing or guessing;
 *  - `Message.alarmStyle` / `.alarmSound` are the only sanctioned read paths.
 */
class MessageEntityAlarmMappingTest {

    private fun timerMessage(
        style: TimerAlarmStyle? = null,
        sound: TimerAlarmSound? = null,
        silent: Boolean = false,
    ) = Message(
        id = "msg1",
        chatId = "chat1",
        senderId = "user1",
        type = MessageType.TIMER,
        timerDurationMs = 30_000L,
        timerSilent = silent,
        timerAlarmStyle = style,
        timerAlarmSound = sound,
    )

    private fun rawEntity(styleName: String?, soundName: String?, silent: Boolean) = MessageEntity(
        id = "msg1",
        chatId = "chat1",
        senderId = "user1",
        content = "",
        type = MessageType.TIMER.name,
        mediaUrl = null,
        mediaThumbnailUrl = null,
        status = "SENT",
        replyToId = null,
        timestamp = 0L,
        editedAt = null,
        timerSilent = silent,
        timerAlarmStyle = styleName,
        timerAlarmSound = soundName,
    )

    @Test
    fun `every style and sound combination round-trips`() {
        for (style in TimerAlarmStyle.entries) {
            for (sound in TimerAlarmSound.entries) {
                val back = MessageEntity.fromDomain(timerMessage(style, sound)).toDomain()
                assertEquals(style, back.timerAlarmStyle)
                assertEquals(sound, back.timerAlarmSound)
                assertEquals(style, back.alarmStyle)
                assertEquals(sound, back.alarmSound)
            }
        }
    }

    @Test
    fun `legacy silent timer with no style resolves to SILENT`() {
        val back = rawEntity(styleName = null, soundName = null, silent = true).toDomain()

        assertNull("no explicit style was stored", back.timerAlarmStyle)
        assertEquals(TimerAlarmStyle.SILENT, back.alarmStyle)
        assertTrue(back.alarmStyle.isSilent)
    }

    @Test
    fun `legacy non-silent timer with no style resolves to NORMAL`() {
        val back = rawEntity(styleName = null, soundName = null, silent = false).toDomain()

        assertEquals(TimerAlarmStyle.NORMAL, back.alarmStyle)
        assertEquals(TimerAlarmSound.DEFAULT, back.alarmSound)
    }

    @Test
    fun `style from a newer client falls back to the legacy boolean, not a guess`() {
        // A future build sends SILENT plus a style name we've never heard of. The
        // unknown name must not out-vote timerSilent — degrading to a ringing alarm
        // when the sender asked for silence is the failure that actually annoys.
        val back = rawEntity(styleName = "THERMONUCLEAR", soundName = null, silent = true).toDomain()

        assertEquals(TimerAlarmStyle.SILENT, back.alarmStyle)
    }

    @Test
    fun `unknown sound from a newer client degrades to the default`() {
        val back = rawEntity(styleName = null, soundName = "AIR_HORN", silent = false).toDomain()

        assertEquals(TimerAlarmSound.DEFAULT, back.alarmSound)
    }

    @Test
    fun `writing a style keeps the legacy boolean in agreement`() {
        // Older clients read only timerSilent, so the two must never disagree.
        val silent = MessageEntity.fromDomain(
            timerMessage(style = TimerAlarmStyle.SILENT, silent = true),
        )
        assertTrue(silent.timerSilent)
        assertEquals("SILENT", silent.timerAlarmStyle)

        val insistent = MessageEntity.fromDomain(
            timerMessage(style = TimerAlarmStyle.INSISTENT, silent = false),
        )
        assertEquals(false, insistent.timerSilent)
        assertEquals("INSISTENT", insistent.timerAlarmStyle)
    }

    @Test
    fun `non-timer messages carry no alarm fields`() {
        val text = Message(id = "m", chatId = "c", senderId = "u", type = MessageType.TEXT)
        val entity = MessageEntity.fromDomain(text)

        assertNull(entity.timerAlarmStyle)
        assertNull(entity.timerAlarmSound)
        // Defaults still resolve rather than throwing, even where they're meaningless.
        assertEquals(TimerAlarmStyle.NORMAL, entity.toDomain().alarmStyle)
    }
}
