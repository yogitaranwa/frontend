# ArtGrid — Technical Documentation

## Project Overview

**ArtGrid** is a full-stack **Android** application for artists and learners who work from reference photos. In plain terms it acts like a **digital proportional divider**: you mark structure on the reference (often with **ML-assisted** landmarks or boxes), and the client turns those **pixel spans** into **centimetre-aligned** overlays for a chosen **physical paper size**. Technically it combines **on-device C++ image math** (JNI) with **cloud ML inference** (face landmarks, unified human + anime-style face fusion, object detection, U²-Net segmentation) and an **SSE-streamed Gemini** “AI Artist” chat.

The backend is split into three **microservices** (auth, ML, AI proxy), each independently deployable (`docker compose` locally; production patterns below).

**Production topology (typical in-repo setup):** **Google OAuth** and **GCP** wiring use the Google Cloud console (OAuth consent screen, Android + Web client IDs). Example deployment has **`artgrid-auth-service`** and **`artgrid-ai-proxy`** on **Cloud Run** (e.g. project **`artgrid-47`**, region **`europe-west1`**) with **Cloud SQL PostgreSQL** when auth runs with **`DEMO_MODE=false`**. Details for environment variables, networking, and rolling updates are covered in the **Build & deployment** sections below and in each service’s `.env.example` plus root **`Backend/docker-compose.yml`**. **`artgrid-ml-service`** is often hosted for **GPU inference on RunPod** (`https://[POD_ID]-8001.proxy.runpod.net`-style HTTPS, single container, **`JWT_SECRET`** matching auth): push the image, expose **8001**, mount or copy **`models/`** into the container, and watch cold-start time and spend. **ML on Cloud Run** (including GPU classes such as NVIDIA L4) is an alternative: mount model weights (e.g. from Cloud Storage) at **`/app/models`** and confirm **`/health`** reports `gpu_available` when CUDA and ONNX Runtime align. The Android app points **`AUTH_BASE_URL`**, **`ML_BASE_URL`**, **`PROXY_BASE_URL`** at these HTTPS endpoints via **`demo.properties`** / **`BuildConfig`**.

---

## Architecture Overview

### System Architecture

| Layer | Technology | Role |
|-------|------------|------|
| **Mobile client** | Kotlin 2.0, Jetpack Compose (Material 3), Hilt, Room, OkHttp/Retrofit, Moshi (KSP), NDK (CMake 3.22) | UI, local pipelines, API clients, offline Room storage |
| **Auth service** | Go 1.23, Chi, PostgreSQL (`golang-migrate`) | Google Sign-In token exchange → JWT; refresh; encrypted PII |
| **ML service** | Python 3.11, FastAPI, OpenCV, ONNX Runtime, dlib, rate limiting | Face / unified face / objects / segment inference; `/health` |
| **AI proxy** | Go 1.23, Chi | `POST /api/v1/chat` → Gemini with **SSE** streaming; HMAC device token |

### Communication Flow

```
Android (Compose)
    → Google Sign-In → artgrid-auth-service :8080 → JWT (+ refresh)    [local; prod: HTTPS Cloud Run URL]
    → Bearer JWT     → artgrid-ml-service   :8001 → multipart image inference    [local / RunPod proxy / optional Cloud Run]
    → X-Device-Token → artgrid-ai-proxy     :8082 → SSE token stream (Gemini)    [local; prod: HTTPS Cloud Run URL]
    → Room SQLite    → reference_assets, progressions, progression_stages (on-device)
```

### On-device vs server split

- **Server ML**: dlib HOG + 68-point landmarks (legacy single-face `/infer/face`), YOLOv8n objects, U²-Netp segmentation, **unified** human + anime-face fusion on `/infer/face_unified` (parallel human geometry and animated-face detection with domain fusion inside `InferService`), optional anime landmarks when `return_landmarks=true` and the optional landmark model is loaded.
- **On-device (`libartgrid-native`)**: Oklab-domain edge pipeline, perspective correction, greyscale, tonal heatmap, white balance, invert, Kuwahara, colour sampling (Chamfer aperture), **Kubelka-Munk** paint-mix solver, Oklab median-cut palette quantisation, measurement/grid helpers. Heavy work runs on `Dispatchers.Default`; bitmap long-side cap (e.g. 4000px) in native code.

