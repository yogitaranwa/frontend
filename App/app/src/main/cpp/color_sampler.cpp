/**
 * color_sampler.cpp  ·  F-12 · Chamfer-distance aperture colour sampling
 *
 * For a circular aperture of radius r centred at (cx, cy):
 *   1. Build a binary aperture mask
 *   2. Run Chamfer 3-4 distance transform to find the pixel geometrically
 *      deepest inside the aperture (maximum distance from the aperture edge)
 *   3. Average pixel colours in the aperture, weighted by Chamfer distance
 *      (centre-biased weighting avoids edge-colour contamination)
 *   4. Return sampled colour + derived colour-space representations
 */
#include "artgrid_math.h"
#include <vector>
#include <cmath>
#include <algorithm>
#include <limits>

// ── Chamfer 3-4 distance transform (Manhattan metric variant) ─────────────────
//
// Uses the classic two-pass (top-left → bottom-right, then bottom-right → top-left)
// Chamfer 3-4 approximation of Euclidean distance.
// Cost: 3 for cardinal steps, 4 for diagonal steps (integer arithmetic).
// Result is an integer distance map in units of 1/3 pixel.

static std::vector<int> chamfer_34(const std::vector<bool>& mask, int w, int h) noexcept {
    std::vector<int> dist(w * h, 0);

    // Initialise: inside mask = large value, outside = 0
    constexpr int INF = 1 << 20;
    for (int i = 0; i < w * h; ++i)
        dist[i] = mask[i] ? INF : 0;

    // Forward pass (top-left → bottom-right)
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            if (!mask[y * w + x]) continue;
            int best = INF;
            // Up
            if (y > 0)
                best = std::min(best, dist[(y-1)*w+x] + 3);
            // Left
            if (x > 0)
                best = std::min(best, dist[y*w+(x-1)] + 3);
            // Up-left
            if (y > 0 && x > 0)
                best = std::min(best, dist[(y-1)*w+(x-1)] + 4);
            // Up-right
            if (y > 0 && x < w-1)
                best = std::min(best, dist[(y-1)*w+(x+1)] + 4);
            dist[y*w+x] = best;
        }
    }

    // Backward pass (bottom-right → top-left)
    for (int y = h-1; y >= 0; --y) {
        for (int x = w-1; x >= 0; --x) {
            if (!mask[y * w + x]) continue;
            int best = dist[y*w+x];
            // Down
            if (y < h-1)
                best = std::min(best, dist[(y+1)*w+x] + 3);
            // Right
            if (x < w-1)
                best = std::min(best, dist[y*w+(x+1)] + 3);
            // Down-right
            if (y < h-1 && x < w-1)
                best = std::min(best, dist[(y+1)*w+(x+1)] + 4);
            // Down-left
            if (y < h-1 && x > 0)
                best = std::min(best, dist[(y+1)*w+(x-1)] + 4);
            dist[y*w+x] = best;
        }
    }

    return dist;
}

// ── Colour sample result ──────────────────────────────────────────────────────

struct ColorSample {
    // sRGB (quantised to uint8)
    uint8_t r, g, b;
    // Oklab
    float oklab_L, oklab_a, oklab_b;
    // HSL
    float hsl_h, hsl_s, hsl_l;   // H in [0,360), S and L in [0,1]
    // Perceived colour temperature estimate (Kelvin, 1667–10000)
    float kelvin;
    // Lightness percentage (0–100)
    float lightness_pct;
};

// sRGB uint8 → HSL
static void rgb_to_hsl(uint8_t ri, uint8_t gi, uint8_t bi,
                        float& h, float& s, float& l) noexcept {
    float r = ri / 255.0f, g = gi / 255.0f, b = bi / 255.0f;
    float mx = std::max({r, g, b}), mn = std::min({r, g, b});
    float delta = mx - mn;
    l = (mx + mn) / 2.0f;

    if (delta < 1e-6f) { h = s = 0.0f; return; }

    s = (l > 0.5f) ? delta / (2.0f - mx - mn) : delta / (mx + mn);

    if      (mx == r) h = std::fmod((g - b) / delta, 6.0f);
    else if (mx == g) h = (b - r) / delta + 2.0f;
    else              h = (r - g) / delta + 4.0f;
    h = std::fmod(h * 60.0f + 360.0f, 360.0f);
}

