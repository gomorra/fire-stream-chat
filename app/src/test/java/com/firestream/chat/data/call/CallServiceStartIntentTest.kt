package com.firestream.chat.data.call

import android.app.Application
import android.content.Intent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The intent that starts an outgoing call carries two facts that are easy to swap: how the call was
 * started, and whether the callee's app takes a video line. An app without video crashes on an offer
 * with a video line, so the second one must never be true by accident.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    manifest = Config.NONE,
    application = Application::class,
)
class CallServiceStartIntentTest {

    private val application: Application = RuntimeEnvironment.getApplication()

    private fun outgoing(video: Boolean, videoLine: Boolean): Intent {
        CallService.startOutgoing(
            application, "call1", "chat1", "callee1", "Callee", null, video = video, videoLine = videoLine
        )
        return shadowOf(application).nextStartedService
    }

    @Test
    fun `a video call to an app without video carries the kind and no video line`() {
        val intent = outgoing(video = true, videoLine = false)

        assertEquals(CallService.ACTION_START_OUTGOING, intent.action)
        assertTrue(intent.getBooleanExtra(CallService.EXTRA_VIDEO, false))
        assertFalse(intent.getBooleanExtra(CallService.EXTRA_VIDEO_LINE, true))
    }

    @Test
    fun `a voice call to an app with video carries the video line`() {
        val intent = outgoing(video = false, videoLine = true)

        assertFalse(intent.getBooleanExtra(CallService.EXTRA_VIDEO, true))
        assertTrue(intent.getBooleanExtra(CallService.EXTRA_VIDEO_LINE, false))
    }

    // An intent from anywhere else has no such extra, and the service reads that as no video line.
    @Test
    fun `an incoming call's intent says nothing about a video line`() {
        CallService.startIncoming(application, "call1", "caller1", "Caller", null, video = true)

        val intent = shadowOf(application).nextStartedService
        assertFalse(intent.hasExtra(CallService.EXTRA_VIDEO_LINE))
    }
}
