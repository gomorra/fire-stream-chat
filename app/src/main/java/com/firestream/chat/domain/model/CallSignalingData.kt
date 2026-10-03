package com.firestream.chat.domain.model

data class SdpData(
    val sdp: String,
    val type: String
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
    val video: Boolean = false
)
