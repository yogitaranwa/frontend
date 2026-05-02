# ArtGrid — ProGuard / R8 rules
# Applied to release builds only (debug builds run without minification).

# ── Android fundamentals ──────────────────────────────────────────────────────
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ── Hilt / Dagger generated code ─────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# ── Moshi JSON codegen ────────────────────────────────────────────────────────
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
-keep @com.squareup.moshi.JsonClass class * { *; }

# ── Retrofit + OkHttp ─────────────────────────────────────────────────────────
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Kotlin Serialisation (if used later) ─────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ── JNI: keep ALL methods in classes that call native code ───────────────────
# This is the critical rule: R8 must NOT rename or remove the JNI bridge methods
# because their names must exactly match the C++ JNIEXPORT symbols.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep ArtGridNative object and all its members (external fun declarations)
-keep class com.artgrid.mobile.nativebridge.ArtGridNative {
    *;
}
-keep class com.artgrid.mobile.nativebridge.ArtGridNative$Companion {
    *;
}

# Keep JNI data classes (passed back from native code as Kotlin data classes)
-keep class com.artgrid.mobile.nativebridge.ColorSampleResult { *; }
-keep class com.artgrid.mobile.nativebridge.KmResult { *; }
-keep class com.artgrid.mobile.nativebridge.KmRecipeEntry { *; }

# ── DataStore ─────────────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }

# ── Compose — keep previews during debug ─────────────────────────────────────
-keep class androidx.compose.ui.tooling.** { *; }
