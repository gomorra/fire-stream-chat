// region: AGENT-NOTE
// Responsibility: The bridge between a call's video tracks and the views that draw them. Hands
//   out a ready `View` per participant, keeps each view on that participant's current track,
//   and reports who has delivered a first frame.
// Owns: Which track belongs to which participant, every live video view, the first-frame set.
// Collaborators: CallService (opens it with the call's EGL context, sets and drops tracks, closes
//   it before anything is disposed), the call screens (createView / releaseView),
//   WebRtcPeerConnectionFactory (the EGL context).
// Don't put here: Compose, layout, or which tile shows when (ui/call); camera capture
//   (LocalCamera); negotiation (PeerSession). No WebRTC type leaves through the public API
//   except the two CallService passes in.
// endregion

package com.firestream.chat.data.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the video of the running call meets the screen. The service gives it tracks, a screen asks
 * it for views, and neither knows the other.
 *
 * A participant can have several views at once, one per screen that shows the call. Each view is
 * released on its own, by [releaseView] or by leaving its window. A view is single-use: once it has
 * left its window it draws nothing, so a screen that shows the participant again asks for a new one.
 * A view belongs to the call it was created in; [close] lets go of every view.
 *
 * Threading: [createView] and [releaseView] run on the main thread. Everything else may come from
 * any thread. Frames arrive on WebRTC's own threads and take no lock here, because [lock] is held
 * while a sink is added to or removed from a track, and those calls wait for WebRTC's threads.
 *
 * @param newRenderer makes one video view.
 * @param onMain runs a block on the main thread, later. A view is given its EGL context there.
 */
