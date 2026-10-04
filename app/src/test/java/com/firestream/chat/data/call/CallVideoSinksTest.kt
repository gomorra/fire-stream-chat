package com.firestream.chat.data.call

import android.content.Context
import android.graphics.SurfaceTexture
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * Plain JUnit, no Robolectric and no native library: the renderer views and the tracks are
 * MockK'd, and blocks for the main thread run at once.
 */
class CallVideoSinksTest {

    private val context: Context = mockk()
    private val egl: EglBase.Context = mockk()
    private val made = mutableListOf<VideoTextureViewRenderer>()

    private val sinks = CallVideoSinks(
        newRenderer = { mockk<VideoTextureViewRenderer>(relaxed = true).also { made += it } },
        onMain = { it() }
    )

    private val remote = "user2"

    /** A track that remembers its sinks, as the real one does. */
    private class FakeTrack {
        val attached = mutableListOf<VideoSink>()
        val track: VideoTrack = mockk {
            every { addSink(any()) } answers { attached += firstArg<VideoSink>() }
            every { removeSink(any()) } answers { attached -= firstArg<VideoSink>() }
        }

        /** Deliver a frame the way WebRTC does: to every sink on the track. */
        fun frame() = attached.toList().forEach { it.onFrame(mockk<VideoFrame>(relaxed = true)) }

        fun draws(view: Any): Boolean = attached.any { it === view }
    }

    // ── Bind ─────────────────────────────────────────────────────────────────

    @Test
    fun `a view is drawn on the call's EGL context and bound to the participant's track`() {
        val video = FakeTrack()
        sinks.open(egl)
        sinks.setTrack(remote, video.track)

        val view = sinks.createView(context, remote)

        assertSame(made.single(), view)
        verify(exactly = 1) { made.single().init(egl, any()) }
        assertTrue(video.draws(view))
    }

    @Test
    fun `a view created before the track arrives is bound when it does`() {
        val video = FakeTrack()
        sinks.open(egl)
        val view = sinks.createView(context, remote)

        sinks.setTrack(remote, video.track)

        assertTrue(video.draws(view))
    }

    @Test
    fun `a view only follows its own participant`() {
        val video = FakeTrack()
        sinks.open(egl)
        val other = sinks.createView(context, "user3")

        sinks.setTrack(remote, video.track)

        assertFalse(video.draws(other))
    }

    @Test
    fun `a participant can have two views, and each is released on its own`() {
        val video = FakeTrack()
        sinks.open(egl)
        sinks.setTrack(remote, video.track)
        val stage = sinks.createView(context, remote)
        val card = sinks.createView(context, remote)
        assertTrue(video.draws(stage) && video.draws(card))

        sinks.releaseView(stage)

        assertFalse(video.draws(stage))
        assertTrue(video.draws(card))
    }

    @Test
    fun `releasing a view twice, or one made elsewhere, does nothing`() {
        val video = FakeTrack()
        sinks.open(egl)
        sinks.setTrack(remote, video.track)
        val view = sinks.createView(context, remote)

        sinks.releaseView(view)
        sinks.releaseView(view)
        sinks.releaseView(mockk<VideoTextureViewRenderer>(relaxed = true))

        verify(exactly = 1) { video.track.removeSink(view as VideoSink) }
    }

    @Test
    fun `a view that leaves its window releases itself`() {
        val video = FakeTrack()
        sinks.open(egl)
        sinks.setTrack(remote, video.track)
        val view = sinks.createView(context, remote)
        val listeners = mutableListOf<android.view.View.OnAttachStateChangeListener>()
        verify { made.single().addOnAttachStateChangeListener(capture(listeners)) }

        listeners.single().onViewDetachedFromWindow(view)

        assertFalse(video.draws(view))
    }

    // ── Rebind ───────────────────────────────────────────────────────────────

    @Test
    fun `a new track takes every view over from the old one`() {
        val old = FakeTrack()
        val new = FakeTrack()
        sinks.open(egl)
        sinks.setTrack(remote, old.track)
        val view = sinks.createView(context, remote)

        sinks.setTrack(remote, new.track)

        assertTrue(old.attached.isEmpty())
        assertTrue(new.draws(view))
    }

    @Test
    fun `setting the same track again changes nothing`() {
        val video = FakeTrack()
        sinks.open(egl)
        sinks.createView(context, remote)
        sinks.setTrack(remote, video.track)
        val before = video.attached.toList()

        sinks.setTrack(remote, video.track)

        assertEquals(before, video.attached)
        verify(exactly = 0) { video.track.removeSink(any()) }
    }

    @Test
    fun `a track that goes away leaves its views unbound`() {
        val video = FakeTrack()
        sinks.open(egl)
        sinks.setTrack(remote, video.track)
        val view = sinks.createView(context, remote)

        sinks.setTrack(remote, null)
        sinks.releaseView(view)

        assertTrue(video.attached.isEmpty())
        // Released after the track went: nothing is left to remove the view from.
        verify(exactly = 1) { video.track.removeSink(view as VideoSink) }
    }

    // ── First frames ─────────────────────────────────────────────────────────

    @Test
    fun `a participant has a frame once their track delivers one, with or without a view`() {
        val video = FakeTrack()
        sinks.setTrack(remote, video.track)
        assertTrue(sinks.framed.value.isEmpty())

        video.frame()
        video.frame()

        assertEquals(setOf(remote), sinks.framed.value)
    }

