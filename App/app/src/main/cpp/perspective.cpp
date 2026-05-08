/**
 * perspective.cpp · Novel Oklab-domain perspective corner detection
 *
 * Pipeline:
 *   1. Oklab L-channel + custom Sobel gradient (same as edge pipeline steps 1–3)
 *   2. Per-boundary argmax scan (top/bottom/left/right 20%)
 *   3. Orientation-confidence weighting per boundary
 *   4. Weighted PCA line fit + 2-pass σ outlier rejection per boundary
 *   5. Quadrilateral line intersection → 4 corner points
 *   6. DLT homography + bilinear warp to axis-aligned rectangle
 */
#include "artgrid_math.h"
#include <cmath>
#include <array>
#include <vector>
#include <algorithm>

// ── Robust line representation: ax + by = c ───────────────────────────────────

struct Line2D { float a, b, c; };  // ax + by = c

// Fit a line (ax + by = c) from centroid + PCA eigenvector.
// Eigenvector = direction of line → normal = (-vy, vx).
static Line2D make_line(float cx, float cy, Vec2 ev) noexcept {
    // Normal to line: n = (-ev.y, ev.x)
    float a = -ev.y, b = ev.x;
    float c = a * cx + b * cy;
    return {a, b, c};
}

// Intersect two lines to find corner point.
// Returns false if lines are parallel (det ~ 0).
static bool intersect_lines(const Line2D& l1, const Line2D& l2, float& ox, float& oy) noexcept {
    float det = l1.a * l2.b - l2.a * l1.b;
    if (std::abs(det) < 1e-6f) return false;
    ox = (l1.c * l2.b - l2.c * l1.b) / det;
    oy = (l1.a * l2.c - l2.a * l1.c) / det;
    return true;
}

// ── DLT homography: 4 correspondences src→dst ────────────────────────────────
//
// Uses the standard 4-point DLT (Hartley & Zisserman §4.1 from scratch).
// Builds an 8×9 matrix A, computes the null space by Gaussian elimination
// (stable enough for 4-point case without full SVD).

struct Mat3x3 { float m[9]; };   // row-major: m[r*3+c]

// Multiply 3×3 matrix by homogeneous (x,y,1), return (ox,oy,oz)
static void mat3_apply(const Mat3x3& M, float x, float y, float& ox, float& oy) noexcept {
    float z = M.m[6]*x + M.m[7]*y + M.m[8];
    if (std::abs(z) < 1e-8f) { ox = x; oy = y; return; }
    ox = (M.m[0]*x + M.m[1]*y + M.m[2]) / z;
    oy = (M.m[3]*x + M.m[4]*y + M.m[5]) / z;
}

static Mat3x3 compute_homography(
    float sx[4], float sy[4],   // source points
    float dx[4], float dy[4])   // destination (rectangle) points
{
    // Build 8×9 matrix A. Each point gives 2 equations.
    // Using standard DLT formulation.
    // For brevity we use a direct closed-form for 4-point H via Gaussian elimination.
    // The 9th unknown h33 is set to 1 (normalised).

    double A[8][9] = {};
    for (int i = 0; i < 4; ++i) {
        double xi = sx[i], yi = sy[i];
        double xi2 = dx[i], yi2 = dy[i];
        int row = 2 * i;
        // Equation 1: -xi*h31 - yi*h32 - h33 + xi2*... cancelled form:
        A[row][0]=xi;  A[row][1]=yi;  A[row][2]=1;
        A[row][3]=0;   A[row][4]=0;   A[row][5]=0;
        A[row][6]=-xi2*xi; A[row][7]=-xi2*yi; A[row][8]=-xi2;

        A[row+1][0]=0; A[row+1][1]=0; A[row+1][2]=0;
        A[row+1][3]=xi; A[row+1][4]=yi; A[row+1][5]=1;
        A[row+1][6]=-yi2*xi; A[row+1][7]=-yi2*yi; A[row+1][8]=-yi2;
    }

    // Move h[8] (= h33 normalised) to RHS: A[][8] becomes the RHS.
    // Solve 8×8 system for h[0..7], with h[8]=1.
    double rhs[8];
    for (int i = 0; i < 8; ++i) rhs[i] = -A[i][8];

    // Gaussian elimination with partial pivoting on 8×8
    double M[8][8];
    for (int i = 0; i < 8; ++i)
        for (int j = 0; j < 8; ++j)
            M[i][j] = A[i][j];

    for (int col = 0; col < 8; ++col) {
        // Pivot
        int pivot = col;
        for (int row = col+1; row < 8; ++row)
            if (std::abs(M[row][col]) > std::abs(M[pivot][col])) pivot = row;
        std::swap(M[col], M[pivot]);
        std::swap(rhs[col], rhs[pivot]);

        if (std::abs(M[col][col]) < 1e-12) continue;
        for (int row = col+1; row < 8; ++row) {
            double f = M[row][col] / M[col][col];
            for (int c = col; c < 8; ++c) M[row][c] -= f * M[col][c];
            rhs[row] -= f * rhs[col];
        }
    }

    // Back substitution
    double h[9];
    h[8] = 1.0;
    for (int i = 7; i >= 0; --i) {
        double s = rhs[i];
        for (int j = i+1; j < 8; ++j) s -= M[i][j] * h[j];
        h[i] = (std::abs(M[i][i]) < 1e-12) ? 0.0 : s / M[i][i];
    }

    Mat3x3 H;
    for (int k = 0; k < 9; ++k) H.m[k] = static_cast<float>(h[k]);
    return H;
}