---

## Technology Stack

### Backend — `artgrid-auth-service`

| Technology | Purpose |
|------------|---------|
| **Go 1.23** | Service runtime |
| **chi/v5** | HTTP router |
| **PostgreSQL** | `users` table; `pgcrypto`; AES-GCM encrypted `google_sub`, `email` |
| **JWT** (`golang-jwt/jwt/v5`) | Access/refresh pattern via `AuthService` |

**Routes** (`internal/api/router.go`):

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Liveness |
| POST | `/api/v1/auth/google` | Exchange Google ID token → JWT |
| POST | `/api/v1/auth/refresh` | Refresh (requires valid JWT middleware) |

### Backend — `artgrid-ml-service`

| Technology | Purpose |
|------------|---------|
| **FastAPI** | REST API, lifespan model load |
| **OpenCV / NumPy** | Decode/validate uploads |
| **ONNX Runtime** | YOLOv8n, YOLOv8 anime-face |
| **dlib** | HOG detector + 68-point shape predictor |
| **JWT middleware** | `JWT_SECRET` shared with auth; `device_id` for rate limits |

**Primary routes** (`app/api/v1/infer/router.py`):

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | `gpu_available`, `models_loaded`, `demo_mode`, `build` |
| POST | `/infer/face` | Single human face, 68 landmarks (legacy endpoint) |
| POST | `/infer/face_unified` | Unified human + animated faces; optional anime landmarks |
| POST | `/infer/objects` | YOLOv8n object boxes + labels |
| POST | `/infer/segment` | U²-Netp subject/background mask (PNG alpha) |

Payload limits (enforced in router): face/objects/segment up to **15 MB** JPEG; unified face **10 MB**; long-edge caps — face **1200** px, objects **640** px, segment **800** px, unified **up to 1280** px (form `max_long_edge`, default 1024).

### Backend — `artgrid-ai-proxy`

| Technology | Purpose |
|------------|---------|
| **Go 1.23, chi** | Thin proxy, **90s** request timeout (`Timeout` middleware) |
| **Gemini** (via `ChatService`) | Model calls for chat completions |
| **SSE** | Chunked streaming response to client |

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Proxy health |
| POST | `/api/v1/chat` | JSON body: message (client-truncated to 2000 chars), optional `imageB64`, `sessionId` → SSE stream |

### Android application

| Technology | Version / notes | Purpose |
|------------|-----------------|----------|
| **Kotlin** | 2.0.21 (`gradle/libs.versions.toml`) | Language |
| **AGP** | 8.7.3 | Android build |
| **Jetpack Compose** | BOM 2024.12.01, Material 3 | UI |
| **Hilt** | 2.52 | DI (ViewModels, repositories, OkHttp clients) |
| **Room** | 2.6.1 | `reference_assets`, `progressions`, `progression_stages` |
| **Retrofit 2.11 + Moshi 1.15** | KSP codegen | Auth, ML multipart, typed DTOs |
| **OkHttp 4.12** | `@Named("proxyRaw")` | **SSE** chat (same client as proxy; avoids Retrofit full-body buffering) |
| **DataStore Preferences** | — | JWT / auth payloads (`AuthDataStore`) |
| **CameraX** | 1.3.4 | Trace mode camera underlay |
| **Coil** | 2.7.0 | Async images (crop, progression, reference history) |
| **NDK / CMake** | 3.22.1, C++17, `c++_shared`, ABI `arm64-v8a`, `x86_64` | `libartgrid-native` |
| **Google Sign-In** | Play Services Auth 21.3.0 | Auth with auth service |
| **minSdk 26 / targetSdk 35 / compileSdk 35** | Java 17 | Device coverage |

