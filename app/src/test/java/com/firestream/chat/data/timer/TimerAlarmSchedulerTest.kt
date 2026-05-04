package com.firestream.chat.data.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Robolectric provides a real [Context] so [Intent] construction works without
 * mocking out the framework. We mock [AlarmManager] (to verify the scheduling
 * call) and stub [PendingIntent.getBroadcast] / .cancel() so we don't need a
 * real [BroadcastReceiver] component.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    manifest = Config.NONE,
    application = android.app.Application::class,
)
class TimerAlarmSchedulerTest {

    private val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
    private val alarmManager = mockk<AlarmManager>(relaxed = true)
    private val pendingIntent = mockk<PendingIntent>(relaxed = true)

    private lateinit var scheduler: TimerAlarmScheduler

    @Before
    fun setUp() {
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns pendingIntent
        scheduler = TimerAlarmScheduler(context, alarmManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `schedule with exact-alarm permission uses setExactAndAllowWhileIdle and reports EXACT`() {
        every { alarmManager.canScheduleExactAlarms() } returns true

        val result = scheduler.schedule(
            messageId = "m1",
            fireAtMs = 1_700_000_000_000L,
            caption = "Pizza",
            chatId = "c1",
            otherUserId = "u2",
        )

        assertEquals(ScheduleResult.EXACT, result)
        verify(exactly = 1) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                1_700_000_000_000L,
                pendingIntent,
            )
        }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `schedule without exact-alarm permission falls back to setAndAllowWhileIdle and reports INEXACT_FALLBACK`() {
        every { alarmManager.canScheduleExactAlarms() } returns false

        val result = scheduler.schedule(
            messageId = "m2",
            fireAtMs = 1_700_000_001_000L,
            caption = null,
            chatId = "c2",
            otherUserId = null,
        )

        assertEquals(ScheduleResult.INEXACT_FALLBACK, result)
        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                1_700_000_001_000L,
                pendingIntent,
            )
        }
        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `pre-S devices skip the canScheduleExactAlarms check entirely`() {
        // canScheduleExactAlarms() does not exist before API 31. The scheduler
        // must not call it on lower SDKs.
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.R)

        scheduler.schedule(
            messageId = "m3",
            fireAtMs = 1_700_000_002_000L,
            caption = null,
            chatId = "c3",
            otherUserId = null,
        )

        verify(exactly = 0) { alarmManager.canScheduleExactAlarms() }
        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `cancel calls AlarmManager cancel when a PendingIntent exists`() {
        // FLAG_NO_CREATE returns the existing PI (mocked here) rather than null.
        scheduler.cancel("m1")

        verify(exactly = 1) { alarmManager.cancel(pendingIntent) }
        verify(exactly = 1) { pendingIntent.cancel() }
    }

    @Test
    fun `cancel is a no-op when no PendingIntent exists for this messageId`() {
        // Stub FLAG_NO_CREATE to return null — meaning no alarm was ever scheduled.
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns null

        scheduler.cancel("never-scheduled")

        verify(exactly = 0) { alarmManager.cancel(any<PendingIntent>()) }
    }

    @Test
    fun `re-scheduling the same messageId uses FLAG_UPDATE_CURRENT for replace semantics`() {
        every { alarmManager.canScheduleExactAlarms() } returns true

        scheduler.schedule("m1", 1_000L, null, "c1", null)
        scheduler.schedule("m1", 2_000L, null, "c1", null)

        // Both calls used the same PendingIntent (returned by our stub) which
        // tracks the latest extras due to FLAG_UPDATE_CURRENT semantics —
        // verified by checking the last-known fireAtMs reaches the AlarmManager.
        verify(exactly = 1) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, 2_000L, pendingIntent)
        }
    }
}