// ── Bilinear warp using homography ────────────────────────────────────────────

static void warp_perspective(
    const uint32_t* src, int sw, int sh,
    uint32_t* dst, int dw, int dh,
    const Mat3x3& H_inv) noexcept     // H_inv maps dst→src
{
    for (int dy = 0; dy < dh; ++dy) {
        for (int dx = 0; dx < dw; ++dx) {
            float sx, sy;
            mat3_apply(H_inv, static_cast<float>(dx), static_cast<float>(dy), sx, sy);

            int x0 = static_cast<int>(sx), y0 = static_cast<int>(sy);
            int x1 = x0 + 1, y1 = y0 + 1;

            if (x0 < 0 || y0 < 0 || x1 >= sw || y1 >= sh) {
                dst[dy * dw + dx] = pack_argb(255, 0, 0, 0);
                continue;
            }

            float wx1 = sx - x0, wx0 = 1.0f - wx1;
            float wy1 = sy - y0, wy0 = 1.0f - wy1;

            auto lerp_ch = [&](auto ch_fn) -> uint8_t {
                float v = wx0*wy0*ch_fn(src[y0*sw+x0])
                        + wx1*wy0*ch_fn(src[y0*sw+x1])
                        + wx0*wy1*ch_fn(src[y1*sw+x0])
                        + wx1*wy1*ch_fn(src[y1*sw+x1]);
                return static_cast<uint8_t>(std::clamp(v, 0.0f, 255.0f));
            };
            dst[dy * dw + dx] = pack_argb(
                lerp_ch(argb_a), lerp_ch(argb_r),
                lerp_ch(argb_g), lerp_ch(argb_b));
        }
    }
}

// ── Weighted PCA line fit per boundary with 2-pass σ rejection ───────────────

