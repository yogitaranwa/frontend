/**
 * edge_pipeline.cpp · Novel Oklab-domain edge extraction
 *
 * Pipeline (all 6 steps implemented from first principles):
 *   1. ARGB bitmap → Oklab L-channel
 *   2. Adaptive separable Gaussian smoothing
 *   3. Custom Taylor-expansion gradient (G magnitude + θ orientation)
 *   4. 8-bin non-maximum suppression
 *   5. Lightness-aware threshold: T = median(G_nonzero) + λ × (L_mean/100)
 *   6. Weighted PCA line fitting with 1-pass σ-outlier rejection per component
 *
 * Returns: ARGB edge image (white lines on black, or overlaid on source).
 */
#include "artgrid_math.h"
#include <cmath>
#include <vector>
#include <algorithm>
#include <queue>

// ── Step 1: ARGB → Oklab L channel ───────────────────────────────────────────

static void extract_oklab_L(
    const uint32_t* argb, int w, int h,
    float* L_out, float& L_mean_out) noexcept
{
    double sum = 0.0;
    for (int i = 0; i < w * h; ++i) {
        uint32_t px = argb[i];
        Vec3 lab = srgb8_to_oklab(argb_r(px), argb_g(px), argb_b(px));
        L_out[i] = lab.x;   // Oklab L is in [0,1]
        sum += lab.x;
    }
    L_mean_out = static_cast<float>(sum / (w * h));
}

// ── Step 4: 8-bin non-maximum suppression ─────────────────────────────────────
//
// Quantises θ into 8 bins (every π/8 = 22.5°), then suppresses any pixel
// whose gradient magnitude is not the local maximum along the gradient direction.

static void nms_8bin(
    const float* mag, const float* theta, int w, int h,
    float* nms_out) noexcept
{
    // 8-directional offsets matching the 8 orientation bins:
    // bin 0 (0°/180°): horizontal  → neighbours (x±1, y)
    // bin 1 (22.5°):               → neighbours (x+1, y-1) and (x-1, y+1)
    // …etc.
    static const int dx8[8] = { 1,  1,  0, -1, -1, -1,  0,  1};
    static const int dy8[8] = { 0, -1, -1, -1,  0,  1,  1,  1};

    const float PI = 3.14159265f;

    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            int i = y * w + x;
            float m = mag[i];

            if (m < 1e-6f) { nms_out[i] = 0.0f; continue; }

            // Map θ ∈ (-π, π] → bin ∈ [0,7]
            float angle = theta[i];
            if (angle < 0) angle += PI;     // fold to [0, π]
            int bin = static_cast<int>(std::round(angle / (PI / 8.0f))) % 8;

            int nx1 = std::clamp(x + dx8[bin],        0, w - 1);
            int ny1 = std::clamp(y + dy8[bin],        0, h - 1);
            int nx2 = std::clamp(x - dx8[bin],        0, w - 1);
            int ny2 = std::clamp(y - dy8[bin],        0, h - 1);

            float n1 = mag[ny1 * w + nx1];
            float n2 = mag[ny2 * w + nx2];

            nms_out[i] = (m >= n1 && m >= n2) ? m : 0.0f;
        }
    }
}

// ── Steps 5 + 6: Threshold + connected components + PCA line fitting ─────────

struct EdgePoint {
    float x, y, weight;    // weight = gradient magnitude
    int label;
};

// Flood-fill connected component labelling on binary edge map (4-connected).
static std::vector<int> label_components(
    const float* edge_mask, int w, int h,
    int& num_components) noexcept
{
    std::vector<int> labels(w * h, -1);
    num_components = 0;
    std::queue<int> q;

    for (int i = 0; i < w * h; ++i) {
        if (edge_mask[i] < 1e-6f || labels[i] >= 0) continue;

        labels[i] = num_components;
        q.push(i);
        while (!q.empty()) {
            int cur = q.front(); q.pop();
            int cx = cur % w, cy = cur / w;

            static const int dx4[] = {1, -1, 0,  0};
            static const int dy4[] = {0,  0, 1, -1};
            for (int d = 0; d < 4; ++d) {
                int nx = cx + dx4[d], ny = cy + dy4[d];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                int ni = ny * w + nx;
                if (edge_mask[ni] < 1e-6f || labels[ni] >= 0) continue;
                labels[ni] = num_components;
                q.push(ni);
            }
        }
        ++num_components;
    }
    return labels;
}

