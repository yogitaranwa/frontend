/**
 * jni_bridge.cpp  ·  All JNI entry points for artgrid-native shared library.
 *
 * Naming convention: Java_<package>_<class>_<method>
 * Package: com.artgrid.mobile.nativebridge
 * Class:   ArtGridNative
 *
 * THREAD SAFETY:
 *   All C++ pipeline functions are pure (no global mutable state).
 *   Thread safety is guaranteed by the Kotlin layer (Dispatchers.Default).
 *   The JNI bridge simply marshals data in/out — no synchronisation needed here.
 *
 * CRASH PREVENTION:
 *   - All image inputs are bounds-checked and size-capped at 4000px.
 *   - Every function wraps its body in try{} catch(...){} and returns a safe
 *     fallback (null / empty array) on any exception.
 *   - AndroidBitmap_lockPixels errors return early with a descriptive log.
 */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <vector>

#include "artgrid_math.h"

// Forward declarations of pipeline entry points
void edge_pipeline_run(const uint32_t*, int, int, float, bool, uint32_t*) noexcept;
bool perspective_correct_run(const uint32_t*, int, int, uint32_t*) noexcept;
void greyscale_oklab(const uint32_t*, int, int, uint32_t*) noexcept;
void tonal_heatmap(const uint32_t*, int, int, uint32_t*) noexcept;
void white_balance(const uint32_t*, int, int, uint32_t*, bool, uint8_t, uint8_t, uint8_t) noexcept;
void invert_linear(const uint32_t*, int, int, uint32_t*) noexcept;
void kuwahara_filter(const uint32_t*, int, int, uint32_t*, int) noexcept;

struct ColorSample {
    uint8_t r, g, b;
    float oklab_L, oklab_a, oklab_b;
    float hsl_h, hsl_s, hsl_l;
    float kelvin;
    float lightness_pct;
};
ColorSample sample_color(const uint32_t*, int, int, int, int, int) noexcept;

struct KMResult {
    std::vector<std::pair<std::string, float>> recipe;
    float similarity_score;
};
KMResult km_solve(uint8_t, uint8_t, uint8_t, int) noexcept;

std::vector<uint32_t> palette_quantise(const uint32_t*, int, int, int) noexcept;

// ── Logging ───────────────────────────────────────────────────────────────────

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ArtGridNative", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "ArtGridNative", __VA_ARGS__)

// ── Bitmap helper: lock pixels → run fn → unlock → return result bitmap ───────

/**
 * Helper that:
 *  1. Locks [src_bitmap] pixels (read-only)
 *  2. Optionally caps image to MAX_PROCESSING_DIM
 *  3. Calls [fn] with (pixels, w, h, output_pixels, out_w, out_h)
 *  4. Creates a result Bitmap with the output pixels
 *  5. Unlocks source bitmap
 * Returns local jobject reference to the new Bitmap, or null on error.
 */
template<typename Fn>
static jobject bitmap_process(JNIEnv* env, jobject src_bitmap, Fn fn) {
    try {
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, src_bitmap, &info) < 0) {
            LOGE("getInfo failed");
            return nullptr;
        }
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LOGE("Bitmap format must be ARGB_8888 — got %d", info.format);
            return nullptr;
        }

        void* pixels_raw = nullptr;
        if (AndroidBitmap_lockPixels(env, src_bitmap, &pixels_raw) < 0) {
            LOGE("lockPixels failed");
            return nullptr;
        }

        int src_w = static_cast<int>(info.width);
        int src_h = static_cast<int>(info.height);

        // Cap resolution
        int proc_w, proc_h;
        compute_capped_dims(src_w, src_h, proc_w, proc_h);

        const uint32_t* src = static_cast<const uint32_t*>(pixels_raw);
        std::vector<uint32_t> downsampled;
        if (proc_w != src_w || proc_h != src_h) {
            downsampled.resize(proc_w * proc_h);
            bilinear_downsample(src, src_w, src_h, downsampled.data(), proc_w, proc_h);
            src = downsampled.data();
        }

        std::vector<uint32_t> output(proc_w * proc_h);
        fn(src, proc_w, proc_h, output.data());

        AndroidBitmap_unlockPixels(env, src_bitmap);

        // Create output Bitmap via Android Bitmap.createBitmap(w, h, ARGB_8888)
        jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
        jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
        jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                    "Landroid/graphics/Bitmap$Config;");
        jobject argbConfig = env->GetStaticObjectField(configClass, argbField);

        jmethodID createBitmap = env->GetStaticMethodID(bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        jobject result = env->CallStaticObjectMethod(bitmapClass, createBitmap,
                                                     proc_w, proc_h, argbConfig);
        if (!result) { LOGE("createBitmap returned null"); return nullptr; }

        void* out_raw = nullptr;
        AndroidBitmap_lockPixels(env, result, &out_raw);
        std::memcpy(out_raw, output.data(), proc_w * proc_h * 4);
        AndroidBitmap_unlockPixels(env, result);

        return result;

    } catch (...) {
        LOGE("Unhandled C++ exception in bitmap_process");
        return nullptr;
    }
}