**Build configuration** (`app/build.gradle.kts`):

- **BuildConfig**: `DEMO_MODE`, `AUTH_BASE_URL`, `ML_BASE_URL`, `PROXY_BASE_URL`, `PROXY_SHARED_SECRET` (HMAC for `X-Device-Token`). Secret resolves `PROXY_SHARED_SECRET` then legacy `PROXY_DEVICE_SECRET`; default dev literal if unset (must match backend `PROXY_SHARED_SECRET`).
- **Host resolution**: `demo.properties` → `local.properties` key `artgrid.dev.host` → fallback LAN IP literal in Gradle (override for emulator: `10.0.2.2`).
- **Debug**: `applicationIdSuffix .debug`; **Release**: R8 + shrink resources.

---

## Database Schema

### PostgreSQL (`users`)

Migration `001_create_users.up.sql`:

```sql
users (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  google_sub  TEXT NOT NULL UNIQUE,   -- AES-256-GCM ciphertext (partial unique idx when active)
  email       TEXT NOT NULL,           -- AES-256-GCM ciphertext
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deleted_at  TIMESTAMPTZ             -- soft delete
)
```

Partial unique index on `google_sub` where `deleted_at IS NULL` (`idx_users_google_sub`).

### Room (on-device)

| Table | Role |
|-------|------|
| **reference_assets** | Saved references: local path, label, note, SHA-256, tag, timestamps (reference history library). |
| **progressions** | Comparator projects: title, description, timestamps (multi-stage progression feature). |
| **progression_stages** | Ordered stages per progression: label, image path, SHA-256, notes, optional **grid overlay** + **grid alpha** for comparator UI. |

Database version **1** (`ArtGridDatabase.kt`); `exportSchema = false`.

---

## Authentication & Authorization

### Google → JWT (auth service)

1. Client completes Google Sign-In and receives an ID token.
2. `POST /api/v1/auth/google` validates and maps to internal user (encrypted identifiers in DB).
3. JWT returned to app; stored via `AuthDataStore` (DataStore).
4. `AuthInterceptor` attaches Bearer token to **auth** and **ml** OkHttp clients (`@Named("auth")`, `@Named("ml")`).
5. `POST /api/v1/auth/refresh` rotates tokens when a valid JWT is present.

### AI proxy auth (separate from JWT)

- **`X-Device-Token`**: `device_uuid:HMAC-SHA256(...)` using `PROXY_SHARED_SECRET` and hourly window (see `ChatRepositoryImpl`). Implementation derives a stable `device_uuid` from the stored token (e.g. `device-` + prefix) or `demo-device` when absent. In **DEMO_MODE** the server may accept an empty HMAC fragment; the app still sends the full structure.
- Proxy client: `@Named("proxy")` / `@Named("proxyRaw")` — **no** `AuthInterceptor` on the proxy stack (documented in `NetworkModule`).

### ML service auth

- JWT validated on inference routes; `device_id` extracted for **per-device rate limiting** (e.g. unified face 60/hour per `router.py` / settings).

### Passing ML results between screens

Large ML payloads are not URL-encoded. **`NavResultHolder`** (in-memory `ConcurrentHashMap`) stores `FaceResult`, `ObjectInferResult`, and `SegmentResult` under ephemeral keys; routes carry only the **key** plus image URI (`AppNavGraph` + `NavArgs.kt`).

---

## Android — Navigation & Screens

**Single `NavHost`** (`AppNavGraph.kt`) — **17** primary composable destinations (plus legacy alias and trace variant):

