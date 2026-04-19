package com.firestream.chat.ui.theme

import androidx.compose.ui.graphics.Color

// Primary - Fire orange (accent, used sparingly)
val FireOrange = Color(0xFFF26A1F)
val FireOrangeLight = Color(0xFFF79A6C)
val FireOrangeDark = Color(0xFFC94E0E)

// Secondary - Deep charcoal
val Charcoal = Color(0xFF2D3436)
val CharcoalLight = Color(0xFF636E72)
val CharcoalDark = Color(0xFF1A1D1E)

// Background
val BackgroundLight = Color(0xFFFAFAFA)
val BackgroundDark = Color(0xFF0E0F11)

val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDark = Color(0xFF17181B)

// Message bubbles
val SentBubble = Color(0xFFE0DDD6)        // light mode: warm-neutral (not orange)
val ReceivedBubble = Color(0xFFF1EDE6)    // light mode: slightly warmer off-white
val SentBubbleDark = Color(0xFF1F2125)    // warm-neutral gray, NOT orange
val ReceivedBubbleDark = Color(0xFF17181B) // same tone as surface

// Status colors
val OnlineGreen = Color(0xFF00B894)
val ErrorRed = Color(0xFFE74C3C)
val WarningYellow = Color(0xFFF39C12)
val ReadReceiptBlue = Color(0xFF34B7F1)   // retained for light mode; dark uses FireOrange

// Text
val TextPrimary = Color(0xFF2D3436)
val TextSecondary = Color(0xFF636E72)
val TextOnPrimary = Color(0xFFFFFFFF)
val TextPrimaryDark = Color(0xFFECEBE7)
val TextSecondaryDark = Color(0xFFA8A6A1)

// ─── Calm FireStream palette (dark) ──────────────────────────────────
// Explicit tokens for the redesign. Used when Material roles don't map
// cleanly (outgoing bubble, avatar placeholder, nav indicator pill).
val FsBg = BackgroundDark                  // app + top bar background
val FsSurface = SurfaceDark                // cards, incoming bubbles
val FsSurface2 = Color(0xFF1F2125)         // outgoing bubbles (warm neutral)
val FsSurface3 = Color(0xFF2A2C31)         // raised, avatar placeholder bg
val FsDivider = Color(0x0FFFFFFF)          // rgba(255,255,255,0.06)

val FsText = TextPrimaryDark
val FsTextDim = TextSecondaryDark
val FsTextMute = Color(0xFF6E6C67)

val FsAccent = FireOrange
val FsAccentSoft = Color(0x24F26A1F)       // rgba(242,106,31,0.14) — nav pill bg
val FsAccentDim = Color(0x8CF26A1F)        // rgba(242,106,31,0.55) — toggle track

val FsAvatarBg = FsSurface3
val FsAvatarText = Color(0xFFC9C6BF)
