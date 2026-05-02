/**
 * artgrid_math.cpp
 * Implementations of shared math utilities declared in artgrid_math.h
 */
#include "artgrid_math.h"
#include <cstring>
#include <cassert>

// ── Bilinear downsample ───────────────────────────────────────────────────────

void bilinear_downsample(
    const uint32_t* src, int src_w, int src_h,
    uint32_t* dst, int dst_w, int dst_h) noexcept
{
    const float sx = static_cast<float>(src_w)  / dst_w;
    const float sy = static_cast<float>(src_h) / dst_h;

    for (int dy = 0; dy < dst_h; ++dy) {
        for (int dx = 0; dx < dst_w; ++dx) {
            float fx = (dx + 0.5f) * sx - 0.5f;
            float fy = (dy + 0.5f) * sy - 0.5f;

            int x0 = std::clamp(static_cast<int>(fx), 0, src_w - 1);
            int y0 = std::clamp(static_cast<int>(fy), 0, src_h - 1);
            int x1 = std::min(x0 + 1, src_w - 1);
            int y1 = std::min(y0 + 1, src_h - 1);

            float wx1 = fx - x0;  float wx0 = 1.0f - wx1;
            float wy1 = fy - y0;  float wy0 = 1.0f - wy1;

            auto lerp_ch = [&](auto ch_fn) -> uint8_t {
                float v = wx0 * wy0 * ch_fn(src[y0 * src_w + x0])
                        + wx1 * wy0 * ch_fn(src[y0 * src_w + x1])
                        + wx0 * wy1 * ch_fn(src[y1 * src_w + x0])
                        + wx1 * wy1 * ch_fn(src[y1 * src_w + x1]);
                return static_cast<uint8_t>(std::clamp(v, 0.0f, 255.0f));
            };

            uint8_t a = lerp_ch(argb_a);
            uint8_t r = lerp_ch(argb_r);
            uint8_t g = lerp_ch(argb_g);
            uint8_t b = lerp_ch(argb_b);
            dst[dy * dst_w + dx] = pack_argb(a, r, g, b);
        }
    }
}

// ── Gaussian kernel ───────────────────────────────────────────────────────────

std::vector<float> make_gaussian_kernel(int size) noexcept {
    // size must be odd and ≥ 3
    if (size < 3) size = 3;
    if (size % 2 == 0) ++size;

    float sigma = size / 3.0f;
    float inv2s2 = 1.0f / (2.0f * sigma * sigma);
    int half = size / 2;

    std::vector<float> k(size);
    float sum = 0.0f;
    for (int i = 0; i < size; ++i) {
        float d = static_cast<float>(i - half);
        k[i] = std::exp(-d * d * inv2s2);
        sum += k[i];
    }
    for (auto& v : k) v /= sum;
    return k;
}

// ── Separable Gaussian blur ───────────────────────────────────────────────────

void separable_gaussian_blur(float* data, int w, int h, int kernel_size) noexcept {
    auto k = make_gaussian_kernel(kernel_size);
    int half = kernel_size / 2;
    std::vector<float> tmp(w * h);

    // Horizontal pass
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float acc = 0.0f;
            for (int ki = 0; ki < kernel_size; ++ki) {
                int sx = std::clamp(x + ki - half, 0, w - 1);
                acc += k[ki] * data[y * w + sx];
            }
            tmp[y * w + x] = acc;
        }
    }

    // Vertical pass (read from tmp, write to data)
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float acc = 0.0f;
            for (int ki = 0; ki < kernel_size; ++ki) {
                int sy = std::clamp(y + ki - half, 0, h - 1);
                acc += k[ki] * tmp[sy * w + x];
            }
            data[y * w + x] = acc;
        }
    }
}

// ── Custom Sobel-style gradient (3×3 Taylor-expansion kernel) ────────────────
//
// Gx kernel (horizontal derivative):
//   +1  0  -1
//   +2  0  -2
//   +1  0  -1
// Gy kernel (vertical derivative):
//   +1  +2  +1
//    0   0   0
//   -1  -2  -1

void compute_gradient(
    const float* L, int w, int h,
    float* mag, float* theta) noexcept
{
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            // Clamp indices to image border
            int xm = std::max(x - 1, 0);
            int xp = std::min(x + 1, w - 1);
            int ym = std::max(y - 1, 0);
            int yp = std::min(y + 1, h - 1);

            float tl = L[ym * w + xm], tm = L[ym * w + x], tr = L[ym * w + xp];
            float ml = L[y  * w + xm],                      mr = L[y  * w + xp];
            float bl = L[yp * w + xm], bm = L[yp * w + x], br = L[yp * w + xp];

            float gx = (tr + 2.0f * mr + br) - (tl + 2.0f * ml + bl);
            float gy = (bl + 2.0f * bm + br) - (tl + 2.0f * tm + tr);

            int idx = y * w + x;
            mag[idx]   = std::sqrt(gx * gx + gy * gy);
            theta[idx] = std::atan2(gy, gx);  // [-π, π]
        }
    }
}

// ── Weighted PCA eigenvector (2-step power iteration) ────────────────────────

Vec2 weighted_pca_eigenvector(
    const float* px, const float* py, const float* wt,
    int n, float cx, float cy) noexcept
{
    if (n < 2) return {1.0f, 0.0f};

    // Build 2×2 weighted covariance matrix
    float cxx = 0, cxy = 0, cyy = 0, wsum = 0;
    for (int i = 0; i < n; ++i) {
        float dx = px[i] - cx;
        float dy = py[i] - cy;
        float w  = wt[i];
        cxx += w * dx * dx;
        cxy += w * dx * dy;
        cyy += w * dy * dy;
        wsum += w;
    }
    if (wsum < 1e-8f) return {1.0f, 0.0f};
    cxx /= wsum; cxy /= wsum; cyy /= wsum;

    // 2-step power iteration starting from (1, 0)
    float vx = 1.0f, vy = 0.0f;
    for (int iter = 0; iter < 2; ++iter) {
        float nx = cxx * vx + cxy * vy;
        float ny = cxy * vx + cyy * vy;
        float len = std::sqrt(nx * nx + ny * ny);
        if (len < 1e-8f) break;
        vx = nx / len;
        vy = ny / len;
    }
    return {vx, vy};
}

// ── Median ────────────────────────────────────────────────────────────────────

float vec_median(std::vector<float>& v) noexcept {
    if (v.empty()) return 0.0f;
    size_t mid = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + mid, v.end());
    return v[mid];
}