| Route | Screen | Notes |
|-------|--------|-------|
| `auth/sign-in` | `SignInScreen` | Google Sign-In entry; unauthenticated users land here until tokens exist; errors surface inline with retry toward Home after success. |
| `home` | `HomeScreen` | Central hub: import reference image, probe ML **`/health`**, launch server ML cards (face, unified face, objects, segment), open on-device playgrounds, crop, palette, progression, trace, chat, and history shortcuts. ML rows respect `MlHealth.isAvailable`. |
| `chat` | `ChatScreen` | **AI Artist** chat: streamed tokens over **SSE** via the AI proxy (`X-Device-Token`), optional image attachment in the request payload, session continuity via `sessionId`. |
| `playground?uri={uri}` | `AnalysisResultScreen` | Full **image playground**: native pipelines (edges, perspective, tonal filters), shared **canvas calibration** (cm grid / ruler), and measurement overlays. Legacy deep link alias: `results?uri={uri}`. |
| `face-studio?uri=&key=` | `FaceStudioScreen` | **Legacy single-face** flow after `/infer/face`: expects a `FaceResult` stored under `key` in **`NavResultHolder`** plus the image URI; shows landmarks and calibration-aware spans. |
| `face-detail?uri=&bbox=` | `FaceDetailScreen` | Zoomed face region with bounding context from the legacy face pipeline. |
| `unified-face?uri=` | `UnifiedFaceStudioScreen` | **Unified face** analysis: human dlib path plus animated-face YOLO fusion; multiple detections; user can pick a face; optional anime landmark overlay when the backend returns it. |
| `object-locator?uri=&key=` | `ObjectLocatorScreen` | YOLO-driven boxes and labels; **`NavResultHolder`** holds `ObjectInferResult` by `key`. |
| `object-detail?uri=&bbox=&label=` | `ObjectDetailScreen` | Single-object focus with label and crop region for measurement or study. |
| `background-remover?uri=&key=` | `BackgroundRemoverScreen` | U²-Net mask preview and composite; **`NavResultHolder`** holds `SegmentResult` by `key`. |
| `color-palette?uri=` | `ColorPaletteScreen` | Colour sampling, Oklab/HSL/Kelvin controls, Kubelka–Munk mix, auto palette, artist swatch groups, **shadow construction** overlay. |
| `crop?uri=` | `CropScreen` | In-app crop with confirm callback; URI updated for downstream routes. |
| `paper-mapping?uri=` | `PaperMappingScreen` | Map reference proportions to **physical paper sizes** (e.g. A4/A3) with scaler and grid affordances. |
| `trace?uri=` / `trace` | `TraceModeScreen` | **Trace mode** with CameraX underlay; optional reference URI for alignment or standalone practice route. |
| `reference-history` | `ReferenceHistoryScreen` | Room-backed library: saved references, tags, notes, reopen into other tools. |
| `progression-list` / `progression-detail?id=` | Progression list & detail | Multi-stage **progression comparator**: ordered stages, optional per-stage grid overlay and alpha for before/after study. |

`ProtectedRoute` gates authenticated destinations; `AuthInterceptor.logoutEvent` triggers sign-out and navigation to `auth/sign-in`.

**Home ML gating**: `HomeViewModel.probeHealth()` sets `MlHealth`; `HomeScreen` treats `mlHealth?.isAvailable == true` as “server OK” for ML action rows. **Unified face** uses the same health gate as the other ML entry points in the current UI.

---

## ML Pipelines (server)

**Backend model storage:** weights and predictors live on the ML host under **`Backend/models/`** (mounted read-only as **`/app/models`** in Docker). The Android APK does **not** ship these binaries; inference always happens on **`artgrid-ml-service`**.

### Human face (legacy `/infer/face`)

- dlib HOG + 68-point regressor; single-face legacy endpoint.
- Used from Home → Face Studio when inferring via `/infer/face`.

### Unified face (`/infer/face_unified`)

- Parallel **human** (dlib) and **animated** (YOLOv8 anime-face ONNX) detection with **domain fusion** inside `InferService`.
- Form fields: `max_long_edge`, `conf`, `iou`, `return_landmarks`.
- Returns multiple faces; the client lets the user choose one in `UnifiedFaceStudioScreen`; optional anime landmark tensor when configured.

### Objects (`/infer/objects`)

- YOLOv8n ONNX at constrained long edge; normalised boxes + labels for Object Locator UI.

### Segmentation (`/infer/segment`)

