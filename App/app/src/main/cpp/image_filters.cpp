/**
 * image_filters.cpp — tonal and colour-space image filters
 *
 *   Greyscale via Oklab L-channel luminance  (not NTSC luma)
 *   Tonal value heatmap (Oklab L → viridis-style colormap)
 *   White balance: 98th-percentile auto-white-point + per-channel levels
 *   Gamma-corrected linear inversion (not naive 255-x)
 *   Edge-preserving Kuwahara filter (4-quadrant mean/variance)
 */
#include "artgrid_math.h"
#include <vector>
#include <algorithm>
#include <cmath>
#include <array>

// ── Greyscale via Oklab L-channel ────────────────────────────────────────────

void greyscale_oklab(const uint32_t* src, int w, int h, uint32_t* dst) noexcept {
    for (int i = 0; i < w * h; ++i) {
        uint32_t px = src[i];
        float L = srgb8_to_oklab(argb_r(px), argb_g(px), argb_b(px)).x;
        // Oklab L is in [0,1]; convert back to grey sRGB
        // Grey point in Oklab: (L, 0, 0) → XYZ → linear RGB → sRGB
        uint32_t grey_px = oklab_to_argb8(L, 0.0f, 0.0f, argb_a(px));
        dst[i] = grey_px;
    }
}

// ── Tonal heatmap ───────────────────────────────────────────────────────────
//
// Maps Oklab L (perceived lightness [0,1]) to a perceptual heatmap.
// Colormap: deep shadows → dark navy, midtones → teal, highlights → amber/white.
// Implemented as a piecewise linear interpolation through 5 key colours in sRGB.

static uint32_t tonal_colormap(float t) noexcept {
    // t in [0,1]: 0 = shadow, 1 = highlight
    // Waypoints: (0.0)=#0D1117 (0.25)=#1F4E79 (0.5)=#2BB5A0 (0.75)=#F6A623 (1.0)=#FFFFFF
    struct ColStop { float t; uint8_t r, g, b; };
    static const ColStop stops[] = {
        {0.00f,  13,  17,  23},
        {0.25f,  31,  78, 121},
        {0.50f,  43, 181, 160},
        {0.75f, 246, 166,  35},
        {1.00f, 255, 255, 255},
    };
    constexpr int NS = 5;
    t = std::clamp(t, 0.0f, 1.0f);

    for (int i = 0; i < NS - 1; ++i) {
        if (t <= stops[i+1].t) {
            float f = (t - stops[i].t) / (stops[i+1].t - stops[i].t);
            uint8_t r = static_cast<uint8_t>(stops[i].r + f * (stops[i+1].r - stops[i].r));
            uint8_t g = static_cast<uint8_t>(stops[i].g + f * (stops[i+1].g - stops[i].g));
            uint8_t b = static_cast<uint8_t>(stops[i].b + f * (stops[i+1].b - stops[i].b));
            return pack_argb(255, r, g, b);
        }
    }
    return pack_argb(255, 255, 255, 255);
}

void tonal_heatmap(const uint32_t* src, int w, int h, uint32_t* dst) noexcept {
    for (int i = 0; i < w * h; ++i) {
        uint32_t px = src[i];
        float L = srgb8_to_oklab(argb_r(px), argb_g(px), argb_b(px)).x;
        dst[i] = tonal_colormap(L);
    }
}

// ── White balance + per-channel levels ──────────────────────────────────────
//
// Strategy: compute 98th-percentile value for each linear-light channel,
// scale so that value → 1.0 (white point normalisation).
// Also supports an explicit grey-patch reference point (grey_r, grey_g, grey_b in [0,255]).
// If use_percentile=true, ignores grey_patch values.

