# Release minify (M9, PLAN §7). R8 full mode is on.
# AndroidX, Compose, CameraX, ML Kit, and kotlinx.serialization also ship
# consumer rules. AGP merges those. The rules below are the ones this app
# needs on top, because these libraries are reached through reflection,
# JNI, or generated serializers.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- kotlinx.serialization (rules/common.pro from 1.11.0) ---
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembers class * {
    static <1> *;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

# Generated serializers and the custom Uuid / Instant serializers.
-keep,includedescriptorclasses class world.xhere.**$$serializer { *; }
-keepclassmembers class world.xhere.shared.** {
    *** Companion;
}
-keep class world.xhere.shared.json.** { *; }
-keepclassmembers enum world.xhere.shared.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Ktor (OkHttp engine on Android; the engine is selected by class) ---
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# --- supabase-kt (auth + postgrest plugins, KotlinXSerializer) ---
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**

# --- MapLibre Native (JNI). Default Android rules keep native methods.
# The SDK also looks classes up by name from the style and location engine. ---
-keep class org.maplibre.** { *; }
-keep interface org.maplibre.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.maplibre.**

# --- ML Kit image labeling (bundled model, pipeline loaded by name) ---
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# --- CameraX ---
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Koin builds the graph with constructor references. Keep the Android helpers
# it looks up, and the module that names every ViewModel.
-keep class org.koin.** { *; }
-keep class world.xhere.app.di.** { *; }

# Sentry is in the APK so its init provider still has a class after R8.
# CrashReporting does not call it unless SENTRY_DSN is a real DSN.
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# Manifest entries. AGP already keeps these; this is the belt for the startup test.
-keep class world.xhere.app.XHereApplication { *; }
-keep class world.xhere.app.MainActivity { *; }