- U²-Netp → PNG alpha mask bytes; Background Remover screen composites preview.

### Health / gating

- `HomeViewModel` / `MlRepository.checkHealth()`; UI uses `MlHealth` (`status == "ok"` → `isAvailable`, plus `gpuAvailable`, `modelsLoaded`, `demo_mode`).

---

## Native (C++) pipelines

**CMake** (`app/src/main/cpp/CMakeLists.txt`): target **`artgrid-native`** (SHARED), C++17, `-O2`, **no** `-ffast-math` (K-M solver Numerics). Linked with `jnigraphics`; linker flag `-Wl,-z,max-page-size=16384` for 16 KB page compatibility.

| Source module | Role |
|----------------|------|
| `artgrid_math.cpp` | Oklab/sRGB/XYZ, shared math |
| `edge_pipeline.cpp` | Oklab-domain structural edges |
| `perspective.cpp` | Corner detection + homography warp |
| `color_sampler.cpp` | Chamfer aperture colour sampling |
| `km_solver.cpp` | Kubelka–Munk 31-band paint-mix solver |
| `palette_quant.cpp` | Oklab median-cut palette + greedy merge |
| `image_filters.cpp` | Greyscale, tonal heatmap, white balance, invert, Kuwahara, related tonal filters |
| `jni_bridge.cpp` | JNI bindings only |

**Kotlin exposure** (`ArtGridNative.kt`): `extractEdges`, `correctPerspective`, `toGreyscale`, `tonalHeatmap`, `whiteBalance`, `invertColors`, `kuwaharaSimplify`, `sampleColor`, `solvePaintMix`, `extractPalette` — all `suspend` on `Dispatchers.Default` with `runCatching`.

---

## UI building blocks (selected)

- **`ui/canvas/`** — `CanvasViewModel`, `CanvasCalibrationSheet`: shared cm-grid calibration used on **Playground** (`AnalysisResultScreen`), **Face Studio / Locator** (and related detail flows) for consistent measurement overlays.
- **`ui/color/shadow/`** — Shadow construction overlay math and Compose overlay on **Colour Palette** (perspective guides, light-type presets).

---

## Key Features (implementation summary)

1. **Reference import** — image picker; URI threaded into routes.
2. **ML triad from Home** — Face, Objects, Segment with `MlFeatureState` (loading / success / empty / error).
3. **Image Playground** — Native filters + `NativePipelineViewModel` + canvas calibration + grid/ruler.
4. **Colour Palette** — Sampling, Oklab/HSL/Kelvin, K-M mix, auto palette, artist swatch groups, shadow construction overlay.
5. **Crop** — In-app crop with confirmation callback.
6. **Paper mapping** — Resize between paper sizes with grid/scaler UI.
7. **Trace mode** — CameraX underlay; optional reference URI or standalone `trace` route.
8. **Reference history** — Room-backed library and tags.
9. **Progression comparator** — Multi-stage rows with optional per-stage grid overlay settings.
10. **AI Artist chat** — `parseSseStream`, session id, optional image base64 in `ChatRequestDto`.

---

## Security Measures

| Area | Measure |
|------|---------|
| **PII** | Google subject + email encrypted at rest (AES-GCM) in PostgreSQL |
| **Transport** | HTTPS recommended in production; JWT on ML/auth |
| **Proxy** | HMAC hourly device token; shared secret from env / `BuildConfig` |
| **ML** | JWT on infer routes; multipart size + dimension validation; rate limits |
| **Errors** | ML service maps unhandled exceptions to typed/generic JSON (no stack traces to clients) |
| **Secrets** | `.env` / `demo.properties` git-ignored; `JWT_SECRET` 32+ bytes per backend docs |

---

## Project Structure (high level)

