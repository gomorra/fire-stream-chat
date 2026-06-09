package com.firestream.chat.data.util

import android.util.Log
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerState

private const val TAG = "EnumParsers"

// Unknown enum values from the backend (or stale local rows) map to safe
// defaults instead of crashing, but each one is logged so schema drift
// surfaces in logcat instead of being silently masked. Single source of
// truth for the raw-string → enum defaults used by the message mappers.

internal fun parseMessageType(raw: String): MessageType =
    runCatching { MessageType.valueOf(raw) }.getOrElse {
        Log.w(TAG, "Unknown message type '$raw' — defaulting to TEXT")
        MessageType.TEXT
    }

internal fun parseMessageStatus(raw: String): MessageStatus =
    runCatching { MessageStatus.valueOf(raw) }.getOrElse {
        Log.w(TAG, "Unknown message status '$raw' — defaulting to SENT")
        MessageStatus.SENT
    }

internal fun parseTimerState(raw: String): TimerState? =
    runCatching { TimerState.valueOf(raw) }.getOrElse {
        Log.w(TAG, "Unknown timer state '$raw' — defaulting to null")
        null
    }
