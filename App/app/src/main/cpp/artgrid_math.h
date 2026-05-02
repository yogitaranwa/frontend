/**
 * artgrid_math.h
 * Core types and colour-space conversion utilities shared across all pipelines.
 * All functions are pure (no global mutable state) and thread-safe.
 *
 * Colour-space chain used throughout:
 *   sRGB (uint8 ARGB) → Linear RGB [0,1] → CIE XYZ D65 → Oklab
 *   Inverse: Oklab → XYZ → Linear RGB → sRGB
 */
#pragma once

#include <cmath>
#include <cstdint>
#include <algorithm>
#include <array>
#include <vector>
#include <numeric>

// ── Primitive types ───────────────────────────────────────────────────────────

struct Vec2 { float x, y; };
struct Vec3 { float x, y, z; };
struct Vec4 { float r, g, b, a; };   // linear-light RGBA [0,1]

// Axis-aligned bounding box in normalised [0,1] image coords.
struct NormBbox { float x, y, w, h; };

// ── Pixel packing helpers (Android ARGB_8888 = 0xAARRGGBB) ──────────────────

inline uint8_t argb_a(uint32_t px) { return (px >> 24) & 0xFF; }
inline uint8_t argb_r(uint32_t px) { return (px >> 16) & 0xFF; }
inline uint8_t argb_g(uint32_t px) { return (px >>  8) & 0xFF; }
inline uint8_t argb_b(uint32_t px) { return (px)       & 0xFF; }

inline uint32_t pack_argb(uint8_t a, uint8_t r, uint8_t g, uint8_t b) {
    return (static_cast<uint32_t>(a) << 24) |
           (static_cast<uint32_t>(r) << 16) |
           (static_cast<uint32_t>(g) <<  8) |
           static_cast<uint32_t>(b);
}

// ── sRGB ↔ Linear RGB ─────────────────────────────────────────────────────────

// IEC 61966-2-1 exact decode (handles the linear segment correctly)
inline float srgb_to_linear(float c) noexcept {
    return (c <= 0.04045f) ? (c / 12.92f)
                           : std::pow((c + 0.055f) / 1.055f, 2.4f);
}

inline float linear_to_srgb(float c) noexcept {
    c = std::clamp(c, 0.0f, 1.0f);
    return (c <= 0.0031308f) ? (12.92f * c)
                             : (1.055f * std::pow(c, 1.0f / 2.4f) - 0.055f);
}

// ── Linear RGB → CIE XYZ D65 (IEC standard matrix) ──────────────────────────

inline Vec3 linear_rgb_to_xyz(float r, float g, float b) noexcept {
    return {
        0.4124564f * r + 0.3575761f * g + 0.1804375f * b,
        0.2126729f * r + 0.7151522f * g + 0.0721750f * b,
        0.0193339f * r + 0.1191920f * g + 0.9503041f * b,
    };
}

inline Vec3 xyz_to_linear_rgb(float X, float Y, float Z) noexcept {
    return {
         3.2404542f * X - 1.5371385f * Y - 0.4985314f * Z,
        -0.9692660f * X + 1.8760108f * Y + 0.0415560f * Z,
         0.0556434f * X - 0.2040259f * Y + 1.0572252f * Z,
    };
}

// ── XYZ → Oklab (Björn Ottosson's 2020 formulation) ──────────────────────────
//
// Step 1: XYZ → LMS via M1
// Step 2: LMS → LMS^(1/3) (cube-root non-linearity)
// Step 3: LMS' → Lab via M2

inline Vec3 xyz_to_oklab(float X, float Y, float Z) noexcept {
    // M1: XYZ D65 → LMS cone responses
    float l = 0.8189330101f * X + 0.3618667424f * Y - 0.1288597137f * Z;
    float m = 0.0329845436f * X + 0.9293118715f * Y + 0.0361456387f * Z;
    float s = 0.0482003018f * X + 0.2643662691f * Y + 0.6338517070f * Z;

    // Cube-root
    float lp = std::cbrt(l);
    float mp = std::cbrt(m);
    float sp = std::cbrt(s);

    // M2: LMS' → Oklab
    return {
        0.2104542553f * lp + 0.7936177850f * mp - 0.0040720468f * sp,
        1.9779984951f * lp - 2.4285922050f * mp + 0.4505937099f * sp,
        0.0259040371f * lp + 0.7827717662f * mp - 0.8086757660f * sp,
    };
}

