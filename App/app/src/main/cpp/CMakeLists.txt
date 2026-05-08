cmake_minimum_required(VERSION 3.22.1)
project("artgrid-native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ── Compiler flags ────────────────────────────────────────────────────────────
# -O2: safe optimisation level (avoids aggressive vectorisation bugs on Exynos)
# -ffast-math excluded intentionally: we need correct NaN/Inf handling in K-M solver
add_compile_options(
    -O2
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fPIC
)

# ── Source files ──────────────────────────────────────────────────────────────
add_library(
    artgrid-native
    SHARED
    artgrid_math.cpp      # Oklab/sRGB/XYZ conversions, core math utilities
    edge_pipeline.cpp     # Oklab-domain edge extraction
    perspective.cpp       # Corner detection + DLT homography warp
    color_sampler.cpp     # Chamfer-distance aperture colour sampling
    km_solver.cpp         # Kubelka-Munk 31-band spectral solver
    palette_quant.cpp     # Oklab median-cut + greedy merge palette
    image_filters.cpp     # Greyscale, heatmap, WB, invert, Kuwahara
    jni_bridge.cpp        # All JNI_* entry points — no business logic here
)

# ── Android system libraries ──────────────────────────────────────────────────
find_library(log-lib  log)
find_library(jnigraphics-lib  jnigraphics)  # android/bitmap.h

target_link_libraries(
    artgrid-native
    ${log-lib}
    ${jnigraphics-lib}
    # Android 15+ requires native libraries to support 16KB page alignment.
    # This flag ensures the .so LOAD segments are properly aligned for
    # both 4KB (legacy) and 16KB (Android 15+) page-size devices.
    -Wl,-z,max-page-size=16384
)

# ── Include paths ─────────────────────────────────────────────────────────────
target_include_directories(artgrid-native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
