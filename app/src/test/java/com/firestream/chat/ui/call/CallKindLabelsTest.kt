package com.firestream.chat.ui.call

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.firestream.chat.domain.model.CallDirection
import com.firestream.chat.domain.model.CallLogEntry
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.ui.calls.buildCallLabel
import com.firestream.chat.ui.calls.directionLabel
import com.firestream.chat.ui.chat.callBubbleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure pieces around the stage: what the log and the bubble say, and where the self tile rests. */
class CallKindLabelsTest {

    private fun entry(direction: CallDirection, seconds: Int?, video: Boolean) =
        CallLogEntry("m1", "chat1", "user1", "Alice", null, direction, seconds, timestamp = 0L, video = video)

    @Test
    fun `a call log row says video call for a video entry`() {
        assertEquals("Video call · 1m 5s", buildCallLabel(entry(CallDirection.OUTGOING, 65, video = true)))
        assertEquals("Video call · No answer", buildCallLabel(entry(CallDirection.OUTGOING, null, video = true)))
        assertEquals("Video call · Missed", buildCallLabel(entry(CallDirection.MISSED, null, video = true)))
    }

    @Test
    fun `a call log row of a voice entry reads as before`() {
        assertEquals("1m 5s", buildCallLabel(entry(CallDirection.INCOMING, 65, video = false)))
        assertEquals("No answer", buildCallLabel(entry(CallDirection.OUTGOING, 0, video = false)))
        assertEquals("Missed", buildCallLabel(entry(CallDirection.MISSED, null, video = false)))
    }

    @Test
    fun `the detail sheet names the direction and the kind`() {
        assertEquals("Outgoing video call", directionLabel(CallDirection.OUTGOING, video = true))
        assertEquals("Missed video call", directionLabel(CallDirection.MISSED, video = true))
        assertEquals("Incoming call", directionLabel(CallDirection.INCOMING, video = false))
    }

    @Test
    fun `the call bubble says video call for a video call`() {
        assertEquals("Outgoing video call", callBubbleLabel("hangup", isOwnMessage = true, video = true))
        assertEquals("Incoming video call", callBubbleLabel("remote_hangup", isOwnMessage = false, video = true))
        assertEquals("Missed video call", callBubbleLabel("timeout", isOwnMessage = false, video = true))
        assertEquals("Video call · No answer", callBubbleLabel("timeout", isOwnMessage = true, video = true))
        assertEquals("Video call · Declined", callBubbleLabel("declined", isOwnMessage = false, video = true))
    }

    @Test
    fun `the call bubble of a voice call reads as before`() {
        assertEquals("Outgoing call", callBubbleLabel("hangup", isOwnMessage = true, video = false))
        assertEquals("Incoming call", callBubbleLabel("hangup", isOwnMessage = false, video = false))
        assertEquals("Missed call", callBubbleLabel("timeout", isOwnMessage = false, video = false))
        assertEquals("No answer", callBubbleLabel("timeout", isOwnMessage = true, video = false))
        assertEquals("Declined", callBubbleLabel("declined", isOwnMessage = true, video = false))
        assertEquals("Declined", callBubbleLabel("declined", isOwnMessage = false, video = false))
    }

    // ── Where the self tile rests ───────────────────────────────────────────

    private val bounds = Rect(left = 10f, top = 100f, right = 300f, bottom = 700f)

    @Test
    fun `each corner is a position on the bounds`() {
        assertEquals(Offset(10f, 100f), SelfTileCorners.offsetOf(SelfTileCorners.TOP_START, bounds))
        assertEquals(Offset(300f, 100f), SelfTileCorners.offsetOf(SelfTileCorners.TOP_END, bounds))
        assertEquals(Offset(10f, 700f), SelfTileCorners.offsetOf(SelfTileCorners.BOTTOM_START, bounds))
        assertEquals(Offset(300f, 700f), SelfTileCorners.offsetOf(SelfTileCorners.BOTTOM_END, bounds))
    }

    @Test
    fun `a tile that is let go snaps to the nearest corner`() {
        assertEquals(SelfTileCorners.TOP_START, SelfTileCorners.nearest(Offset(100f, 300f), bounds))
        assertEquals(SelfTileCorners.TOP_END, SelfTileCorners.nearest(Offset(200f, 300f), bounds))
        assertEquals(SelfTileCorners.BOTTOM_START, SelfTileCorners.nearest(Offset(100f, 500f), bounds))
        assertEquals(SelfTileCorners.BOTTOM_END, SelfTileCorners.nearest(Offset(200f, 500f), bounds))
        // Dragged past the bounds, it still lands in a corner.
        assertEquals(SelfTileCorners.TOP_START, SelfTileCorners.nearest(Offset(-50f, -50f), bounds))
        assertEquals(SelfTileCorners.BOTTOM_END, SelfTileCorners.nearest(Offset(900f, 900f), bounds))
    }

    // ── Whether any video shows ─────────────────────────────────────────────

    private val alice = CallParticipant("remote1", "Alice", null)

    @Test
    fun `video shows while the own camera runs or a frame of someone else has arrived`() {
        assertFalse(showsVideo(CallUiControls(), listOf(alice)))
        assertTrue(showsVideo(CallUiControls(cameraOn = true), listOf(alice)))
        assertTrue(showsVideo(CallUiControls(), listOf(alice.copy(cameraOn = true, hasFrame = true))))
    }

    @Test
    fun `a paused camera and a camera without a frame show no video`() {
        assertFalse(showsVideo(CallUiControls(cameraOn = true, cameraPaused = true), listOf(alice)))
        assertFalse(showsVideo(CallUiControls(), listOf(alice.copy(cameraOn = true, hasFrame = false))))
        // A frame left over from before the camera went off.
        assertFalse(showsVideo(CallUiControls(), listOf(alice.copy(cameraOn = false, hasFrame = true))))
    }
}
