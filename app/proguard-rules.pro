# PaySync Gateway R8 rules (release build: minify + shrinkResources ON).
#
# Room, WorkManager, OkHttp/Okio, Compose and Tink ship their own consumer
# rules inside their AARs, so only app-specific reflective surface is kept
# here. Every rule below exists because that class IS reached by reflection.

# ── Gson: models are serialized/deserialized via reflection ──────────────
# Wire models (DispatchPayload, PendingVerifyDto) and Room entities.
-keep class com.paysync.gateway.data.** { *; }
-keep class com.paysync.gateway.data.db.** { *; }

# Gson TypeToken generics survive only with signature attributes.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Coroutines: DebugProbes looked up reflectively when enabled ──────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── Keep line numbers in release crash traces (no source file names) ────
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
