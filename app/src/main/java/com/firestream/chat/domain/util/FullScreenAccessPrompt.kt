package com.firestream.chat.domain.util

/** The first Android version with the special access *Full screen notifications* (Android 14). */
const val FULL_SCREEN_ACCESS_MIN_API = 34

/**
 * Whether the app asks for the special access *Full screen notifications*.
 *
 * It asks when the access exists on this Android version, is off, and the user has not said
 * *Not now*. Without the access an incoming call and a timer alarm show only as a notification.
 */
fun shouldPromptForFullScreenAccess(apiLevel: Int, granted: Boolean, dismissed: Boolean): Boolean =
    apiLevel >= FULL_SCREEN_ACCESS_MIN_API && !granted && !dismissed
