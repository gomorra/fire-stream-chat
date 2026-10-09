package com.firestream.chat.ui.call

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.firestream.chat.domain.model.CallLogType
import com.firestream.chat.domain.model.CallLogEntry
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.ui.calls.buildCallLabel
import com.firestream.chat.ui.calls.typeLabel
import com.firestream.chat.ui.chat.callBubbleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure pieces around the stage: what the log and the bubble say, and where the self tile rests. */
class CallKindLabelsTest {

    private fun entry(type: CallLogType, seconds: Int?, video: Boolean) =
        CallLogEntry("m1", "chat1", "user1", "Alice", null, type, seconds, timestamp = 0L, video = video)

    @Test
    fun `a call log row says video call for a video entry`() {
        assertEquals("Video call · 1m 5s", buildCallLabel(entry(CallLogType.OUTGOING, 65, video = true)))
        assertEquals("Video call · No answer", buildCallLabel(entry(CallLogType.NO_ANSWER, null, video = true)))
        assertEquals("Video call · Missed", buildCallLabel(entry(CallLogType.MISSED, null, video = true)))
        assertEquals("Video call · Declined", buildCallLabel(entry(CallLogType.DECLINED, null, video = true)))
    }

    @Test
    fun `a call log row of a voice entry reads as before`() {
        assertEquals("1m 5s", buildCallLabel(entry(CallLogType.INCOMING, 65, video = false)))
        assertEquals("No answer", buildCallLabel(entry(CallLogType.NO_ANSWER, 0, video = false)))
        assertEquals("Missed", buildCallLabel(entry(CallLogType.MISSED, null, video = false)))
        assertEquals("Declined", buildCallLabel(entry(CallLogType.OUTGOING_DECLINED, null, video = false)))
    }

    @Test
    fun `the detail sheet names the type and the kind`() {
        assertEquals("Outgoing video call", typeLabel(CallLogType.OUTGOING, video = true))
        assertEquals("Outgoing video call", typeLabel(CallLogType.NO_ANSWER, video = true))
        assertEquals("Missed video call", typeLabel(CallLogType.MISSED, video = true))
        assertEquals("Declined video call", typeLabel(CallLogType.DECLINED, video = true))
        assertEquals("Incoming call", typeLabel(CallLogType.INCOMING, video = false))
        assertEquals("Declined call", typeLabel(CallLogType.DECLINED, video = false))
    }

    /** The bubble's label, from the message as it is stored: who wrote it, how the call ended, how long it ran. */
    private fun bubble(endReason: String, isOwnMessage: Boolean, video: Boolean, seconds: Int? = null) =
        callBubbleLabel(CallLogType.of(isOwnMessage, endReason, seconds), video)

    @Test
    fun `the call bubble says video call for a video call`() {
        assertEquals("Outgoing video call", bubble("hangup", isOwnMessage = true, video = true, seconds = 65))
        assertEquals("Incoming video call", bubble("remote_hangup", isOwnMessage = false, video = true, seconds = 65))
        assertEquals("Missed video call", bubble("timeout", isOwnMessage = false, video = true))
        assertEquals("Video call · No answer", bubble("timeout", isOwnMessage = true, video = true))
        assertEquals("Video call · Declined", bubble("declined", isOwnMessage = false, video = true))
    }

    @Test
    fun `the call bubble of a voice call reads as before`() {
        assertEquals("Outgoing call", bubble("hangup", isOwnMessage = true, video = false, seconds = 65))
        assertEquals("Incoming call", bubble("hangup", isOwnMessage = false, video = false, seconds = 65))
        assertEquals("Missed call", bubble("timeout", isOwnMessage = false, video = false))
        assertEquals("No answer", bubble("timeout", isOwnMessage = true, video = false))
        assertEquals("Declined", bubble("declined", isOwnMessage = true, video = false))
        assertEquals("Declined", bubble("declined", isOwnMessage = false, video = false))
    }

    // A call the caller cancelled while it rang is logged as "hangup" with no duration.
    @Test
    fun `a call that never connected reads as unanswered whatever its end reason`() {
        assertEquals("Video call · No answer", bubble("hangup", isOwnMessage = true, video = true))
        assertEquals("Missed video call", bubble("hangup", isOwnMessage = false, video = true))
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