static Line2D fit_boundary_line(
    const std::vector<float>& bx, const std::vector<float>& by,
    const std::vector<float>& bw) noexcept
{
    int n = static_cast<int>(bx.size());
    if (n < 2) return {1.0f, 0.0f, 0.0f};

    auto fit_once = [&](const std::vector<float>& px, const std::vector<float>& py,
                        const std::vector<float>& pw) -> std::pair<Vec2, Vec2> {
        float wsum = 0, cx = 0, cy = 0;
        for (int i = 0; i < static_cast<int>(px.size()); ++i) {
            cx += pw[i]*px[i]; cy += pw[i]*py[i]; wsum += pw[i];
        }
        if (wsum < 1e-8f) return {{1,0},{0,0}};
        cx /= wsum; cy /= wsum;
        Vec2 ev = weighted_pca_eigenvector(px.data(), py.data(), pw.data(),
                                           static_cast<int>(px.size()), cx, cy);
        return {ev, {cx, cy}};
    };

    auto reject_outliers = [&](const std::vector<float>& px, const std::vector<float>& py,
                               const std::vector<float>& pw, Vec2 ev, Vec2 cen,
                               std::vector<float>& ox, std::vector<float>& oy,
                               std::vector<float>& ow) {
        float perp_x = -ev.y, perp_y = ev.x;
        float sum_r2 = 0;
        std::vector<float> res(px.size());
        for (int i = 0; i < static_cast<int>(px.size()); ++i) {
            res[i] = std::abs((px[i]-cen.x)*perp_x + (py[i]-cen.y)*perp_y);
            sum_r2 += res[i]*res[i];
        }
        float sigma = std::sqrt(sum_r2 / px.size());
        for (int i = 0; i < static_cast<int>(px.size()); ++i) {
            if (res[i] <= 1.5f * sigma) { ox.push_back(px[i]); oy.push_back(py[i]); ow.push_back(pw[i]); }
        }
    };

    // Pass 1
    auto [ev1, cen1] = fit_once(bx, by, bw);
    std::vector<float> ox1, oy1, ow1;
    reject_outliers(bx, by, bw, ev1, cen1, ox1, oy1, ow1);

    // Pass 2 (re-fit on survivors)
    Vec2 ev_final = ev1; Vec2 cen_final = cen1;
    if (!ox1.empty()) {
        auto [ev2, cen2] = fit_once(ox1, oy1, ow1);
        std::vector<float> ox2, oy2, ow2;
        reject_outliers(ox1, oy1, ow1, ev2, cen2, ox2, oy2, ow2);
        if (!ox2.empty()) {
            auto [ev3, cen3] = fit_once(ox2, oy2, ow2);
            ev_final = ev3; cen_final = cen3;
        }
    }

    return make_line(cen_final.x, cen_final.y, ev_final);
}

// ── Main entry point ──────────────────────────────────────────────────────────

/**
 * perspective_correct_run
 * Detects 4 painting corners and warps the image to a frontal rectangle.
 * [argb_in]  : source ARGB8 pixels (w×h)
 * [argb_out] : pre-allocated w×h output buffer
 * Returns false if corners cannot be reliably detected (lines nearly parallel).
 */
