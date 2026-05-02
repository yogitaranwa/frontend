/**
 * palette_quant.cpp  ·  F-14 · Oklab median-cut + greedy merge palette quantisation
 *
 * Pipeline:
 *   1. Downsample to 200×200 via bilinear interpolation
 *   2. Convert all pixels to Oklab
 *   3. Over-quantise: recursive median-cut in Oklab (L/a/b) space
 *      Split axis = widest IQR (inter-quartile range, outlier-resistant)
 *      Target: 4×N initial buckets
 *   4. Greedy merge: min-heap of pair costs = 0.7·(1−cosine_sim) + 0.3·norm_euclid
 *      Merge until N buckets remain
 *   5. Return centroid of each final bucket (Oklab → sRGB ARGB)
 */
#include "artgrid_math.h"
#include <vector>
#include <array>
#include <algorithm>
#include <cmath>
#include <queue>
#include <functional>

// ── Oklab pixel bucket ────────────────────────────────────────────────────────

struct OklabPixel { float L, a, b; };

// ── IQR computation on a float vector (25th–75th percentile range) ────────────

static float compute_iqr(std::vector<float>& vals) noexcept {
    if (vals.size() < 4) return 0.0f;
    std::sort(vals.begin(), vals.end());
    size_t n = vals.size();
    float q1 = vals[n / 4];
    float q3 = vals[3 * n / 4];
    return q3 - q1;
}

// ── Recursive median-cut in Oklab space ──────────────────────────────────────

struct Bucket {
    std::vector<int> pixel_indices;   // indices into the pixels vector
    OklabPixel centroid;
};

static OklabPixel compute_centroid(
    const std::vector<OklabPixel>& pixels,
    const std::vector<int>& indices) noexcept
{
    if (indices.empty()) return {0,0,0};
    double L=0, a=0, b=0;
    for (int i : indices) { L += pixels[i].L; a += pixels[i].a; b += pixels[i].b; }
    float n = static_cast<float>(indices.size());
    return {static_cast<float>(L/n), static_cast<float>(a/n), static_cast<float>(b/n)};
}

static void median_cut(
    const std::vector<OklabPixel>& pixels,
    std::vector<int> indices,
    int target_count,
    std::vector<Bucket>& result)
{
    if (target_count <= 1 || static_cast<int>(indices.size()) <= 1) {
        Bucket b;
        b.pixel_indices = std::move(indices);
        b.centroid = compute_centroid(pixels, b.pixel_indices);
        result.push_back(std::move(b));
        return;
    }

    // Compute IQR for each Oklab channel to find the split axis
    std::vector<float> Ls, as_, bs;
    for (int i : indices) {
        Ls.push_back(pixels[i].L);
        as_.push_back(pixels[i].a);
        bs.push_back(pixels[i].b);
    }
    float iqr_L = compute_iqr(Ls);
    float iqr_a = compute_iqr(as_);
    float iqr_b = compute_iqr(bs);

    // Split on the axis with the widest IQR
    int axis = 0;
    float max_iqr = iqr_L;
    if (iqr_a > max_iqr) { max_iqr = iqr_a; axis = 1; }
    if (iqr_b > max_iqr) { axis = 2; }

    // Sort by chosen axis, split at median
    std::sort(indices.begin(), indices.end(), [&](int i, int j) {
        if (axis == 0) return pixels[i].L < pixels[j].L;
        if (axis == 1) return pixels[i].a < pixels[j].a;
        return pixels[i].b < pixels[j].b;
    });

    size_t mid = indices.size() / 2;
    std::vector<int> lo(indices.begin(), indices.begin() + mid);
    std::vector<int> hi(indices.begin() + mid, indices.end());

    int half = target_count / 2;
    median_cut(pixels, std::move(lo), half,               result);
    median_cut(pixels, std::move(hi), target_count - half, result);
}

// ── Greedy merge using a min-heap ─────────────────────────────────────────────

static float oklab_cosine_sim(const OklabPixel& a, const OklabPixel& b) noexcept {
    float dot = a.L*b.L + a.a*b.a + a.b*b.b;
    float na  = std::sqrt(a.L*a.L + a.a*a.a + a.b*a.b);
    float nb  = std::sqrt(b.L*b.L + b.a*b.a + b.b*b.b);
    if (na < 1e-8f || nb < 1e-8f) return 0.0f;
    return dot / (na * nb);
}