// Fit a PCA line to a component, discard outliers > 1.5σ residual, refit once.
// Returns rendered line pixels in [out_edges].
static void fit_pca_line(
    const std::vector<EdgePoint>& pts,
    std::vector<uint32_t>& pixel_set,   // output: list of (y*w+x) pixels to light
    int w, int h) noexcept
{
    int n = static_cast<int>(pts.size());
    if (n < 10) {
        // Small component — just keep the raw pixels
        for (auto& p : pts) {
            int xi = static_cast<int>(p.x), yi = static_cast<int>(p.y);
            if (xi >= 0 && xi < w && yi >= 0 && yi < h)
                pixel_set.push_back(static_cast<uint32_t>(yi * w + xi));
        }
        return;
    }

    // Compute weighted centroid
    float wsum = 0, cx = 0, cy = 0;
    for (auto& p : pts) { cx += p.weight * p.x; cy += p.weight * p.y; wsum += p.weight; }
    if (wsum < 1e-8f) return;
    cx /= wsum; cy /= wsum;

    std::vector<float> px(n), py(n), pw(n);
    for (int i = 0; i < n; ++i) { px[i] = pts[i].x; py[i] = pts[i].y; pw[i] = pts[i].weight; }

    // First PCA fit
    Vec2 ev = weighted_pca_eigenvector(px.data(), py.data(), pw.data(), n, cx, cy);

    // Compute residuals (perpendicular distance to fitted line)
    float perp_x = -ev.y, perp_y = ev.x;
    std::vector<float> residuals(n);
    float sum_r2 = 0;
    for (int i = 0; i < n; ++i) {
        float dx = px[i] - cx, dy = py[i] - cy;
        residuals[i] = std::abs(dx * perp_x + dy * perp_y);
        sum_r2 += residuals[i] * residuals[i];
    }
    float sigma = std::sqrt(sum_r2 / n);

    // Reject outliers > 1.5σ and refit
    std::vector<float> px2, py2, pw2;
    for (int i = 0; i < n; ++i) {
        if (residuals[i] <= 1.5f * sigma) {
            px2.push_back(px[i]); py2.push_back(py[i]); pw2.push_back(pw[i]);
        }
    }

    if (static_cast<int>(px2.size()) >= 4) {
        // Recompute centroid on survivors
        float wsum2 = 0, cx2 = 0, cy2 = 0;
        for (int i = 0; i < static_cast<int>(px2.size()); ++i) {
            cx2 += pw2[i] * px2[i]; cy2 += pw2[i] * py2[i]; wsum2 += pw2[i];
        }
        cx2 /= wsum2; cy2 /= wsum2;
        ev = weighted_pca_eigenvector(px2.data(), py2.data(), pw2.data(),
                                       static_cast<int>(px2.size()), cx2, cy2);

        // Find extent of survivors along eigenvector
        float t_min = 1e9f, t_max = -1e9f;
        for (int i = 0; i < static_cast<int>(px2.size()); ++i) {
            float t = (px2[i] - cx2) * ev.x + (py2[i] - cy2) * ev.y;
            t_min = std::min(t_min, t);
            t_max = std::max(t_max, t);
        }

        // Rasterise line from t_min to t_max in unit steps (Bresenham-style)
        float seg_len = t_max - t_min;
        int steps = std::max(1, static_cast<int>(std::ceil(seg_len)));
        for (int s = 0; s <= steps; ++s) {
            float t = t_min + (t_max - t_min) * s / steps;
            int xi = static_cast<int>(std::round(cx2 + t * ev.x));
            int yi = static_cast<int>(std::round(cy2 + t * ev.y));
            if (xi >= 0 && xi < w && yi >= 0 && yi < h)
                pixel_set.push_back(static_cast<uint32_t>(yi * w + xi));
        }
    } else {
        // Fallback: keep raw points
        for (auto& p : pts) {
            int xi = static_cast<int>(p.x), yi = static_cast<int>(p.y);
            if (xi >= 0 && xi < w && yi >= 0 && yi < h)
                pixel_set.push_back(static_cast<uint32_t>(yi * w + xi));
        }
    }
}

