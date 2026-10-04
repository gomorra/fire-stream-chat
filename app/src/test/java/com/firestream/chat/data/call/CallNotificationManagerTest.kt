package com.firestream.chat.data.call

import android.app.Notification
import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The ring names the kind of call the caller started. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    manifest = Config.NONE,
    application = android.app.Application::class,
)
class CallNotificationManagerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = CallNotificationManager(context)

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test
    fun `a video call rings as an incoming video call`() {
        val ring = manager.buildIncomingCallNotification("Alice", video = true)

        assertEquals("Incoming Video Call", ring.title())
        assertEquals("Alice", ring.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `a voice call rings as an incoming voice call`() {
        val ring = manager.buildIncomingCallNotification("Alice", video = false)

        assertEquals("Incoming Voice Call", ring.title())
    }

    @Test
    fun `the notifications of a running call name its kind`() {
        assertEquals("Video Call", manager.buildOutgoingCallNotification("Alice", video = true).title())
        assertEquals("Voice Call", manager.buildOutgoingCallNotification("Alice", video = false).title())
        assertEquals("Video Call", manager.buildOngoingCallNotification("Alice", video = true).title())
        assertEquals("Voice Call", manager.buildOngoingCallNotification("Alice", video = false).title())
    }

    // The service posts the ring again when the call document names the kind after the push did
    // not. The second post must not alert a second time.
    @Test
    fun `the ring alerts only once`() {
        val ring = manager.buildIncomingCallNotification("Alice", video = true)

        assertTrue(ring.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }
}
