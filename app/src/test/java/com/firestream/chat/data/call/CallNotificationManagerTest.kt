package com.firestream.chat.data.call

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