// ── Main entry point ──────────────────────────────────────────────────────────

/**
 * Runs the edge pipeline on [argb_in] (w×h ARGB8 pixels).
 * [sensitivity_lambda] ∈ [0.5, 3.0] — higher = more edges detected.
 * [overlay_on_source]  — true: draw edges over source; false: white-on-black.
 * Returns ARGB8 result in [argb_out] (same dimensions).
 * argb_out must be pre-allocated to w*h elements.
 */
void edge_pipeline_run(
    const uint32_t* argb_in, int w, int h,
    float sensitivity_lambda,
    bool overlay_on_source,
    uint32_t* argb_out) noexcept
{
    const int N = w * h;

    // Step 1: Oklab L channel
    std::vector<float> L(N);
    float L_mean = 0;
    extract_oklab_L(argb_in, w, h, L.data(), L_mean);

    // Step 2: Adaptive Gaussian smoothing
    // Kernel size = max(3, shorter_side/500), rounded to nearest odd integer
    int shorter = std::min(w, h);
    int ksize = std::max(3, shorter / 500);
    if (ksize % 2 == 0) ++ksize;
    separable_gaussian_blur(L.data(), w, h, ksize);

    // Step 3: Gradient field
    std::vector<float> mag(N), theta(N);
    compute_gradient(L.data(), w, h, mag.data(), theta.data());

    // Step 4: 8-bin NMS
    std::vector<float> nms(N);
    nms_8bin(mag.data(), theta.data(), w, h, nms.data());

    // Step 5: Lightness-aware threshold
    std::vector<float> nonzero_mags;
    nonzero_mags.reserve(N / 4);
    for (int i = 0; i < N; ++i)
        if (nms[i] > 1e-6f) nonzero_mags.push_back(nms[i]);

    float threshold = 0.0f;
    if (!nonzero_mags.empty()) {
        float med = vec_median(nonzero_mags);
        threshold = med + sensitivity_lambda * (L_mean / 1.0f) * 0.05f;
        // Oklab L is in [0,1]; L_mean/100 from spec rescaled accordingly
    }

    std::vector<float> edge_mask(N, 0.0f);
    for (int i = 0; i < N; ++i)
        edge_mask[i] = (nms[i] >= threshold) ? nms[i] : 0.0f;

    // Step 6: Connected components + PCA line fitting
    int num_components = 0;
    auto labels = label_components(edge_mask.data(), w, h, num_components);

    if (num_components > 0) {
        std::vector<std::vector<EdgePoint>> components(num_components);
        for (int i = 0; i < N; ++i) {
            if (labels[i] < 0) continue;
            components[labels[i]].push_back({
                static_cast<float>(i % w),
                static_cast<float>(i / w),
                edge_mask[i],
                labels[i]
            });
        }

        // Collect all lit pixels from PCA fitting
        std::vector<uint32_t> lit_pixels;
        lit_pixels.reserve(N / 8);

        for (auto& comp : components)
            fit_pca_line(comp, lit_pixels, w, h);

        // Render output
        if (overlay_on_source) {
            std::copy(argb_in, argb_in + N, argb_out);
        } else {
            std::fill(argb_out, argb_out + N, pack_argb(255, 0, 0, 0)); // black background
        }
        for (uint32_t idx : lit_pixels) {
            if (idx < static_cast<uint32_t>(N))
                argb_out[idx] = pack_argb(255, 255, 255, 255);  // white edge pixels
        }
    } else {
        // No edges found — return black or source
        if (overlay_on_source) std::copy(argb_in, argb_in + N, argb_out);
        else std::fill(argb_out, argb_out + N, pack_argb(255, 0, 0, 0));
    }
}
