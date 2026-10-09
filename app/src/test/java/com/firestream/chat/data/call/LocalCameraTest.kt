package com.firestream.chat.data.call

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * The lifecycle around the capturer, with the capturer itself MockK'd: which camera is opened,
 * what start, stop and flip ask of it, and the order everything is released in. Whether a real
 * camera delivers frames needs a device.
 */
class LocalCameraTest {

    private val context: Context = mockk()
    private val egl: EglBase.Context = mockk()
    private val source: VideoSource = mockk(relaxed = true)
    private val track: VideoTrack = mockk(relaxed = true)
    private val helper: SurfaceTextureHelper = mockk(relaxed = true)
    private val capturer: CameraVideoCapturer = mockk(relaxed = true)
    private val factory: WebRtcPeerConnectionFactory = mockk()
    private val enumerator: CameraEnumerator = mockk()

    private val events = slot<CameraVideoCapturer.CameraEventsHandler>()
    private val flipped = mutableListOf<Boolean>()
    private val failures = mutableListOf<String>()

    private lateinit var camera: LocalCamera

    @Before
    fun setUp() {
        every { factory.eglContext } returns egl
        every { factory.createVideoSource() } returns source
        every { factory.createVideoTrack(source) } returns track
        every { enumerator.deviceNames } returns arrayOf("back", "front")
        every { enumerator.isFrontFacing("front") } returns true
        every { enumerator.isFrontFacing("back") } returns false
        every { enumerator.createCapturer(any(), capture(events)) } returns capturer
        camera = LocalCamera(
            context = context,
            factory = factory,
            enumerator = enumerator,
            newTextureHelper = { helper },
            onFlipped = { flipped += it },
            onFailure = { failures += it }
        )
    }

    // ── Start ────────────────────────────────────────────────────────────────

    @Test
    fun `start opens the front camera first, at 720p and 30 frames`() {
        val started = camera.start()

        assertSame(track, started)
        assertTrue(camera.isFront)
        verifyOrder {
            enumerator.createCapturer("front", any())
            capturer.initialize(helper, context, any())
            capturer.startCapture(1280, 720, 30)
        }
    }

    @Test
    fun `a device without a front camera opens the one it has`() {
        every { enumerator.deviceNames } returns arrayOf("back")

        assertSame(track, camera.start())

        assertFalse(camera.isFront)
        verify { enumerator.createCapturer("back", any()) }
    }

    @Test
    fun `a device without a camera does not start, and builds nothing`() {
        every { enumerator.deviceNames } returns emptyArray()

        assertNull(camera.start())

        verify(exactly = 0) { factory.createVideoSource() }
        verify(exactly = 0) { enumerator.createCapturer(any(), any()) }
    }

    @Test
    fun `a camera that does not open does not start`() {
        every { enumerator.createCapturer(any(), any()) } returns null

        assertNull(camera.start())

        verify(exactly = 0) { capturer.startCapture(any(), any(), any()) }
    }

    @Test
    fun `a capturer that throws does not start, and does not crash the call`() {
        every { capturer.startCapture(any(), any(), any()) } throws RuntimeException("camera in use")

        assertNull(camera.start())
    }

    @Test
    fun `start twice captures once`() {
        camera.start()

        assertSame(track, camera.start())

        verify(exactly = 1) { capturer.startCapture(any(), any(), any()) }
    }

    // ── Stop ─────────────────────────────────────────────────────────────────

    @Test
    fun `stop closes the camera and keeps the track for the next start`() {
        camera.start()

        camera.stop()
        camera.stop()

        verify(exactly = 1) { capturer.stopCapture() }
        verify(exactly = 0) { capturer.dispose() }
        verify(exactly = 0) { track.dispose() }

        // The same track, the same capturer: only the capture starts again.
        assertSame(track, camera.start())
        verify(exactly = 1) { enumerator.createCapturer(any(), any()) }
        verify(exactly = 1) { factory.createVideoTrack(any()) }
        verify(exactly = 2) { capturer.startCapture(1280, 720, 30) }
    }

    @Test
    fun `stop before start does nothing`() {
        camera.stop()

        verify(exactly = 0) { capturer.stopCapture() }
    }

    // ── Flip ─────────────────────────────────────────────────────────────────

    @Test
    fun `flip switches the camera and reports the one now in use`() {
        val handler = slot<CameraVideoCapturer.CameraSwitchHandler>()
        every { capturer.switchCamera(capture(handler)) } returns Unit
        camera.start()

        camera.flip()
        handler.captured.onCameraSwitchDone(false)

        assertFalse(camera.isFront)
        assertEquals(listOf(false), flipped)
    }

    @Test
    fun `a flip that fails keeps the camera it had`() {
        val handler = slot<CameraVideoCapturer.CameraSwitchHandler>()
        every { capturer.switchCamera(capture(handler)) } returns Unit
        camera.start()

        camera.flip()
        handler.captured.onCameraSwitchError("No camera to switch to.")

        assertTrue(camera.isFront)
        assertTrue(flipped.isEmpty())
    }

    @Test
    fun `flip while the camera is stopped does nothing`() {
        camera.flip()
        camera.start()
        camera.stop()
        camera.flip()

        verify(exactly = 0) { capturer.switchCamera(any()) }
    }

    // ── A camera that stops on its own ───────────────────────────────────────

    @Test
    fun `an error, a disconnect and a freeze are reported as failures`() {
        camera.start()

        events.captured.onCameraError("boom")
        events.captured.onCameraDisconnected()
        events.captured.onCameraFreezed("stuck")

        assertEquals(3, failures.size)
    }

    @Test
    fun `opening, the first frame and closing are not failures`() {
        camera.start()

        events.captured.onCameraOpening("front")
        events.captured.onFirstFrameAvailable()
        events.captured.onCameraClosed()

        assertTrue(failures.isEmpty())
    }

    // ── Dispose ──────────────────────────────────────────────────────────────

    @Test
    fun `dispose releases the capturer, the texture helper, the source and the track, in that order`() {
        camera.start()

        camera.dispose()

        verifyOrder {
            capturer.dispose()
            helper.dispose()
            source.dispose()
            track.dispose()
        }
    }

    @Test
    fun `dispose twice releases once`() {
        camera.start()

        camera.dispose()
        camera.dispose()

        verify(exactly = 1) { capturer.dispose() }
        verify(exactly = 1) { helper.dispose() }
        verify(exactly = 1) { source.dispose() }
        verify(exactly = 1) { track.dispose() }
    }

    @Test
    fun `a camera that never started has nothing to release`() {
        camera.dispose()

        verify(exactly = 0) { capturer.dispose() }
        verify(exactly = 0) { helper.dispose() }
    }

    @Test
    fun `nothing starts after dispose`() {
        camera.dispose()

        assertNull(camera.start())
        camera.flip()

        verify(exactly = 0) { enumerator.createCapturer(any(), any()) }
        verify(exactly = 0) { factory.createVideoSource() }
    }
}
