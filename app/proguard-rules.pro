# ZeroChat ProGuard / R8 Rules
# Keep rules for libraries used in the project.

# ── Signal Protocol ──────────────────────────────────────────────────
# (For when libsignal-client is integrated in production)
-keep class org.signal.libsignal.** { *; }
-keepclassmembers class org.signal.libsignal.** { *; }

# ── WebRTC (Stream WebRTC) ───────────────────────────────────────────
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }

# ── JmDNS ────────────────────────────────────────────────────────────
-keep class javax.jmdns.** { *; }
-keepclassmembers class javax.jmdns.** { *; }

# ── Kotlin Serialization ─────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Room ─────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# ── Coroutines ───────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── DataStore ────────────────────────────────────────────────────────
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ── Timber ───────────────────────────────────────────────────────────
-dontwarn timber.log.Timber

# ── General Android ──────────────────────────────────────────────────
-keep class com.zerochat.BuildConfig { *; }