void white_balance(
    const uint32_t* src, int w, int h,
    uint32_t* dst,
    bool use_percentile,
    uint8_t grey_r, uint8_t grey_g, uint8_t grey_b) noexcept
{
    const int N = w * h;
    float scale_r = 1.0f, scale_g = 1.0f, scale_b = 1.0f;

    if (use_percentile) {
        // Collect linear-light channel values
        std::vector<float> ch_r(N), ch_g(N), ch_b(N);
        for (int i = 0; i < N; ++i) {
            uint32_t px = src[i];
            ch_r[i] = srgb_to_linear(argb_r(px) / 255.0f);
            ch_g[i] = srgb_to_linear(argb_g(px) / 255.0f);
            ch_b[i] = srgb_to_linear(argb_b(px) / 255.0f);
        }
        // 98th percentile
        auto pct98 = [&](std::vector<float>& v) {
            size_t idx = static_cast<size_t>(v.size() * 0.98f);
            std::nth_element(v.begin(), v.begin() + idx, v.end());
            return std::max(v[idx], 1e-6f);
        };
        float wr = pct98(ch_r), wg = pct98(ch_g), wb = pct98(ch_b);
        scale_r = 1.0f / wr;
        scale_g = 1.0f / wg;
        scale_b = 1.0f / wb;
    } else {
        // Grey-patch reference: the sampled grey pixel should be neutral
        float gr = srgb_to_linear(grey_r / 255.0f);
        float gg = srgb_to_linear(grey_g / 255.0f);
        float gb = srgb_to_linear(grey_b / 255.0f);
        float lum = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb;
        scale_r = lum / std::max(gr, 1e-6f);
        scale_g = lum / std::max(gg, 1e-6f);
        scale_b = lum / std::max(gb, 1e-6f);
    }

    for (int i = 0; i < N; ++i) {
        uint32_t px = src[i];
        float rl = srgb_to_linear(argb_r(px) / 255.0f) * scale_r;
        float gl = srgb_to_linear(argb_g(px) / 255.0f) * scale_g;
        float bl = srgb_to_linear(argb_b(px) / 255.0f) * scale_b;

        uint8_t r = static_cast<uint8_t>(std::clamp(linear_to_srgb(rl) * 255.0f, 0.0f, 255.0f));
        uint8_t g = static_cast<uint8_t>(std::clamp(linear_to_srgb(gl) * 255.0f, 0.0f, 255.0f));
        uint8_t b = static_cast<uint8_t>(std::clamp(linear_to_srgb(bl) * 255.0f, 0.0f, 255.0f));
        dst[i] = pack_argb(argb_a(px), r, g, b);
    }
}

// ── Gamma-corrected linear inversion ────────────────────────────────────────
//
// sRGB decode → linear[0,1] → invert (1-C) → sRGB encode.
// This avoids the muddy mid-tones of naive 255-x inversion.

void invert_linear(const uint32_t* src, int w, int h, uint32_t* dst) noexcept {
    for (int i = 0; i < w * h; ++i) {
        uint32_t px = src[i];
        float rl = 1.0f - srgb_to_linear(argb_r(px) / 255.0f);
        float gl = 1.0f - srgb_to_linear(argb_g(px) / 255.0f);
        float bl = 1.0f - srgb_to_linear(argb_b(px) / 255.0f);

        uint8_t r = static_cast<uint8_t>(std::clamp(linear_to_srgb(rl) * 255.0f, 0.0f, 255.0f));
        uint8_t g = static_cast<uint8_t>(std::clamp(linear_to_srgb(gl) * 255.0f, 0.0f, 255.0f));
        uint8_t b = static_cast<uint8_t>(std::clamp(linear_to_srgb(bl) * 255.0f, 0.0f, 255.0f));
        dst[i] = pack_argb(argb_a(px), r, g, b);
    }
}

// ── Kuwahara edge-preserving filter ─────────────────────────────────────────
//
// For each pixel, computes mean + variance in each of 4 overlapping quadrant
// neighbourhoods, assigns the mean of the quadrant with the lowest variance.
// [radius] controls the neighbourhood size (default 3 → 7×7 total area per quadrant).