static float oklab_norm_euclid(const OklabPixel& a, const OklabPixel& b) noexcept {
    float dL = a.L - b.L, da = a.a - b.a, db = a.b - b.b;
    // Oklab L ~ [0,1], a ~ [-0.5,0.5], b ~ [-0.5,0.5]. Normalise by estimated max range.
    return std::sqrt(dL*dL + da*da + db*db) / 1.5f;
}

static float merge_cost(const OklabPixel& a, const OklabPixel& b) noexcept {
    return 0.7f * (1.0f - oklab_cosine_sim(a, b))
         + 0.3f * oklab_norm_euclid(a, b);
}

struct MergePair {
    float cost;
    int i, j;
    bool operator>(const MergePair& o) const { return cost > o.cost; }
};

static std::vector<OklabPixel> greedy_merge(
    std::vector<Bucket>& buckets, int target_n) noexcept
{
    int nb = static_cast<int>(buckets.size());
    if (nb <= target_n) {
        std::vector<OklabPixel> out;
        for (auto& b : buckets) out.push_back(b.centroid);
        return out;
    }

    // Build active flag + min-heap of all pairs
    std::vector<bool> active(nb, true);
    std::vector<OklabPixel> cents(nb);
    for (int i = 0; i < nb; ++i) cents[i] = buckets[i].centroid;

    std::priority_queue<MergePair, std::vector<MergePair>, std::greater<MergePair>> heap;
    for (int i = 0; i < nb; ++i)
        for (int j = i+1; j < nb; ++j)
            heap.push({merge_cost(cents[i], cents[j]), i, j});

    int active_count = nb;
    while (active_count > target_n && !heap.empty()) {
        auto [cost, i, j] = heap.top(); heap.pop();
        if (!active[i] || !active[j]) continue;

        // Merge j into i (weighted by pixel count)
        size_t ni = buckets[i].pixel_indices.size();
        size_t nj = buckets[j].pixel_indices.size();
        float fn = static_cast<float>(ni + nj);

        cents[i] = {
            (cents[i].L * ni + cents[j].L * nj) / fn,
            (cents[i].a * ni + cents[j].a * nj) / fn,
            (cents[i].b * ni + cents[j].b * nj) / fn,
        };
        for (int idx : buckets[j].pixel_indices)
            buckets[i].pixel_indices.push_back(idx);

        active[j] = false;
        --active_count;

        // Add new pair costs for merged bucket i vs remaining
        for (int k = 0; k < nb; ++k) {
            if (!active[k] || k == i) continue;
            heap.push({merge_cost(cents[i], cents[k]), i, k});
        }
    }

    std::vector<OklabPixel> result;
    for (int i = 0; i < nb; ++i)
        if (active[i]) result.push_back(cents[i]);
    return result;
}

// ── Main entry point ──────────────────────────────────────────────────────────

/**
 * palette_quantise
 * Extracts [n_colors] dominant perceptual colours from [argb] (w×h).
 * Returns ARGB8-packed sRGB colour values for each palette entry.
 * [n_colors] must be in [2..12].
 */
std::vector<uint32_t> palette_quantise(
    const uint32_t* argb, int w, int h, int n_colors) noexcept
{
    n_colors = std::clamp(n_colors, 2, 12);

    // Step 1: Downsample to 200×200
    constexpr int THUMB_W = 200, THUMB_H = 200;
    std::vector<uint32_t> thumb(THUMB_W * THUMB_H);
    bilinear_downsample(argb, w, h, thumb.data(), THUMB_W, THUMB_H);

    // Step 2: Convert to Oklab
    const int N = THUMB_W * THUMB_H;
    std::vector<OklabPixel> pixels(N);
    for (int i = 0; i < N; ++i) {
        uint32_t px = thumb[i];
        Vec3 lab = srgb8_to_oklab(argb_r(px), argb_g(px), argb_b(px));
        pixels[i] = {lab.x, lab.y, lab.z};
    }

    // Step 3: Over-quantise to 4×n_colors buckets via median-cut
    std::vector<int> all_indices(N);
    std::iota(all_indices.begin(), all_indices.end(), 0);
    std::vector<Bucket> buckets;
    buckets.reserve(4 * n_colors);
    median_cut(pixels, std::move(all_indices), 4 * n_colors, buckets);

    // Step 4: Greedy merge to n_colors
    auto centroids = greedy_merge(buckets, n_colors);

    // Step 5: Oklab centroid → sRGB ARGB8
    std::vector<uint32_t> palette;
    palette.reserve(centroids.size());
    for (auto& c : centroids)
        palette.push_back(oklab_to_argb8(c.L, c.a, c.b));

    return palette;
}