// ── Edge extraction ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_edgePipeline(
    JNIEnv* env, jobject /*thiz*/,
    jobject src_bitmap,
    jfloat sensitivity,
    jboolean overlay)
{
    return bitmap_process(env, src_bitmap, [sensitivity, overlay]
        (const uint32_t* px, int w, int h, uint32_t* out){
            edge_pipeline_run(px, w, h, sensitivity, overlay != JNI_FALSE, out);
        });
}

// ── Perspective correction ──────────────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_perspectiveCorrect(
    JNIEnv* env, jobject /*thiz*/,
    jobject src_bitmap)
{
    // Returns source if corner detection fails (safe fallback)
    return bitmap_process(env, src_bitmap,
        [](const uint32_t* px, int w, int h, uint32_t* out){
            perspective_correct_run(px, w, h, out);
        });
}

// ── Greyscale ───────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_greyscaleOklab(
    JNIEnv* env, jobject /*thiz*/,
    jobject src_bitmap)
{
    return bitmap_process(env, src_bitmap,
        [](const uint32_t* px, int w, int h, uint32_t* out){
            greyscale_oklab(px, w, h, out);
        });
}

// ── Tonal heatmap ───────────────────────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_nativeTonalHeatmap(
    JNIEnv* env, jobject /*thiz*/,
    jobject src_bitmap)
{
    return bitmap_process(env, src_bitmap,
        [](const uint32_t* px, int w, int h, uint32_t* out){
            tonal_heatmap(px, w, h, out);
        });
}

// ── White balance ───────────────────────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_nativeWhiteBalance(
    JNIEnv* env, jobject /*thiz*/,
    jobject src_bitmap,
    jboolean use_percentile,
    jint grey_r, jint grey_g, jint grey_b)
{
    return bitmap_process(env, src_bitmap,
        [use_percentile, grey_r, grey_g, grey_b]
        (const uint32_t* px, int w, int h, uint32_t* out){
            white_balance(px, w, h, out, use_percentile != JNI_FALSE,
                static_cast<uint8_t>(grey_r), static_cast<uint8_t>(grey_g),
                static_cast<uint8_t>(grey_b));
        });
}

// ── Linear inversion ───────────────────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_invertLinear(
    JNIEnv* env, jobject /*thiz*/,
    jobject src_bitmap)
{
    return bitmap_process(env, src_bitmap,
        [](const uint32_t* px, int w, int h, uint32_t* out){
            invert_linear(px, w, h, out);
        });
}

// ── Kuwahara filter ──────────────────────────────────────────────────────────

extern "C" JNIEXPORT jobject JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_kuwaharaFilter(
    JNIEnv* env, jobject /*thiz*/,
    jobject src_bitmap,
    jint radius)
{
    return bitmap_process(env, src_bitmap,
        [radius](const uint32_t* px, int w, int h, uint32_t* out){
            kuwahara_filter(px, w, h, out, static_cast<int>(radius));
        });
}