```
App/
├── app/src/main/
│   ├── java/com/artgrid/mobile/
│   │   ├── ArtGridApp.kt, MainActivity.kt
│   │   ├── core/              # di (NetworkModule, DatabaseModule, …), network, auth
│   │   ├── data/              # auth, ml, chat, db, reference, progression implementations
│   │   ├── domain/            # repository interfaces, ml/reference/progression models
│   │   ├── nativebridge/      # ArtGridNative.kt
│   │   └── ui/                # auth, home, chat, color (+ shadow), crop, face, objects,
│   │                          # segment, results, papermapping, trace, history, progression,
│   │                          # navigation, common, canvas
│   ├── cpp/                   # JNI + image math (CMake)
│   └── res/
├── gradle/libs.versions.toml  # version catalog
└── demo.properties            # optional local overrides (not in VCS)

Backend/
├── artgrid-auth-service/      # Go
├── artgrid-ml-service/        # Python FastAPI (loads ONNX/dlib weights from mounted volume)
├── artgrid-ai-proxy/          # Go
├── models/                    # ML weights
├── docker-compose.yml        # ports 8080 / 8001 / 8082; profiles: postgres, gpu
└── …
```

---

## Environment Variables

### Auth service

- See `artgrid-auth-service/.env.example`: database URL, JWT secrets, Google client IDs, encryption key material for PII.

### ML service

- See `artgrid-ml-service/.env.example`: `JWT_SECRET` (must match auth), `DEMO_MODE`, `BUILD`, rate limit knobs, `LOG_LEVEL`.

### AI proxy

- See `artgrid-ai-proxy/.env.example`: Gemini API key, `PROXY_SHARED_SECRET`, timeouts.

### Android

- **`App/demo.properties`** and **`App/local.properties`** for URLs, `DEMO_MODE`, and secrets (not committed). Production: HTTPS base URLs for Cloud Run / RunPod (trailing slash consistent with `NetworkModule`); **`PROXY_SHARED_SECRET`** must match **`artgrid-ai-proxy`**. Detailed env wiring is summarized under **Environment variables** and each service `.env.example` above.

---

## Development Workflow

### Backend (local)

```bash
cd Backend
cp artgrid-ml-service/.env.example .env   # set JWT_SECRET (32+ chars)
docker compose up --build
```

- **Full Postgres**: `docker compose --profile postgres up --build` (auth depends on `db` when profile enabled).
- Services: **:8080** auth, **:8001** ML (Swagger at `/docs` in demo), **:8082** proxy.

### Android

```bash
cd App
# optional: demo.properties, local.properties (artgrid.dev.host=10.0.2.2 for emulator)
./gradlew :app:assembleDebug
```

Use emulator **10.0.2.2** to reach the host loopback.

---

## Build & Deployment

### Android (APK / AAB)

- **Release**: `assembleRelease` with R8 minify + shrink resources (`app/build.gradle.kts` release block).

### Backend — local / dev machine

- **Full stack**: `docker compose up --build` (see **Development Workflow**). **ML GPU host**: `docker compose --profile gpu up --build` (NVIDIA Container Toolkit; `docker-compose.yml`).

### Production — Google Cloud (OAuth, auth, AI proxy; optional ML)

- **Console**: Google **Cloud Run** for **`artgrid-auth-service`** (8080) and **`artgrid-ai-proxy`** (8082); **Cloud SQL** for PostgreSQL when auth runs with **`DEMO_MODE=false`**; **APIs & Services → OAuth consent screen** and **Credentials** (Android + Web OAuth client IDs, SHA-1 for signing; **`GOOGLE_CLIENT_ID`** on the auth service aligned with `id_token` verification). Align **region**, **VPC connector**, and **service account IAM** with your org policies.
- **Optional**: **ML on Cloud Run** with GPU (e.g. NVIDIA L4), models on **Cloud Storage** mount at **`/app/models`**; **`/health`** should show `gpu_available` when CUDA and ONNX Runtime match your image.

### Production — ML on RunPod (GPU Pod)