    @Test
    fun `a new track starts without a frame`() {
        val old = FakeTrack()
        val new = FakeTrack()
        sinks.setTrack(remote, old.track)
        old.frame()

        sinks.setTrack(remote, new.track)

        assertTrue(sinks.framed.value.isEmpty())
        new.frame()
        assertEquals(setOf(remote), sinks.framed.value)
    }

    @Test
    fun `awaitFrame forgets the frame and reports the next one`() {
        val video = FakeTrack()
        sinks.setTrack(remote, video.track)
        video.frame()

        sinks.awaitFrame(remote)
        assertTrue(sinks.framed.value.isEmpty())

        video.frame()
        assertEquals(setOf(remote), sinks.framed.value)
    }

    @Test
    fun `frames are counted per participant`() {
        val theirs = FakeTrack()
        val mine = FakeTrack()
        sinks.setTrack(remote, theirs.track)
        sinks.setTrack(CallVideoSinks.LOCAL, mine.track)

        mine.frame()

        assertEquals(setOf(CallVideoSinks.LOCAL), sinks.framed.value)
    }

    // ── The self view ────────────────────────────────────────────────────────

    @Test
    fun `the self view is mirrored for the front camera and plain for the back camera`() {
        sinks.open(egl)
        val self = made.also { sinks.createView(context, CallVideoSinks.LOCAL) }.single()
        verify { self.setMirror(true) }

        sinks.setLocalMirrored(false)
        verify { self.setMirror(false) }
    }

    @Test
    fun `a self view made after a flip to the back camera is not mirrored`() {
        sinks.open(egl)
        sinks.setLocalMirrored(false)

        sinks.createView(context, CallVideoSinks.LOCAL)

        verify { made.single().setMirror(false) }
        verify(exactly = 0) { made.single().setMirror(true) }
    }

    @Test
    fun `the view of someone else is never mirrored`() {
        sinks.open(egl)
        sinks.createView(context, remote)

        sinks.setLocalMirrored(false)

        verify(exactly = 0) { made.single().setMirror(any()) }
    }

    @Test
    fun `the next call's self view starts mirrored again`() {
        sinks.open(egl)
        sinks.setLocalMirrored(false)
        sinks.close()

        sinks.open(egl)
        sinks.createView(context, CallVideoSinks.LOCAL)

        verify { made.single().setMirror(true) }
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    @Test
    fun `a view fills the bounds it is given and crops the picture`() {
        sinks.createView(context, remote)

        verify { made.single().setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL) }
    }

    // ── EGL context ──────────────────────────────────────────────────────────

    @Test
    fun `a view made before the call's EGL context exists is initialised when it does`() {
        val view = made.also { sinks.createView(context, remote) }.single()
        verify(exactly = 0) { view.init(any(), any()) }

        sinks.open(egl)
        sinks.open(egl)

        verify(exactly = 1) { view.init(egl, any()) }
    }

    @Test
    fun `a view whose surface came before the EGL context is handed its surface again`() {
        val surface: SurfaceTexture = mockk()
        val view = made.also { sinks.createView(context, remote) }.single()
        every { view.isAvailable } returns true
        every { view.surfaceTexture } returns surface
        every { view.width } returns 320
        every { view.height } returns 240

        sinks.open(egl)

        verifyOrder {
            view.init(egl, any())
            view.onSurfaceTextureAvailable(surface, 320, 240)
        }
    }

    // ── Close: before anything is disposed ───────────────────────────────────

    @Test
    fun `close takes every sink off every track`() {
        val theirs = FakeTrack()
        val mine = FakeTrack()
        sinks.open(egl)
        sinks.setTrack(remote, theirs.track)
        sinks.setTrack(CallVideoSinks.LOCAL, mine.track)
        sinks.createView(context, remote)
        sinks.createView(context, CallVideoSinks.LOCAL)
        theirs.frame()

        sinks.close()

        assertTrue(theirs.attached.isEmpty())
        assertTrue(mine.attached.isEmpty())
        assertTrue(sinks.framed.value.isEmpty())
    }

    @Test
    fun `after close no track is touched again — it may be disposed by then`() {
        val video = FakeTrack()
        sinks.open(egl)
        sinks.setTrack(remote, video.track)
        val view = sinks.createView(context, remote)
        sinks.close()
        // What the service does next. A disposed track throws on every call.
        every { video.track.addSink(any()) } throws IllegalStateException("MediaStreamTrack has been disposed.")
        every { video.track.removeSink(any()) } throws IllegalStateException("MediaStreamTrack has been disposed.")

        sinks.releaseView(view)
        sinks.awaitFrame(remote)
        sinks.setTrack(remote, null)
        sinks.createView(context, remote)
        sinks.close()
    }

    @Test
    fun `a frame still on its way when its track is dropped reports nothing`() {
        val video = FakeTrack()
        sinks.setTrack(remote, video.track)
        val probe = video.attached.single()

        sinks.close()
        probe.onFrame(mockk<VideoFrame>(relaxed = true))

        assertTrue(sinks.framed.value.isEmpty())
    }

    @Test
    fun `a view of the call before is not bound to the tracks of the next call`() {
        val next = FakeTrack()
        sinks.open(egl)
        val stale = sinks.createView(context, remote)
        sinks.close()

        sinks.open(mockk())
        sinks.setTrack(remote, next.track)

        assertFalse(next.draws(stale))
    }
}
