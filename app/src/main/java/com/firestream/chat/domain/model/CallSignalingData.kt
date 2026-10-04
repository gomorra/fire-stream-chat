package com.firestream.chat.domain.model

data class SdpData(
    val sdp: String,
    val type: String
)

/** What one person in a call says about their own camera and microphone, right now. */
data class CallMedia(
    val camera: Boolean = false,
    val mic: Boolean = true
)

/**
 * A call the signed-in user just placed.
 *
 * @param videoLine the offer may carry a video line: the callee's app said it takes one. False on
 *   any doubt, because an app without video crashes on such an offer.
 */
data class OutgoingCall(
    val callId: String,
    val videoLine: Boolean
)

data class CallSignalingData(
    val callId: String,
    val callerId: String,
    val calleeId: String,
    val status: String,
    val offer: SdpData?,
    val answer: SdpData?,
    val createdAt: Long,
    val endedAt: Long?,
    val endReason: String?,
    /** How the call was started. Not the live camera state. */
    val video: Boolean = false,
    /**
     * The live state per user id, each entry written by that user. Someone who has written
     * nothing yet has no entry: an older app, or a call that is not connected.
     */
    val media: Map<String, CallMedia> = emptyMap()
)
