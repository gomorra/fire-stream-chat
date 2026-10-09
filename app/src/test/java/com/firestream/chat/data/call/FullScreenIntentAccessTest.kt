package com.firestream.chat.data.call

import android.app.Application
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FullScreenIntentAccessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Robolectric's NotificationManager shadow has no switch for this access, so the system's
    // answer comes from a mock behind the context.
    private val notificationManager = mockk<NotificationManager>()
    private val contextWithManager = object : ContextWrapper(context) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.NOTIFICATION_SERVICE) notificationManager else super.getSystemService(name)
    }

    @Test
    @Config(sdk = [33])
    fun `below Android 14 the access is always granted, and the system is not asked`() {
        // The method does not exist on this version, so asking would throw.
        assertTrue(FullScreenIntentAccess(contextWithManager).isGranted())
    }

    @Test
    @Config(sdk = [34])
    fun `on Android 14 the access is what the system says`() {
        val access = FullScreenIntentAccess(contextWithManager)

        every { notificationManager.canUseFullScreenIntent() } returns false
        assertFalse(access.isGranted())

        every { notificationManager.canUseFullScreenIntent() } returns true
        assertTrue(access.isGranted())
    }

    @Test
    @Config(sdk = [34])
    fun `the settings intent opens the app's own page from any context`() {
        val intent = FullScreenIntentAccess(context).settingsIntent()

        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, intent.action)
        assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    @Config(sdk = [34])
    fun `opening the settings starts that page`() {
        FullScreenIntentAccess(context).openSettings()

        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, started.action)
    }

    @Test
    @Config(sdk = [34])
    fun `a device without the page gets the app's notification settings`() {
        val started = mutableListOf<Intent>()
        val withoutThePage = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                if (intent.action == Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) {
                    throw ActivityNotFoundException()
                }
                started += intent
            }
        }

        FullScreenIntentAccess(withoutThePage).openSettings()

        val fallback = started.single()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, fallback.action)
        assertEquals(context.packageName, fallback.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(fallback.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    @Config(sdk = [34])
    fun `a device with neither page does not crash on the tap`() {
        val withoutAnyPage = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent): Unit = throw ActivityNotFoundException()
        }

        FullScreenIntentAccess(withoutAnyPage).openSettings()
    }
}