void kuwahara_filter(const uint32_t* src, int w, int h, uint32_t* dst, int radius) noexcept {
    radius = std::clamp(radius, 1, 8);

    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {

            // 4 quadrants: TL, TR, BL, BR (each (radius+1)×(radius+1))
            struct Quad { float sum_r, sum_g, sum_b, sum_r2, sum_g2, sum_b2; int count; };
            std::array<Quad, 4> q{};

            for (int dy = -radius; dy <= 0; ++dy) {
                for (int dx = -radius; dx <= 0; ++dx) {
                    int sx = std::clamp(x + dx, 0, w-1);
                    int sy = std::clamp(y + dy, 0, h-1);
                    uint32_t px = src[sy*w+sx];
                    float r = argb_r(px), g = argb_g(px), b = argb_b(px);
                    q[0].sum_r+=r; q[0].sum_g+=g; q[0].sum_b+=b;
                    q[0].sum_r2+=r*r; q[0].sum_g2+=g*g; q[0].sum_b2+=b*b;
                    ++q[0].count;
                }
            }
            for (int dy = -radius; dy <= 0; ++dy) {
                for (int dx = 0; dx <= radius; ++dx) {
                    int sx = std::clamp(x+dx,0,w-1), sy = std::clamp(y+dy,0,h-1);
                    uint32_t px = src[sy*w+sx];
                    float r=argb_r(px), g=argb_g(px), b=argb_b(px);
                    q[1].sum_r+=r; q[1].sum_g+=g; q[1].sum_b+=b;
                    q[1].sum_r2+=r*r; q[1].sum_g2+=g*g; q[1].sum_b2+=b*b;
                    ++q[1].count;
                }
            }
            for (int dy = 0; dy <= radius; ++dy) {
                for (int dx = -radius; dx <= 0; ++dx) {
                    int sx = std::clamp(x+dx,0,w-1), sy = std::clamp(y+dy,0,h-1);
                    uint32_t px = src[sy*w+sx];
                    float r=argb_r(px), g=argb_g(px), b=argb_b(px);
                    q[2].sum_r+=r; q[2].sum_g+=g; q[2].sum_b+=b;
                    q[2].sum_r2+=r*r; q[2].sum_g2+=g*g; q[2].sum_b2+=b*b;
                    ++q[2].count;
                }
            }
            for (int dy = 0; dy <= radius; ++dy) {
                for (int dx = 0; dx <= radius; ++dx) {
                    int sx = std::clamp(x+dx,0,w-1), sy = std::clamp(y+dy,0,h-1);
                    uint32_t px = src[sy*w+sx];
                    float r=argb_r(px), g=argb_g(px), b=argb_b(px);
                    q[3].sum_r+=r; q[3].sum_g+=g; q[3].sum_b+=b;
                    q[3].sum_r2+=r*r; q[3].sum_g2+=g*g; q[3].sum_b2+=b*b;
                    ++q[3].count;
                }
            }

            // Find quadrant with minimum variance (sum of R/G/B variances)
            float min_var = 1e9f;
            int best = 0;
            for (int qi = 0; qi < 4; ++qi) {
                if (q[qi].count == 0) continue;
                float n = static_cast<float>(q[qi].count);
                float vr = q[qi].sum_r2/n - (q[qi].sum_r/n)*(q[qi].sum_r/n);
                float vg = q[qi].sum_g2/n - (q[qi].sum_g/n)*(q[qi].sum_g/n);
                float vb = q[qi].sum_b2/n - (q[qi].sum_b/n)*(q[qi].sum_b/n);
                float var = vr + vg + vb;
                if (var < min_var) { min_var = var; best = qi; }
            }

            float n = static_cast<float>(q[best].count);
            uint8_t r = static_cast<uint8_t>(std::clamp(q[best].sum_r / n, 0.0f, 255.0f));
            uint8_t g = static_cast<uint8_t>(std::clamp(q[best].sum_g / n, 0.0f, 255.0f));
            uint8_t b = static_cast<uint8_t>(std::clamp(q[best].sum_b / n, 0.0f, 255.0f));
            dst[y*w+x] = pack_argb(argb_a(src[y*w+x]), r, g, b);
        }
    }
}
