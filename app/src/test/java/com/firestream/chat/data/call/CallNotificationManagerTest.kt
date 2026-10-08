package com.firestream.chat.data.call

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.firestream.chat.ui.call.CallActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CallNotificationManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val systemNotifications = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `an incoming call rings and vibrates on the ringtone stream`() {
        val notification = CallNotificationManager(context).buildIncomingCallNotification("Alice")

        val channel = systemNotifications.getNotificationChannel(notification.channelId)
        assertNotNull("incoming-call channel exists", channel)
        assertNotNull("incoming-call channel has a sound", channel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, channel.audioAttributes.usage)
        assertTrue("incoming-call channel vibrates", channel.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `an incoming call keeps ringing until its notification is replaced or removed`() {
        val notification = CallNotificationManager(context).buildIncomingCallNotification("Alice")

        assertTrue(notification.flags and Notification.FLAG_INSISTENT != 0)
    }

    @Test
    fun `the silent incoming-call channel from earlier versions is removed`() {
        // Android freezes a channel's sound when it is created, so an upgrade must replace it.
        systemNotifications.createNotificationChannel(
            NotificationChannel("fire_stream_incoming_calls", "Incoming Calls", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) }
        )

        CallNotificationManager(context)

        assertNull(systemNotifications.getNotificationChannel("fire_stream_incoming_calls"))
    }

    @Test
    fun `an ongoing call stays silent`() {
        val notification = CallNotificationManager(context).buildOngoingCallNotification("Alice")

        val channel = systemNotifications.getNotificationChannel(notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
    }

    @Test
    fun `the fallback ring rings until the call would have timed out`() {
        val notification = fallbackRing()

        val channel = systemNotifications.getNotificationChannel(notification.channelId)
        assertNotNull("fallback ring has a sound", channel.sound)
        assertTrue(notification.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(CallSession.RING_TIMEOUT_MS, notification.timeoutAfter)
    }

    @Test
    fun `the fallback ring opens the call screen to ring the call it names`() {
        val notification = fallbackRing()

        for (pending in listOf(notification.contentIntent, notification.fullScreenIntent)) {
            val intent = shadowOf(pending).savedIntent
            assertEquals(CallActivity::class.java.name, intent.component?.className)
            assertEquals(CallActivity.ACTION_RING, intent.getStringExtra(CallActivity.EXTRA_ACTION))
            assertEquals("call1", intent.getStringExtra(CallActivity.EXTRA_CALL_ID))
            assertEquals("u2", intent.getStringExtra(CallActivity.EXTRA_CALLER_ID))
            assertEquals("Alice", intent.getStringExtra(CallActivity.EXTRA_CALLER_NAME))
        }
    }

    @Test
    fun `the fallback ring declines the call it names`() {
        val decline = fallbackRing().actions.single { it.title == "Decline" }

        val intent = shadowOf(decline.actionIntent).savedIntent
        assertEquals(CallService::class.java.name, intent.component?.className)
        assertEquals(CallService.ACTION_DECLINE, intent.action)
        assertEquals("call1", intent.getStringExtra(CallService.EXTRA_CALL_ID))
    }

    @Test
    fun `the service's own ring declines the call the service holds`() {
        val notification = CallNotificationManager(context).buildIncomingCallNotification("Alice")
        val decline = notification.actions.single { it.title == "Decline" }

        val intent = shadowOf(decline.actionIntent).savedIntent
        assertEquals(CallService.ACTION_DECLINE, intent.action)
        assertNull(intent.getStringExtra(CallService.EXTRA_CALL_ID))
        // The two Declines are separate PendingIntents: the fallback's call id must not reach it.
        val fallbackDecline = fallbackRing().actions.single { it.title == "Decline" }
        assertNotEquals(fallbackDecline.actionIntent, decline.actionIntent)
    }

    private fun fallbackRing(): Notification =
        CallNotificationManager(context).buildIncomingCallFallbackNotification("call1", "u2", "Alice", null)
}