// Approximate colour temperature from CIE xy chromaticity (McCamy's formula)
static float xy_to_kelvin(float x, float y) noexcept {
    float n = (x - 0.3320f) / (y - 0.1858f);
    float K = -449.0f * n*n*n + 3525.0f * n*n - 6823.3f * n + 5520.33f;
    return std::clamp(K, 1667.0f, 10000.0f);
}

// XYZ D65 → CEI xy chromaticity
static void xyz_to_xy(float X, float Y, float Z, float& x, float& y) noexcept {
    float sum = X + Y + Z;
    if (sum < 1e-8f) { x = 0.3127f; y = 0.3290f; return; }   // D65 white
    x = X / sum;
    y = Y / sum;
}

// ── Main entry point ──────────────────────────────────────────────────────────

/**
 * sample_color
 * Samples the average colour inside a circular aperture of [radius] pixels
 * centred at (cx, cy) in the [argb] image (w×h).
 * Weight per pixel = Chamfer distance (centre-biased, avoids edge contamination).
 * Returns a ColorSample with all derived colour-space values filled in.
 */
ColorSample sample_color(
    const uint32_t* argb, int w, int h,
    int cx, int cy, int radius) noexcept
{
    // Clamp centre to image bounds
    cx = std::clamp(cx, 0, w - 1);
    cy = std::clamp(cy, 0, h - 1);
    radius = std::clamp(radius, 1, std::min(w, h) / 2);

    // Bounding box of aperture (clamped to image)
    int x0 = std::max(0, cx - radius);
    int y0 = std::max(0, cy - radius);
    int x1 = std::min(w - 1, cx + radius);
    int y1 = std::min(h - 1, cy + radius);
    int bw = x1 - x0 + 1, bh = y1 - y0 + 1;

    // Build circular mask in the bounding box
    std::vector<bool> mask(bw * bh, false);
    float r2 = static_cast<float>(radius * radius);
    for (int y = 0; y < bh; ++y) {
        for (int x = 0; x < bw; ++x) {
            float dx = (x0 + x) - cx, dy = (y0 + y) - cy;
            if (dx*dx + dy*dy <= r2) mask[y*bw+x] = true;
        }
    }

    // Chamfer distance transform on the mask
    auto dist = chamfer_34(mask, bw, bh);

    // Weighted average in linear-light space (avoids sum-of-sRGB gamma error)
    double wr = 0, wg = 0, wb = 0, wsum = 0;
    for (int y = 0; y < bh; ++y) {
        for (int x = 0; x < bw; ++x) {
            if (!mask[y*bw+x]) continue;
            float wt = static_cast<float>(dist[y*bw+x]);
            uint32_t px = argb[(y0+y)*w + (x0+x)];
            wr += wt * srgb_to_linear(argb_r(px) / 255.0f);
            wg += wt * srgb_to_linear(argb_g(px) / 255.0f);
            wb += wt * srgb_to_linear(argb_b(px) / 255.0f);
            wsum += wt;
        }
    }

    if (wsum < 1e-8f) {
        // Fallback: sample centre pixel directly
        uint32_t px = argb[cy * w + cx];
        wsum = 1.0; wr = srgb_to_linear(argb_r(px)/255.0f);
        wg = srgb_to_linear(argb_g(px)/255.0f); wb = srgb_to_linear(argb_b(px)/255.0f);
    }

    float rl = static_cast<float>(wr / wsum);
    float gl = static_cast<float>(wg / wsum);
    float bl = static_cast<float>(wb / wsum);

    // Convert to all colour spaces
    ColorSample result;
    result.r = static_cast<uint8_t>(std::clamp(linear_to_srgb(rl) * 255.0f, 0.0f, 255.0f));
    result.g = static_cast<uint8_t>(std::clamp(linear_to_srgb(gl) * 255.0f, 0.0f, 255.0f));
    result.b = static_cast<uint8_t>(std::clamp(linear_to_srgb(bl) * 255.0f, 0.0f, 255.0f));

    auto [X, Y, Z] = linear_rgb_to_xyz(rl, gl, bl);
    auto [L, a, b] = xyz_to_oklab(X, Y, Z);
    result.oklab_L = L; result.oklab_a = a; result.oklab_b = b;

    rgb_to_hsl(result.r, result.g, result.b, result.hsl_h, result.hsl_s, result.hsl_l);
    result.lightness_pct = L * 100.0f;

    float chrx, chry;
    xyz_to_xy(X, Y, Z, chrx, chry);
    result.kelvin = xy_to_kelvin(chrx, chry);

    return result;
}
