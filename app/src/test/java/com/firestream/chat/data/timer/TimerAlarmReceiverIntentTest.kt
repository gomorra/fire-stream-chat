package com.firestream.chat.data.timer

import android.content.Context
import android.os.Build
import com.firestream.chat.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression cover for the timer notification's content intent.
 *
 * The bug: the intent carried only chat + sender, so tapping "Timer ended" opened
 * the chat at the newest message instead of scrolling to the timer bubble and
 * flashing it. `ChatScreen`'s deep-link jump keys off
 * [MainActivity.EXTRA_MESSAGE_ID], so that extra is the whole fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    manifest = Config.NONE,
    application = android.app.Application::class,
)
class TimerAlarmReceiverIntentTest {

    private val context: Context = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `open-chat intent carries the message id so the chat jumps to the timer bubble`() {
        val intent = TimerAlarmReceiver.buildOpenChatIntent(
            context = context,
            messageId = "msg-42",
            chatId = "chat-1",
            otherUserId = "user-2",
        )

        assertEquals("msg-42", intent.getStringExtra(MainActivity.EXTRA_MESSAGE_ID))
        assertEquals("chat-1", intent.getStringExtra(MainActivity.EXTRA_CHAT_ID))
        assertEquals("user-2", intent.getStringExtra(MainActivity.EXTRA_SENDER_ID))
    }

    @Test
    fun `message id is still set when the timer has no other user`() {
        val intent = TimerAlarmReceiver.buildOpenChatIntent(
            context = context,
            messageId = "msg-42",
            chatId = "chat-1",
            otherUserId = null,
        )

        assertEquals("msg-42", intent.getStringExtra(MainActivity.EXTRA_MESSAGE_ID))
        assertNull(intent.getStringExtra(MainActivity.EXTRA_SENDER_ID))
    }
}