inline Vec3 oklab_to_xyz(float L, float a, float b) noexcept {
    // Inverse M2
    float lp = L + 0.3963377774f * a + 0.2158037573f * b;
    float mp = L - 0.1055613458f * a - 0.0638541728f * b;
    float sp = L - 0.0894841775f * a - 1.2914855480f * b;

    float l = lp * lp * lp;
    float m = mp * mp * mp;
    float s = sp * sp * sp;

    // Inverse M1
    return {
         4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
        -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
        -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
    };
}

// ── Convenience: sRGB uint8 → Oklab in one call ──────────────────────────────

inline Vec3 srgb8_to_oklab(uint8_t r8, uint8_t g8, uint8_t b8) noexcept {
    float rl = srgb_to_linear(r8 / 255.0f);
    float gl = srgb_to_linear(g8 / 255.0f);
    float bl = srgb_to_linear(b8 / 255.0f);
    auto [X, Y, Z] = linear_rgb_to_xyz(rl, gl, bl);
    return xyz_to_oklab(X, Y, Z);
}

inline uint32_t oklab_to_argb8(float L, float a, float b, uint8_t alpha = 255) noexcept {
    auto [X, Y, Z] = oklab_to_xyz(L, a, b);
    auto [rl, gl, bl] = xyz_to_linear_rgb(X, Y, Z);
    uint8_t r8 = static_cast<uint8_t>(std::clamp(linear_to_srgb(rl) * 255.0f, 0.0f, 255.0f));
    uint8_t g8 = static_cast<uint8_t>(std::clamp(linear_to_srgb(gl) * 255.0f, 0.0f, 255.0f));
    uint8_t b8 = static_cast<uint8_t>(std::clamp(linear_to_srgb(bl) * 255.0f, 0.0f, 255.0f));
    return pack_argb(alpha, r8, g8, b8);
}

// ── Safe image cap — prevents OOM on 48MP source images ──────────────────────

constexpr int MAX_PROCESSING_DIM = 4000;   // longest-edge cap for all C++ pipelines

inline void compute_capped_dims(int src_w, int src_h, int& out_w, int& out_h) noexcept {
    if (src_w <= MAX_PROCESSING_DIM && src_h <= MAX_PROCESSING_DIM) {
        out_w = src_w;
        out_h = src_h;
        return;
    }
    float scale = static_cast<float>(MAX_PROCESSING_DIM) / std::max(src_w, src_h);
    out_w = static_cast<int>(src_w * scale);
    out_h = static_cast<int>(src_h * scale);
}

// ── Bilinear downsample ───────────────────────────────────────────────────────

// Downsamples [src] (ARGB8, src_w×src_h) into [dst] (dst_w×dst_h).
void bilinear_downsample(
    const uint32_t* src, int src_w, int src_h,
    uint32_t* dst, int dst_w, int dst_h) noexcept;

// ── Gaussian kernel generation ────────────────────────────────────────────────

// Returns a 1-D Gaussian kernel of odd size, sigma = size/3.
std::vector<float> make_gaussian_kernel(int size) noexcept;

// Applies a separable Gaussian blur in-place on [data] (float, w×h).
void separable_gaussian_blur(float* data, int w, int h, int kernel_size) noexcept;

// ── Sobel-style gradient (custom 3×3 Taylor-expansion kernel) ────────────────

// Computes gradient magnitude and angle for each pixel into [mag] and [theta].
void compute_gradient(
    const float* L, int w, int h,
    float* mag, float* theta) noexcept;

// ── Weighted PCA eigenvector (2-step power iteration) ────────────────────────

// Given N points (px[i], py[i]) and weights w[i], returns the dominant eigenvector.
Vec2 weighted_pca_eigenvector(
    const float* px, const float* py, const float* wt,
    int n, float cx, float cy) noexcept;

// ── Float median (partial sort, stable) ──────────────────────────────────────
float vec_median(std::vector<float>& v) noexcept;