- **`artgrid-ml-service` only** as a **single Docker image** (no Compose on the Pod); expose **HTTP 8001**; public URL often `https://[POD_ID]-8001.proxy.runpod.net`; bundle or volume-mount **`Backend/models`** into **`/app/models`**; **`JWT_SECRET`** identical to **`artgrid-auth-service`**. Budget for GPU idle cost, proxy **~100 s** timeout behaviour on cold starts (per RunPod networking docs), and first-request model warm-up latency.

### Cross-cutting

- **Same `JWT_SECRET`** across auth, ML (all hosts), and clients; **HTTPS** for production client traffic.
- **RunPod HTTP proxy** ~**100 s** timeout limit documented in RunPod docs (long first inference).

---

## Testing Considerations

| Layer | Suggested focus |
|-------|-----------------|
| **Go services** | Handler/service tests under each service |
| **FastAPI** | Pytest / dev dependencies in `pyproject.toml` |
| **Android** | Unit tests (e.g. `ShadowConstructionMathTest`); ViewModel state; SSE parser edge cases; JNI with large bitmaps |

---

## Data Flow Examples

### Run face detection

```
HomeScreen → uriToFile → HomeViewModel.runFaceDetection
  → MlRepository.inferFace (multipart JPEG)
  → ML: validate bytes/px → dlib infer → JSON DTO
  → UI: Success → NavResultHolder + navigate FaceStudio with key + uri
```

### Chat streaming

```
ChatViewModel → ChatRepository.streamChat
  → OkHttp POST PROXY_BASE_URL/api/v1/chat + X-Device-Token
  → parseSseStream(ResponseBody) → Flow<SseEvent>
  → UI collects tokens incrementally
```

---

## Performance Optimisations

- Bitmap long-side caps in native code before heavy ops (`MAX_PROCESSING_DIM` / `ArtGridNative` docs).
- ML images resized client-side to meet service limits before upload where applicable.
- **SSE** avoids buffering the full LLM response on the client.
- Model warm load at FastAPI lifespan; GPU availability exposed via `/health`.

---

## Future Enhancement Possibilities

- iOS client sharing math core or server-side parity.
- Push notifications for long-running ML jobs.
- User-uploaded calibration presets synced via backend.
- Expanded Compose/UI automation tests.

---

## Context for Understanding

### Distinctive aspects

1. **Split microservices** with different auth models (JWT vs HMAC device token).
2. **Unified face** pipeline combining classical geometry (dlib) and animated-face detection (YOLO ONNX) inside one inference pathway.
3. **Heavy on-device C++** for artist-specific colour and geometry versus filter-only stacks.
4. **SSE chat** through a dedicated proxy (not calling Gemini from Android directly).
5. **Operational split**: **Google Cloud Run** (OAuth / Cloud SQL) for stateless Go services versus **RunPod** or local **Compose `--profile gpu`** for the **GPU-bound** Python ML container—pods run a single service image without the full Compose stack.

### Common user flows

1. Sign in → Import reference → Run ML object detection → open locator with cm spans / calibration.
2. Import → Playground edges + perspective → Colour palette → K-M mix or shadow overlay.
3. Import → Unified face → inspect human vs animated heads → crop/paper map.
4. Chat → composition question with optional reference snapshot (`imageB64`).

---

## Documentation assets

Screenshots and architecture diagrams live under **`App/images/`**. **Model evaluation** plots committed for reports or coursework include (see root **`README.md`** for clickable links):

- **`confusion_matrix.png`** — confusion matrix  
- **`PR_curve.png`** — precision–recall curve  
- **`P_curve.png`** — precision vs threshold / confidence  
- **`R_curve.png`** — recall vs threshold / confidence  
- **`F1_curve.png`** — F1 vs threshold  

---

## Additional Resources (external)

- [FastAPI](https://fastapi.tiangolo.com/)
- [Go chi](https://github.com/go-chi/chi)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [ONNX Runtime](https://onnxruntime.ai/)
- [dlib](http://dlib.net/)

---

**Last Updated**: April 29, 2026  
**Version**: Android **0.1.0** (`versionName` in `app/build.gradle.kts`, `versionCode` 1)  
**Repository**: `App/` Android app + `Backend/` microservices
