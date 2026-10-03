// PROTOTYPE — throwaway. See VideoCallPrototype.kt.
//
// Renders every variant on the JVM and writes PNGs to app/build/prototype-shots/, so the
// variants can be compared without a device. It asserts nothing.
//
//   ./gradlew :app:testFirebaseDebugUnitTest --tests '*VideoCallPrototypeShots*' -Proborazzi.test.record=true
package com.firestream.chat.ui.call.prototype

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The emulator's screen: 1080 x 2400 at 420 dpi.
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w411dp-h914dp-420dpi")
// The launch is typed Any because a JUnit class is public and PrototypeLaunch is internal.
class VideoCallPrototypeShots(private val name: String, private val launch: Any, private val tap: String) {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun shot() {
        // The prototype's clock never settles, so time is advanced by hand.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VideoCallPrototype(
                launch = launch as PrototypeLaunch,
                appDark = false,
                inPip = false,
                host = PrototypeHost(setPipEligible = {}, enterPip = {}, close = {}),
            )
        }
        // 1.3 s is the middle of the first speaking turn, so the speaker's ring is at its widest.
        rule.mainClock.advanceTimeBy(1_300)
        // One gesture before the shot: a tap on a content description, a tap on
        // "text:<a label>", or "swipe:x1,y1,x2,y2" in screen pixels.
        when {
            tap.isEmpty() -> Unit
            tap.startsWith("swipe:") -> {
                val (x1, y1, x2, y2) = tap.removePrefix("swipe:").split(",").map { it.toFloat() }
                rule.onRoot().performTouchInput { swipe(Offset(x1, y1), Offset(x2, y2), durationMillis = 300) }
            }
            tap.startsWith("text:") -> rule.onAllNodesWithText(tap.removePrefix("text:")).onFirst().performClick()
            else -> rule.onAllNodesWithContentDescription(tap).onFirst().performClick()
        }
        if (tap.isNotEmpty()) rule.mainClock.advanceTimeBy(1_200)
        rule.onRoot().captureRoboImage(filePath = "build/prototype-shots/$name.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = buildList {
            for (variant in CallVariant.entries) {
                val key = variant.key.lowercase()
                // B and C follow the app theme, so they are shot in both. A is always dark.
                val themes = if (variant == CallVariant.STAGE) listOf(true) else listOf(true, false)
                for (dark in themes) {
                    val base = PrototypeLaunch(variant = variant, forceDark = dark)
                    val tag = "$key-${if (dark) "dark" else "light"}"
                    fun shot(name: String, launch: PrototypeLaunch, tap: String = "") {
                        add(arrayOf("$tag-$name", launch, tap))
                    }
                    shot("1-two-video", base)
                    shot("2-two-voice", base.copy(video = false, myCamera = false, remoteCameras = RemoteCameras.OFF))
                    shot("3-two-their-camera-off", base.copy(remoteCameras = RemoteCameras.OFF))
                    shot("4-three-mixed", base.copy(people = 3, remoteCameras = RemoteCameras.MIXED))
                    shot("5-four", base.copy(people = 4))
                    shot("6-incoming-video", base.copy(phase = CallPhase.INCOMING))
                    shot("7-incoming-voice", base.copy(phase = CallPhase.INCOMING, video = false, myCamera = false))
                    shot("8-outgoing-video", base.copy(phase = CallPhase.OUTGOING))
                    shot("9-ended", base.copy(phase = CallPhase.ENDED))
                    shot("10-two-weak", base.copy(weak = true))
                    when (variant) {
                        CallVariant.STAGE -> {
                            shot("11-bare", base.copy(bare = true))
                            shot("12-self-dragged-to-top-left", base, tap = "swipe:900,1900,200,600")
                        }
                        CallVariant.SPLIT -> {
                            shot("11-sheet-pulled-up", base.copy(people = 3), tap = "More")
                            shot("12-tile-enlarged", base.copy(people = 4), tap = "text:Jonas")
                            shot("13-sheet-dragged-up", base, tap = "swipe:540,2080,540,900")
                        }
                        CallVariant.DOCKED -> {
                            shot("11-full-screen", base.copy(people = 4), tap = "Full screen")
                            shot("12-own-camera-off", base, tap = "Turn camera off")
                            shot("13-card-dragged-to-full", base, tap = "swipe:540,900,540,2200")
                            shot("14-card-dragged-to-strip", base, tap = "swipe:540,900,540,300")
                        }
                    }
                }
            }
        }
    }
}
