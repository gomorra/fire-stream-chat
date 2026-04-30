# Signal Protocol
-keep class org.signal.** { *; }
-keep class org.whispersystems.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# R8 ArrayIndexOutOfBoundsException on EmojiHandlerPanelKt.<clinit>() — the
# static emoji catalog (PANEL_CATEGORIES with hundreds of listOf() entries)
# trips a known R8 optimizer bug on the static initializer. Keep the whole
# top-level Kt class so R8 skips the broken pass; APK-size impact is
# negligible since the contents are emoji string literals.
-keep class com.firestream.chat.ui.chat.EmojiHandlerPanelKt { *; }
