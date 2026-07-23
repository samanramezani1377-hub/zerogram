# Add project specific ProGuard rules here.

# Signal Protocol
-keep class org.signal.libsignal.** { *; }
-keepclassmembers class org.signal.libsignal.** { *; }

# WebRTC
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }

# JmDNS
-keep class javax.jmdns.** { *; }
-keepclassmembers class javax.jmdns.** { *; }

# Kotlin Serialization
-#keepattributes *Annotation*, InnerClasses

-dontnote kotlinx.serialization.AnnotationsKt
- keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
- keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
- keep class * extends androidxroom.RoomDatabase
- keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
