# Signal Protocol
-keep class org.signal.** { *; }
-keep class org.whispersystems.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
