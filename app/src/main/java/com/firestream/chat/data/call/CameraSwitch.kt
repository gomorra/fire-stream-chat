package com.firestream.chat.data.call

/**
 * Whether the own camera of one call runs. The user switches it, and four things hold it back:
 * the `CAMERA` permission, a call without an agreed video line, a call that no screen shows, and
 * a foreground type the system refuses.
 *
 * - **On** is the user's switch. It goes on only with the permission and a video line.
 * - **Running** is on and on screen. Off screen the camera pauses, and it resumes with the screen.
 * - The foreground type follows *on*, not *running*. It is set from the screen the user tapped
 *   on, while the app is visible. A refused type switches the camera off, and the call goes on.
 *
 * Main thread only, like the [CallSession] that owns it. It is a plain class so the rule has a
 * test of its own.
 */
internal class CameraSwitch(private val port: Port) {

    /** What the switch reads and drives. [CallSession] provides it. */
    interface Port {
        fun hasCameraPermission(): Boolean

        /**
         * Name the camera in the service's foreground type, or drop it again.
         *
         * @return false when the system refused to name the camera.
         */
        fun setForegroundCamera(camera: Boolean): Boolean

        /** Start or stop capturing. */
        fun setCameraRunning(running: Boolean)

        /** What the screens show of the switch. [paused] is on, but not running. */
        fun onSwitchChanged(on: Boolean, paused: Boolean)
    }

    /** The user's switch. It stays on while the camera is paused. */
    var isOn = false
        private set

    /** The camera is asked to capture: the switch is on and a screen shows the call. */
    var isRunning = false
        private set

    private var onScreen = false

    /** False once the call is known to have no video line both sides agreed on. */
    private var videoLine = true

    /** Something has decided the camera once. The preview of a ring is offered only before that. */
    private var decided = false

    /** The user switched the camera, or answered with or without video. */
    fun set(on: Boolean) {
        decided = true
        switch(on)
    }

    /**
     * A screen shows the call, or none has for a moment. The first screen to show the call
     * switches the camera on when [preview] is true: a ring of a video call shows the own camera.
     * A switch that was set before that screen came is left as it is.
     */
    fun onScreen(showing: Boolean, preview: Boolean) {
        onScreen = showing
        if (showing && !decided) {
            decided = true
            if (preview) return switch(true)
        }
        apply()
    }

    /**
     * The call has a video line both sides agreed on, or it has none. Without one the camera goes
     * off: a preview that went on running would look like a camera that is being sent.
     */
    fun onVideoLine(available: Boolean) {
        videoLine = available
        if (!available && isOn) switch(false)
    }

    /** The camera did not open, or stopped on its own. The switch goes off and the call goes on. */
    fun onCameraFailed() {
        if (isOn) switch(false)
    }

    /**
     * The service set its foreground type anew, for a call that was just answered, and the system
     * refused to name the camera in it.
     */
    fun onForegroundRefused() {
        isOn = false
        apply()
    }

    private fun switch(on: Boolean) {
        val wanted = on && port.hasCameraPermission() && videoLine
        if (wanted != isOn) {
            // A refused type leaves the camera off. The service keeps the type it had.
            isOn = wanted && port.setForegroundCamera(true)
            if (!wanted) port.setForegroundCamera(false)
        }
        apply()
    }

    private fun apply() {
        port.onSwitchChanged(on = isOn, paused = isOn && !onScreen)
        val run = isOn && onScreen
        if (run == isRunning) return
        isRunning = run
        port.setCameraRunning(run)
    }
}
