package com.firestream.chat.data.local.entity

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Room-level mapping of the alarm fields.
 *
 * Note what is deliberately *not* tested here: the legacy `timerSilent` fallback.
 * The database reaches v25 by destructive migration, so no row in it can predate
 * the alarm enums — legacy resolution belongs to the remote boundary alone and is
 * covered by `EnumParsersTest`. If a fallback ever becomes necessary at this
 * layer, something has gone wrong with the migration story.
 */
class MessageEntityAlarmMappingTest {

    private fun timerMessage(style: TimerAlarmStyle, sound: TimerAlarmSound) = Message(
        id = "msg1",
        chatId = "chat1",
        senderId = "user1",
        type = MessageType.TIMER,
        timerDurationMs = 30_000L,
        timerAlarmStyle = style,
        timerAlarmSound = sound,
    )

    @Test
    fun `every style and sound combination round-trips`() {
        for (style in TimerAlarmStyle.entries) {
            for (sound in TimerAlarmSound.entries) {
                val back = MessageEntity.fromDomain(timerMessage(style, sound)).toDomain()
                assertEquals(style, back.timerAlarmStyle)
                assertEquals(sound, back.timerAlarmSound)
            }
        }
    }

    @Test
    fun `stored as enum names`() {
        val entity = MessageEntity.fromDomain(
            timerMessage(TimerAlarmStyle.INSISTENT, TimerAlarmSound.RINGTONE),
        )

        assertEquals("INSISTENT", entity.timerAlarmStyle)
        assertEquals("RINGTONE", entity.timerAlarmSound)
    }

    @Test
    fun `a corrupt row degrades to the defaults instead of crashing`() {
        val entity = MessageEntity(
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
            timerAlarmStyle = "THERMONUCLEAR",
            timerAlarmSound = "AIR_HORN",
        ).toDomain()

        assertEquals(TimerAlarmStyle.DEFAULT, entity.timerAlarmStyle)
        assertEquals(TimerAlarmSound.DEFAULT, entity.timerAlarmSound)
    }

    @Test
    fun `non-timer messages carry the defaults`() {
        val text = Message(id = "m", chatId = "c", senderId = "u", type = MessageType.TEXT)
        val back = MessageEntity.fromDomain(text).toDomain()

        assertEquals(TimerAlarmStyle.NORMAL, back.timerAlarmStyle)
        assertEquals(TimerAlarmSound.ALARM, back.timerAlarmSound)
    }
}
