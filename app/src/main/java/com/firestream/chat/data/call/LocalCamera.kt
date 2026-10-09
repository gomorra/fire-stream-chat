// region: AGENT-NOTE
// Responsibility: The call's own camera — pick a device (front first), capture into one video
//   track, stop, flip, and release everything in the right order.
// Owns: The capturer, its texture helper, the video source and the video track of one call.
// Collaborators: WebRtcPeerConnectionFactory (source, track, EGL context), WebRtcCallLocalMedia
//   (calls it on its worker, and hands the track to the sessions and to CallVideoSinks),
//   CameraSwitch (decides when the camera runs).
// Don't put here: Permission checks or the foreground type (CameraSwitch, CallService),
//   attaching the track to a connection (PeerSession.setCamera), views (CallVideoSinks).
// endregion

package com.firestream.chat.data.call

import android.content.Context
import android.util.Log
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The camera of one call. Lifecycle is n × ([start] → [stop]) → [dispose].
 *
 * It needs only the factory, not a connection, so a preview can run while the call rings.
 *
 * Threading: **never call this on the main thread.** [stop] waits for a camera that is still
 * opening, and [dispose] waits until the device is closed. Every function takes [lock], so calls
 * from two threads run one after the other, and [dispose] latches: a [start] that arrives late
 * does nothing.
 *
 * [onFlipped] and [onFailure] run on the camera thread. They must not wait for a thread that is
 * inside a function of this class, because that function may be waiting for the camera thread.
 *
 * @param factory makes the source and the track, and its EGL context carries the frames.
 * @param enumerator lists the cameras of the device and opens one.
 * @param newTextureHelper makes the capture thread. It answers null when the EGL context is gone.
 * @param onFlipped the camera now in use, after a [flip]: true for the front camera.
 * @param onFailure the camera stopped on its own: it failed, froze, or another app took it.
 */
class LocalCamera(
    private val context: Context,
    private val factory: WebRtcPeerConnectionFactory,
    private val enumerator: CameraEnumerator = Camera2Enumerator(context),
    private val newTextureHelper: (EglBase.Context) -> SurfaceTextureHelper? =
        { SurfaceTextureHelper.create(CAPTURE_THREAD, it) },
    private val onFlipped: (front: Boolean) -> Unit = {},
    private val onFailure: (reason: String) -> Unit = {}
) {

    private val lock = Any()

    // Guarded by [lock]. Made by the first start() and kept until dispose().
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var capturer: CameraVideoCapturer? = null
    private var capturing = false
    private var disposed = false

    /** True while the front camera is the one in use. Before the first [start] it is the guess. */
    @Volatile
    var isFront: Boolean = true
        private set

    /**
     * Open the camera and capture into the track. Does nothing new when it is already capturing.
     *
     * @return the track the frames go into, the same one for the life of this camera. Null when
     *   the camera cannot run: it was disposed, or the device has no camera that opens.
     */
    fun start(): VideoTrack? = synchronized(lock) {
        if (disposed) return null
        if (capturing) return track
        try {
            val capturer = capturer ?: openCapturer() ?: return null
            capturer.startCapture(WIDTH, HEIGHT, FPS)
            capturing = true
            track
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not start the camera", e)
            null
        }
    }

    /**
     * Stop capturing and close the camera device, so the system's camera indicator goes out. The
     * track stays, and [start] opens the camera again.
     */
    fun stop() {
        synchronized(lock) {
            if (!capturing) return
            capturing = false
            stopCapture()
        }
    }

    /** Switch between the front and the back camera. Does nothing while the camera is stopped. */
    fun flip() {
        synchronized(lock) {
            if (!capturing) return
            capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    isFront = isFrontCamera
                    onFlipped(isFrontCamera)
                }

                override fun onCameraSwitchError(error: String?) {
                    Log.w(TAG, "Could not switch the camera: $error")
                }
            })
        }
    }

    /**
     * Release everything, in this order: capturer, texture helper, source, track. A second call
     * does nothing, and neither does any later [start].
     *
     * Whoever holds the track must let go of it first: a connection it is attached to, and
     * [CallVideoSinks].
     */
    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            capturing = false
            // Stops capturing by itself.
            capturer?.dispose()
            capturer = null
            textureHelper?.let { helper ->
                awaitCaptureThread(helper)
                helper.dispose()
            }
            textureHelper = null
            source?.dispose()
            source = null
            track?.dispose()
            track = null
        }
    }

    /**
     * The capturer closes the camera device on the capture thread, as a task it queues when it
     * stops. Wait until that thread has worked its queue off, so the close has run before the
     * thread is told to quit.
     */
    private fun awaitCaptureThread(helper: SurfaceTextureHelper) {
        val idle = CountDownLatch(1)
        if (!helper.handler.post { idle.countDown() }) return
        try {
            idle.await(CAMERA_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Caller holds [lock]. Makes the source, the track, the capture thread and the capturer. */
    private fun openCapturer(): CameraVideoCapturer? {
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
        if (name == null) {
            Log.w(TAG, "This device has no camera")
            return null
        }
        val helper = textureHelper ?: newTextureHelper(factory.eglContext)?.also { textureHelper = it }
        if (helper == null) {
            Log.e(TAG, "No capture thread")
            return null
        }
        val source = source ?: factory.createVideoSource().also { source = it }
        if (track == null) track = factory.createVideoTrack(source)

        val opened = enumerator.createCapturer(name, events)
        if (opened == null) {
            Log.e(TAG, "Camera $name did not open")
            return null
        }
        opened.initialize(helper, context, source.capturerObserver)
        isFront = enumerator.isFrontFacing(name)
        capturer = opened
        return opened
    }

    /** Caller holds [lock]. Returns once the close of the camera device is queued. */
    private fun stopCapture() {
        try {
            capturer?.stopCapture()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private val events = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) = onFailure("error: $error")
        override fun onCameraDisconnected() = onFailure("disconnected")
        override fun onCameraFreezed(error: String?) = onFailure("frozen: $error")
        override fun onCameraOpening(cameraName: String?) {}
        override fun onFirstFrameAvailable() {}
        override fun onCameraClosed() {}
    }

    private companion object {
        const val TAG = "LocalCamera"
        const val CAPTURE_THREAD = "CallCamera"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 30
        const val CAMERA_CLOSE_TIMEOUT_MS = 2_000L
    }
}
