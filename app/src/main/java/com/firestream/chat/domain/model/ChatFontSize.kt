package com.firestream.chat.domain.model

/** Bounds of the chat font size setting, in sp. [DEFAULT_SP] matches `bodyMedium` in `Type.kt`. */
object ChatFontSize {
    const val DEFAULT_SP = 15f
    const val MIN_SP = 12f
    const val MAX_SP = 22f
    const val STEP_SP = 0.5f
}