bool perspective_correct_run(
    const uint32_t* argb_in, int w, int h,
    uint32_t* argb_out) noexcept
{
    const int N = w * h;

    // Step 1-3: Oklab L + gradient
    std::vector<float> L(N);
    {
        for (int i = 0; i < N; ++i) {
            uint32_t px = argb_in[i];
            L[i] = srgb8_to_oklab(argb_r(px), argb_g(px), argb_b(px)).x;
        }
    }
    int ksize = std::max(3, std::min(w, h) / 500);
    if (ksize % 2 == 0) ++ksize;
    separable_gaussian_blur(L.data(), w, h, ksize);

    std::vector<float> mag(N), theta(N);
    compute_gradient(L.data(), w, h, mag.data(), theta.data());

    // Step 2: Per-boundary candidate extraction + orientation weighting
    // Boundaries: top 20%, bottom 20%, left 20%, right 20%
    int top_h    = h / 5;
    int bot_start= h - top_h;
    int left_w   = w / 5;
    int right_start = w - left_w;

    // For each of 4 boundaries, collect (x, y, weight) candidates
    struct Boundary {
        std::vector<float> bx, by, bw;
        bool is_horizontal;  // top/bottom are horizontal (gradient should be vertical)
    };
    std::array<Boundary, 4> bounds;
    bounds[0].is_horizontal = true;   // top
    bounds[1].is_horizontal = true;   // bottom
    bounds[2].is_horizontal = false;  // left
    bounds[3].is_horizontal = false;  // right

    // Top boundary: scan rows [0, top_h), for each column take argmax
    for (int x = 0; x < w; ++x) {
        float best_m = -1; int best_y = 0;
        for (int y = 0; y < top_h; ++y) {
            if (mag[y*w+x] > best_m) { best_m = mag[y*w+x]; best_y = y; }
        }
        if (best_m < 1e-4f) continue;
        // Orientation-confidence weight for top boundary: |cos(θ)| (horizontal gradient = vertical edge)
        float w_conf = std::abs(std::cos(theta[best_y*w+x]));
        bounds[0].bx.push_back(static_cast<float>(x));
        bounds[0].by.push_back(static_cast<float>(best_y));
        bounds[0].bw.push_back(best_m * w_conf);
    }

    // Bottom boundary
    for (int x = 0; x < w; ++x) {
        float best_m = -1; int best_y = 0;
        for (int y = bot_start; y < h; ++y) {
            if (mag[y*w+x] > best_m) { best_m = mag[y*w+x]; best_y = y; }
        }
        if (best_m < 1e-4f) continue;
        float w_conf = std::abs(std::cos(theta[best_y*w+x]));
        bounds[1].bx.push_back(static_cast<float>(x));
        bounds[1].by.push_back(static_cast<float>(best_y));
        bounds[1].bw.push_back(best_m * w_conf);
    }

    // Left boundary: scan cols [0, left_w), for each row take argmax
    for (int y = 0; y < h; ++y) {
        float best_m = -1; int best_x = 0;
        for (int x = 0; x < left_w; ++x) {
            if (mag[y*w+x] > best_m) { best_m = mag[y*w+x]; best_x = x; }
        }
        if (best_m < 1e-4f) continue;
        float w_conf = std::abs(std::sin(theta[y*w+best_x]));  // vertical gradient
        bounds[2].bx.push_back(static_cast<float>(best_x));
        bounds[2].by.push_back(static_cast<float>(y));
        bounds[2].bw.push_back(best_m * w_conf);
    }

    // Right boundary
    for (int y = 0; y < h; ++y) {
        float best_m = -1; int best_x = 0;
        for (int x = right_start; x < w; ++x) {
            if (mag[y*w+x] > best_m) { best_m = mag[y*w+x]; best_x = x; }
        }
        if (best_m < 1e-4f) continue;
        float w_conf = std::abs(std::sin(theta[y*w+best_x]));
        bounds[3].bx.push_back(static_cast<float>(best_x));
        bounds[3].by.push_back(static_cast<float>(y));
        bounds[3].bw.push_back(best_m * w_conf);
    }

    // Step 4: Fit robust PCA line per boundary
    std::array<Line2D, 4> lines;
    for (int i = 0; i < 4; ++i) {
        if (bounds[i].bx.empty()) {
            // Fallback: image boundary line
            if (i == 0) lines[i] = {0,1,0};          // y=0
            else if (i == 1) lines[i] = {0,1,(float)h};    // y=h
            else if (i == 2) lines[i] = {1,0,0};          // x=0
            else             lines[i] = {1,0,(float)w};    // x=w
        } else {
            lines[i] = fit_boundary_line(bounds[i].bx, bounds[i].by, bounds[i].bw);
        }
    }

    // Step 5: Intersect line pairs to get 4 corners
    // top-left:     intersect(lines[0], lines[2])
    // top-right:    intersect(lines[0], lines[3])
    // bottom-left:  intersect(lines[1], lines[2])
    // bottom-right: intersect(lines[1], lines[3])
    float cx[4] = {}, cy[4] = {};
    const std::pair<int,int> pairs[4] = {{0,2},{0,3},{1,2},{1,3}};
    for (int i = 0; i < 4; ++i) {
        auto [li, lj] = pairs[i];
        if (!intersect_lines(lines[li], lines[lj], cx[i], cy[i])) {
            // Parallel lines — can't correct perspective, return source copy
            std::copy(argb_in, argb_in + N, argb_out);
            return false;
        }
    }

    // Step 6: DLT homography
    // Destination: axis-aligned rectangle (0,0) → (w-1, h-1)
    float dx[4] = {0, (float)(w-1), 0, (float)(w-1)};
    float dy[4] = {0, 0, (float)(h-1), (float)(h-1)};

    Mat3x3 H     = compute_homography(dx, dy, cx, cy);  // dst → src
    warp_perspective(argb_in, w, h, argb_out, w, h, H);
    return true;
}
