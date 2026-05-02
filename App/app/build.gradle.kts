import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// Load demo.properties if present (git-ignored)
val demoProps = Properties().also { props ->
    val file = rootProject.file("demo.properties")
    if (file.exists()) props.load(file.inputStream())
}

// Written by Android SDK tooling; optional key artgrid.dev.host overrides demo backend host (not in VCS).
val localProps = Properties().also { props ->
    val file = rootProject.file("local.properties")
    if (file.exists()) props.load(file.inputStream())
}

/** Demo backend host: DEV_HOST, then local.properties artgrid.dev.host, else LAN IP (physical device). Emulator: set artgrid.dev.host=10.0.2.2 in local.properties. */
fun resolveDemoHost(): String {
    val fromDemo = demoProps.getProperty("DEV_HOST")?.trim().orEmpty()
    if (fromDemo.isNotEmpty()) return fromDemo
    val fromLocal = localProps.getProperty("artgrid.dev.host")?.trim().orEmpty()
    if (fromLocal.isNotEmpty()) return fromLocal
    return "192.168.29.187"
}

/** Full service URL if set in demo.properties; otherwise http://<host>:<port>/. */
fun resolveDemoServiceUrl(port: Int, overrideKey: String): String {
    val direct = demoProps.getProperty(overrideKey)?.trim().orEmpty()
    if (direct.isNotEmpty()) return direct
    val host = resolveDemoHost()
    return "http://$host:$port/"
}

android {
    namespace  = "com.artgrid.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.artgrid.mobile"
        minSdk        = 26          // ARKit guard is deviceHasArCore() helper — minSdk 26 covers 99% devices
        targetSdk     = 35
        versionCode   = 1
        versionName   = "0.1.0"

        // Demo-mode: single flag flips the whole auth / ML URL strategy.
        // Set DEMO_MODE=true in demo.properties (never in VCS) for local demo runs.
        val demoMode = demoProps.getProperty("DEMO_MODE", "true").toBoolean()
        buildConfigField("Boolean", "DEMO_MODE",        "$demoMode")

        // HMAC secret for X-Device-Token (artgrid-ai-proxy). Must match backend PROXY_SHARED_SECRET in .env.
        val proxySharedSecret = demoProps.getProperty("PROXY_SHARED_SECRET")?.trim()?.takeIf { it.isNotEmpty() }
            ?: demoProps.getProperty("PROXY_DEVICE_SECRET")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "artgrid-proxy-secret-32ch"
        buildConfigField("String", "PROXY_SHARED_SECRET", "\"${proxySharedSecret.replace("\"", "\\\"")}\"")

        // Service base URLs — optional full URLs in demo.properties; else DEV_HOST / artgrid.dev.host / default LAN host below.
        buildConfigField("String", "AUTH_BASE_URL",  "\"${resolveDemoServiceUrl(8080, "AUTH_BASE_URL")}\"")
        buildConfigField("String", "ML_BASE_URL",    "\"${resolveDemoServiceUrl(8001, "ML_BASE_URL")}\"")
        buildConfigField("String", "PROXY_BASE_URL", "\"${resolveDemoServiceUrl(8082, "PROXY_BASE_URL")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // ── NDK / CMake ───────────────────────────────────────────────────────
        // Build artgrid-native shared library from C++17 sources.
        ndk {
            // arm64-v8a  : Samsung M34 5G + Xiaomi Redmi Note 12R (both ARM64)
            // x86_64     : Android Emulator on x86_64 host (for CI / demo on PC)
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    // ── CMake build for C++17 JNI library ────────────────────────────────────
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Multiple .so files with the same name (e.g. libc++_shared.so from
            // different AARs) are merged by keeping the first occurrence.
            pickFirsts += listOf("lib/**/libc++_shared.so", "lib/**/libartgrid-native.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose     = true
        buildConfig = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.google.material)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)

    // Compose BOM — aligns all Compose artifact versions
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle + ViewModel
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)  // collectAsStateWithLifecycle

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Retrofit + OkHttp 4 + Moshi
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Moshi — chosen: null-safe, Kotlin-first, KSP codegen avoids runtime reflection
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    // DataStore (token persistence)
    implementation(libs.datastore.preferences)

    // Security-crypto (EncryptedSharedPreferences backup for <API 26 — kept unused but declared)
    implementation(libs.security.crypto)

    // Coroutines
    implementation(libs.coroutines.android)

    // Room — F-31 (reference history) + F-32 (progression comparator) local SQLite
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // CameraX — F-27-MVP trace/camera-underlay mode
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Coil — async image loading in Compose (progression stages, reference history, crop preview)
    implementation(libs.coil.compose)

    testImplementation("junit:junit:4.13.2")
}