@Singleton
class CallVideoSinks internal constructor(
    private val newRenderer: (Context) -> VideoTextureViewRenderer,
    private val onMain: (() -> Unit) -> Unit
) {

    @Inject constructor() : this(
        newRenderer = { VideoTextureViewRenderer(it) },
        onMain = { block -> Handler(Looper.getMainLooper()).post(block) }
    )

    private val lock = Any()

    // Guarded by [lock].
    private var eglContext: EglBase.Context? = null
    private val feeds = mutableMapOf<String, Feed>()
    private val views = mutableMapOf<VideoTextureViewRenderer, Tile>()
    private var localMirrored = true

    private val _framed = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The participants whose current track has delivered a frame since it was set, or since the
     * last [awaitFrame]. A screen shows the video of a participant only while they are in here.
     */
    val framed: StateFlow<Set<String>> = _framed.asStateFlow()

    /** A call begins: views are drawn on [eglContext] from now on, the ones made before included. */
    fun open(eglContext: EglBase.Context) {
        synchronized(lock) {
            if (this.eglContext === eglContext) return
            this.eglContext = eglContext
        }
        onMain {
            synchronized(lock) {
                val egl = this.eglContext ?: return@synchronized
                views.forEach { (renderer, tile) -> if (!tile.initialised) initialise(renderer, tile, egl) }
            }
        }
    }

    /**
     * [track] is what [participantId] sends now. Null means they send nothing. Every view of that
     * participant moves to the new track, and their first frame counts from here.
     */
    fun setTrack(participantId: String, track: VideoTrack?) {
        synchronized(lock) {
            val previous = feeds[participantId]
            if (previous?.track === track) return
            if (previous != null) dropFeed(participantId, previous)
            if (track == null) return
            val probe = FrameProbe(participantId)
            feeds[participantId] = Feed(track, probe)
            track.addSink(probe)
            viewsOf(participantId).forEach { track.addSink(it) }
        }
    }

    /**
     * Forget that [participantId] delivered a frame, and report the next one. For a camera that
     * was switched off and on again: the track stays the same, and the picture left in a view is
     * old.
     */
    fun awaitFrame(participantId: String) {
        synchronized(lock) {
            // Out of the set first: a frame that lands in between is then reported, not lost.
            _framed.update { it - participantId }
            feeds[participantId]?.probe?.rearm()
        }
    }

    /** The self view shows the front camera mirrored, the back camera as it is. */
    fun setLocalMirrored(mirrored: Boolean) {
        synchronized(lock) {
            if (localMirrored == mirrored) return
            localMirrored = mirrored
            viewsOf(LOCAL).forEach { it.setMirror(mirrored) }
        }
    }

    /**
     * A view that draws the video of [participantId], or of the own camera for [LOCAL]. It follows
     * that participant's track as it comes, changes and goes. It fills the bounds it is given and
     * crops the picture to them. The caller adds it to a window and calls [releaseView] when it is
     * done with it.
     */
    fun createView(context: Context, participantId: String): View {
        val renderer = newRenderer(context)
        // Without it the view measures itself smaller than its bounds to keep more of the picture.
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        synchronized(lock) {
            val tile = Tile(participantId)
            views[renderer] = tile
            eglContext?.let { initialise(renderer, tile, it) }
            if (participantId == LOCAL) renderer.setMirror(localMirrored)
            feeds[participantId]?.track?.addSink(renderer)
        }
        renderer.addOnAttachStateChangeListener(releaseOnDetach)
        return renderer
    }

    /** Take [view] off its track and forget it. A second call, or a view made elsewhere, is fine. */
    fun releaseView(view: View) {
        val renderer = view as? VideoTextureViewRenderer ?: return
        synchronized(lock) {
            val tile = views.remove(renderer) ?: return
            feeds[tile.participantId]?.track?.removeSink(renderer)
        }
        renderer.removeOnAttachStateChangeListener(releaseOnDetach)
    }

    /**
     * The call is over: every view lets go of its track, and every track is forgotten. Call this
     * before a track or a connection is disposed. A disposed track throws when a sink is removed
     * from it, and a connection's threads are gone once the factory is.
     */
    fun close() {
        synchronized(lock) {
            feeds.toMap().forEach { (participantId, feed) -> dropFeed(participantId, feed) }
            views.clear()
            eglContext = null
            localMirrored = true
        }
    }

    /** Caller holds [lock]. Takes the probe and every view off the track, and forgets the track. */
    private fun dropFeed(participantId: String, feed: Feed) {
        feed.probe.retire()
        feed.track.removeSink(feed.probe)
        viewsOf(participantId).forEach { feed.track.removeSink(it) }
        feeds.remove(participantId)
        _framed.update { it - participantId }
    }

    /** Caller holds [lock] and is on the main thread. */
    private fun initialise(renderer: VideoTextureViewRenderer, tile: Tile, egl: EglBase.Context) {
        renderer.init(egl, NoRendererEvents)
        tile.initialised = true
        // A surface that came before the EGL context was dropped by the renderer. Hand it over again.
        if (renderer.isAvailable) {
            renderer.surfaceTexture?.let { renderer.onSurfaceTextureAvailable(it, renderer.width, renderer.height) }
        }
    }

    /** Caller holds [lock]. */
    private fun viewsOf(participantId: String): List<VideoTextureViewRenderer> =
        views.filterValues { it.participantId == participantId }.keys.toList()

    private val releaseOnDetach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {}

        // The renderer has released its EGL surface by now and will not draw again.
        override fun onViewDetachedFromWindow(view: View) = releaseView(view)
    }

    private class Tile(val participantId: String) {
        var initialised = false
    }

    /** What a participant sends now, and the probe that reports its first frame. */
    private class Feed(val track: VideoTrack, val probe: FrameProbe)

    /** Sits on a track beside the views and reports its first frame. Takes no lock. */
    private inner class FrameProbe(private val participantId: String) : VideoSink {
        private val waiting = AtomicBoolean(true)

        override fun onFrame(frame: VideoFrame) {
            if (waiting.compareAndSet(true, false)) _framed.update { it + participantId }
        }

        fun rearm() = waiting.set(true)

        /** The probe is coming off its track. A frame still on its way reports nothing. */
        fun retire() = waiting.set(false)
    }

    private object NoRendererEvents : RendererCommon.RendererEvents {
        override fun onFirstFrameRendered() {}
        override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
    }

    companion object {
        /** The participant id of the own camera. */
        const val LOCAL = "local"
    }
}