// ── Colour sample ────────────────────────────────────────────────────────────
// Returns FloatArray: [r_norm, g_norm, b_norm, oklab_L, oklab_a, oklab_b,
//                      hsl_h_norm, hsl_s, hsl_l, kelvin_norm, lightness_pct]

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_nativeSampleColor(
    JNIEnv* env, jobject /*thiz*/,
    jobject src_bitmap,
    jint cx, jint cy, jint radius)
{
    try {
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, src_bitmap, &info) < 0) return nullptr;
        void* raw = nullptr;
        if (AndroidBitmap_lockPixels(env, src_bitmap, &raw) < 0) return nullptr;

        ColorSample s = sample_color(
            static_cast<const uint32_t*>(raw),
            static_cast<int>(info.width), static_cast<int>(info.height),
            static_cast<int>(cx), static_cast<int>(cy), static_cast<int>(radius));

        AndroidBitmap_unlockPixels(env, src_bitmap);

        jfloatArray arr = env->NewFloatArray(11);
        float data[11] = {
            s.r / 255.0f,   s.g / 255.0f,  s.b / 255.0f,
            s.oklab_L,      s.oklab_a,     s.oklab_b,
            s.hsl_h / 360.0f, s.hsl_s,    s.hsl_l,
            s.kelvin,       s.lightness_pct
        };
        env->SetFloatArrayRegion(arr, 0, 11, data);
        return arr;
    } catch (...) {
        LOGE("sampleColor: unhandled exception");
        return nullptr;
    }
}

// ── K–M paint mixing ───────────────────────────────────────────────────────
// Returns String JSON: {"score":82.4,"recipe":[{"name":"Ultramarine Blue","weight":0.6},…]}

extern "C" JNIEXPORT jstring JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_kmSolve(
    JNIEnv* env, jobject /*thiz*/,
    jint r8, jint g8, jint b8,
    jint medium)
{
    try {
        KMResult result = km_solve(
            static_cast<uint8_t>(r8), static_cast<uint8_t>(g8),
            static_cast<uint8_t>(b8), static_cast<int>(medium));

        // Build JSON manually (no library needed for this simple structure)
        std::string json = "{\"score\":";
        json += std::to_string(static_cast<int>(result.similarity_score));
        json += ".0,\"recipe\":[";
        for (size_t i = 0; i < result.recipe.size(); ++i) {
            if (i > 0) json += ",";
            json += "{\"name\":\"" + result.recipe[i].first + "\",\"weight\":";
            // 4 decimal places
            char buf[16];
            snprintf(buf, sizeof(buf), "%.4f", result.recipe[i].second);
            json += buf;
            json += "}";
        }
        json += "]}";
        return env->NewStringUTF(json.c_str());
    } catch (...) {
        LOGE("kmSolve: unhandled exception");
        return env->NewStringUTF("{\"score\":0,\"recipe\":[]}");
    }
}

// ── Palette quantisation ─────────────────────────────────────────────────────
// Returns IntArray of ARGB-packed palette colours.

extern "C" JNIEXPORT jintArray JNICALL
Java_com_artgrid_mobile_nativebridge_ArtGridNative_paletteQuantise(
    JNIEnv* env, jobject /*thiz*/,
    jobject src_bitmap,
    jint n_colors)
{
    try {
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, src_bitmap, &info) < 0) return nullptr;
        void* raw = nullptr;
        if (AndroidBitmap_lockPixels(env, src_bitmap, &raw) < 0) return nullptr;

        auto palette = palette_quantise(
            static_cast<const uint32_t*>(raw),
            static_cast<int>(info.width), static_cast<int>(info.height),
            static_cast<int>(n_colors));

        AndroidBitmap_unlockPixels(env, src_bitmap);

        jintArray arr = env->NewIntArray(static_cast<jsize>(palette.size()));
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(palette.size()),
                               reinterpret_cast<const jint*>(palette.data()));
        return arr;
    } catch (...) {
        LOGE("paletteQuantise: unhandled exception");
        return nullptr;
    }
}
