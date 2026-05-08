/**
 * AppNavGraph.kt
 * Responsibility : Single NavHost defining all routes, the ProtectedNavigation wrapper,
 *                  and logout-event collection from AuthInterceptor.
 *
 * Route table (17 distinct screens):
 * | Route                               | Screen                   | Area                         |
 * |-------------------------------------|--------------------------|------------------------------|
 * | auth/sign-in                        | SignInScreen             | —                            |
 * | home                                | HomeScreen               | —                            |
 * | chat                                | ChatScreen               | —                            |
 * | playground?uri={uri}                | AnalysisResultScreen     | native playground / filters  |
 * | face-studio?uri={uri}&key={key}     | FaceStudioScreen         | legacy face + landmarks      |
 * | face-detail?uri={uri}&bbox={b}      | FaceDetailScreen         | face region detail           |
 * | unified-face?uri={uri}              | UnifiedFaceStudioScreen  | unified human + anime face    |
 * | object-locator?uri={uri}&key={k}    | ObjectLocatorScreen      | object detection overlay     |
 * | object-detail?uri={uri}&bbox={b}&l  | ObjectDetailScreen       | single object detail         |
 * | background-remover?uri={uri}&key={k}| BackgroundRemoverScreen  | segmentation / mask preview  |
 * | color-palette?uri={uri}             | ColorPaletteScreen       | colour + shadow tools        |
 * | crop?uri={uri}                      | CropScreen               | in-app crop                  |
 * | paper-mapping?uri={uri}             | PaperMappingScreen       | paper size mapping           |
 * | trace?uri={uri}                     | TraceModeScreen          | trace / camera underlay       |
 * | reference-history                   | ReferenceHistoryScreen   | saved references             |
 * | progression-list                    | ProgressionListScreen    | progression list             |
 * | progression-detail?id={id}          | ProgressionDetailScreen  | progression comparator        |
 *
 * API calls      : none (navigation glue only)
 * Injects        : AuthViewModel (shared), AuthInterceptor (via Hilt)
 */
package com.artgrid.mobile.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.artgrid.mobile.core.network.AuthInterceptor
import com.artgrid.mobile.domain.ml.model.ObjectInferResult
import com.artgrid.mobile.domain.ml.model.ObjectDetection
import com.artgrid.mobile.domain.ml.model.NormBbox
import com.artgrid.mobile.ui.auth.AuthViewModel
import com.artgrid.mobile.ui.auth.SignInScreen
import com.artgrid.mobile.ui.chat.ChatScreen
import com.artgrid.mobile.ui.color.ColorPaletteScreen
import com.artgrid.mobile.ui.common.LoadingScreen
import com.artgrid.mobile.ui.crop.CropScreen
import com.artgrid.mobile.ui.face.FaceDetailScreen
import com.artgrid.mobile.ui.face.FaceStudioScreen
import com.artgrid.mobile.ui.face.UnifiedFaceStudioScreen
import com.artgrid.mobile.ui.history.ReferenceHistoryScreen
import com.artgrid.mobile.ui.home.HomeScreen
import com.artgrid.mobile.ui.objects.ObjectDetailScreen
import com.artgrid.mobile.ui.objects.ObjectLocatorScreen
import com.artgrid.mobile.ui.papermapping.PaperMappingScreen
import com.artgrid.mobile.ui.progression.ProgressionDetailScreen
import com.artgrid.mobile.ui.progression.ProgressionListScreen
import com.artgrid.mobile.ui.results.AnalysisResultScreen
import com.artgrid.mobile.ui.segment.BackgroundRemoverScreen
import com.artgrid.mobile.ui.trace.TraceModeScreen

// ── Route constants ───────────────────────────────────────────────────────────

object Routes {
    const val SIGN_IN = "auth/sign-in"
    const val HOME    = "home"
    const val CHAT    = "chat"

    // Playground (generic filters, no ML result data)
    const val PLAYGROUND = "playground?uri={uri}"
    fun playground(encodedUri: String) = "playground?uri=$encodedUri"

    // Face screens
    const val FACE_STUDIO = "face-studio?uri={uri}&key={key}"
    fun faceStudio(encodedUri: String, key: String) = "face-studio?uri=$encodedUri&key=$key"

    const val FACE_DETAIL = "face-detail?uri={uri}&bbox={bbox}"
    fun faceDetail(encodedUri: String, encodedBbox: String) = "face-detail?uri=$encodedUri&bbox=$encodedBbox"

    // Object screens
    const val OBJECT_LOCATOR = "object-locator?uri={uri}&key={key}"
    fun objectLocator(encodedUri: String, key: String) = "object-locator?uri=$encodedUri&key=$key"

    const val OBJECT_DETAIL = "object-detail?uri={uri}&bbox={bbox}&label={label}"
    fun objectDetail(encodedUri: String, encodedBbox: String, label: String) =
        "object-detail?uri=$encodedUri&bbox=$encodedBbox&label=${Uri.encode(label)}"

    // Segment
    const val BACKGROUND_REMOVER = "background-remover?uri={uri}&key={key}"
    fun backgroundRemover(encodedUri: String, key: String) = "background-remover?uri=$encodedUri&key=$key"

    // Color palette (no ML result, just the image)
    const val COLOR_PALETTE = "color-palette?uri={uri}"
    fun colorPalette(encodedUri: String) = "color-palette?uri=$encodedUri"

    // Unified face (`/infer/face_unified`)
    const val UNIFIED_FACE = "unified-face?uri={uri}"
    fun unifiedFace(encodedUri: String) = "unified-face?uri=$encodedUri"

    // In-app crop
    const val CROP = "crop?uri={uri}"
    fun crop(encodedUri: String) = "crop?uri=$encodedUri"

    // Paper mapping
    const val PAPER_MAPPING = "paper-mapping?uri={uri}"
    fun paperMapping(encodedUri: String) = "paper-mapping?uri=$encodedUri"

    // Camera underlay / trace mode
    const val TRACE = "trace?uri={uri}"
    fun trace(encodedUri: String) = "trace?uri=$encodedUri"
    const val TRACE_NOREF = "trace"

    // Reference history (no image argument — accessed from home)
    const val REFERENCE_HISTORY = "reference-history"

    // Progression comparator
    const val PROGRESSION_LIST   = "progression-list"
    const val PROGRESSION_DETAIL = "progression-detail?id={id}"
    fun progressionDetail(id: Long) = "progression-detail?id=$id"

    // Keep old results route as alias for playground (backwards compatibility)
    const val RESULTS = "results?uri={uri}"
    fun results(encodedUri: String) = "results?uri=$encodedUri"
}

// ── NavGraph entry point ──────────────────────────────────────────────────────

@Composable
fun AppNavGraph(
    authInterceptor: AuthInterceptor,
    modifier:        Modifier             = Modifier,
    navController:   NavHostController   = rememberNavController(),
    authViewModel:   AuthViewModel        = hiltViewModel(),
) {
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(authInterceptor) {
        authInterceptor.logoutEvent.collect {
            authViewModel.signOut()
            navController.navigate(Routes.SIGN_IN) { popUpTo(0) { inclusive = true } }
        }
    }
    LaunchedEffect(Unit) { authViewModel.initialize() }

    if (!authState.isInitialized) { LoadingScreen(); return }

    NavHost(
        navController    = navController,
        startDestination = if (authState.isAuthenticated) Routes.HOME else Routes.SIGN_IN,
        modifier         = modifier,
    ) {
        // ── Unauthenticated ───────────────────────────────────────────────────
        composable(Routes.SIGN_IN) {
            SignInScreen(viewModel = authViewModel)
            LaunchedEffect(authState.isAuthenticated) {
                if (authState.isAuthenticated) {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.SIGN_IN) { inclusive = true } }
                }
            }
        }

        // ── Home ──────────────────────────────────────────────────────────────
        composable(Routes.HOME) {
            ProtectedRoute(authState.isAuthenticated, navController) {
                HomeScreen(
                    onNavigateToChat = { navController.navigate(Routes.CHAT) },
                    onNavigateToPlayground = { uri ->
                        navController.navigate(Routes.playground(Uri.encode(uri.toString())))
                    },
                    onNavigateToFaceStudio = { uri, faceResult ->
                        val key = "face_${System.currentTimeMillis()}"
                        NavResultHolder.putFace(key, faceResult)
                        navController.navigate(Routes.faceStudio(Uri.encode(uri.toString()), key))
                    },
                    onNavigateToUnifiedFace = { uri ->
                        navController.navigate(Routes.unifiedFace(Uri.encode(uri.toString())))
                    },
                    onNavigateToObjectLocator = { uri, objectResult ->
                        val key = "obj_${System.currentTimeMillis()}"
                        NavResultHolder.putObjects(key, objectResult)
                        navController.navigate(Routes.objectLocator(Uri.encode(uri.toString()), key))
                    },
                    onNavigateToBackgroundRemover = { uri, segmentResult ->
                        val key = "seg_${System.currentTimeMillis()}"
                        NavResultHolder.putSegment(key, segmentResult)
                        navController.navigate(Routes.backgroundRemover(Uri.encode(uri.toString()), key))
                    },
                    onNavigateToColorPalette = { uri ->
                        navController.navigate(Routes.colorPalette(Uri.encode(uri.toString())))
                    },
                    onNavigateToCrop = { uri ->
                        navController.navigate(Routes.crop(Uri.encode(uri.toString())))
                    },
                    onNavigateToPaperMapping = { uri ->
                        navController.navigate(Routes.paperMapping(Uri.encode(uri.toString())))
                    },
                    onNavigateToTrace = { uri ->
                        if (uri != null) navController.navigate(Routes.trace(Uri.encode(uri.toString())))
                        else navController.navigate(Routes.TRACE_NOREF)
                    },
                    onNavigateToReferenceHistory = { navController.navigate(Routes.REFERENCE_HISTORY) },
                    onNavigateToProgressions = { navController.navigate(Routes.PROGRESSION_LIST) },
                )
            }
        }

        // ── Chat ──────────────────────────────────────────────────────────────
        composable(Routes.CHAT) {
            ProtectedRoute(authState.isAuthenticated, navController) {
                ChatScreen(onNavigateBack = { navController.popBackStack() })
            }
        }

        // ── Playground (on-device native filters) ───────────────────────────
        composable(
            route     = Routes.PLAYGROUND,
            arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back ->
            val imageUri = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null) AnalysisResultScreen(imageUri = imageUri, onNavigateBack = { navController.popBackStack() })
                else LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        // Legacy alias
        composable(
            route     = Routes.RESULTS,
            arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back ->
            val imageUri = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null) AnalysisResultScreen(imageUri = imageUri, onNavigateBack = { navController.popBackStack() })
                else LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        // ── Face Studio ───────────────────────────────────────────────────────
        composable(
            route     = Routes.FACE_STUDIO,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("key") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val imageUri   = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            val key        = back.arguments?.getString("key") ?: ""
            val faceResult = NavResultHolder.getFace(key)
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null && faceResult != null) {
                    FaceStudioScreen(
                        imageUri             = imageUri,
                        faceResult           = faceResult,
                        onNavigateBack       = { navController.popBackStack() },
                        onNavigateToFaceDetail = { uri, bbox ->
                            navController.navigate(Routes.faceDetail(Uri.encode(uri.toString()), bbox.encodeToNav()))
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }

        // ── Face Detail ───────────────────────────────────────────────────────
        composable(
            route     = Routes.FACE_DETAIL,
            arguments = listOf(
                navArgument("uri")  { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("bbox") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val imageUri = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            val bbox     = back.arguments?.getString("bbox")?.decodeNavBbox()
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null && bbox != null) {
                    FaceDetailScreen(imageUri = imageUri, faceBbox = bbox, onNavigateBack = { navController.popBackStack() })
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }

        // ── Object Locator ────────────────────────────────────────────────────
        composable(
            route     = Routes.OBJECT_LOCATOR,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("key") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val imageUri     = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            val key          = back.arguments?.getString("key") ?: ""
            val objectResult = NavResultHolder.getObjects(key)
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null && objectResult != null) {
                    ObjectLocatorScreen(
                        imageUri              = imageUri,
                        objectResult          = objectResult,
                        onNavigateBack        = { navController.popBackStack() },
                        onNavigateToObjectDetail = { uri, bbox ->
                            // Find label for this bbox
                            val label = objectResult.detections.minByOrNull {
                                val ax = it.bbox.xNorm - bbox.xNorm; val ay = it.bbox.yNorm - bbox.yNorm
                                ax * ax + ay * ay
                            }?.label ?: "Object"
                            navController.navigate(Routes.objectDetail(Uri.encode(uri.toString()), bbox.encodeToNav(), label))
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }

        // ── Object Detail ─────────────────────────────────────────────────────
        composable(
            route     = Routes.OBJECT_DETAIL,
            arguments = listOf(
                navArgument("uri")   { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("bbox")  { type = NavType.StringType; defaultValue = "" },
                navArgument("label") { type = NavType.StringType; defaultValue = "Object" },
            ),
        ) { back ->
            val imageUri = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            val bbox     = back.arguments?.getString("bbox")?.decodeNavBbox()
            val label    = Uri.decode(back.arguments?.getString("label") ?: "Object")
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null && bbox != null) {
                    ObjectDetailScreen(imageUri = imageUri, objectBbox = bbox, objectLabel = label, onNavigateBack = { navController.popBackStack() })
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }

        // ── Background Remover ────────────────────────────────────────────────
        composable(
            route     = Routes.BACKGROUND_REMOVER,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("key") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val imageUri  = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            val key       = back.arguments?.getString("key") ?: ""
            val segResult = NavResultHolder.getSegment(key)
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null && segResult != null) {
                    BackgroundRemoverScreen(
                        imageUri      = imageUri,
                        maskPngBytes  = segResult.maskPng,
                        onNavigateBack = { navController.popBackStack() },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }

        // ── Color Palette ─────────────────────────────────────────────────────
        composable(
            route     = Routes.COLOR_PALETTE,
            arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back ->
            val imageUri = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null) {
                    ColorPaletteScreen(imageUri = imageUri, onNavigateBack = { navController.popBackStack() })
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }

        // ── Unified face studio ───────────────────────────────────────────────
        composable(
            route     = Routes.UNIFIED_FACE,
            arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back ->
            val imageUri = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null) {
                    UnifiedFaceStudioScreen(imageUri = imageUri, onNavigateBack = { navController.popBackStack() })
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }

        // ── Crop ───────────────────────────────────────────────────────────────
        composable(
            route     = Routes.CROP,
            arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back ->
            val imageUri = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null) {
                    CropScreen(
                        imageUri       = imageUri,
                        onCropConfirmed = { _ -> navController.popBackStack() },
                        onNavigateBack  = { navController.popBackStack() },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }

        // ── Paper mapping ─────────────────────────────────────────────────────
        composable(
            route     = Routes.PAPER_MAPPING,
            arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back ->
            val imageUri = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (imageUri != null) {
                    PaperMappingScreen(imageUri = imageUri, onNavigateBack = { navController.popBackStack() })
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }

        // ── Trace mode (optional reference) ─────────────────────────────────
        composable(
            route     = Routes.TRACE,
            arguments = listOf(navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { back ->
            val imageUri = back.arguments?.getString("uri")?.let { Uri.parse(Uri.decode(it)) }
            ProtectedRoute(authState.isAuthenticated, navController) {
                TraceModeScreen(referenceUri = imageUri, onNavigateBack = { navController.popBackStack() })
            }
        }

        composable(route = Routes.TRACE_NOREF) {
            ProtectedRoute(authState.isAuthenticated, navController) {
                TraceModeScreen(referenceUri = null, onNavigateBack = { navController.popBackStack() })
            }
        }

        // ── Reference history ────────────────────────────────────────────────
        composable(Routes.REFERENCE_HISTORY) {
            ProtectedRoute(authState.isAuthenticated, navController) {
                ReferenceHistoryScreen(
                    onNavigateBack  = { navController.popBackStack() },
                    onOpenReference = { uri ->
                        navController.navigate(Routes.playground(Uri.encode(uri.toString())))
                    },
                )
            }
        }

        // ── Progression list ─────────────────────────────────────────────────
        composable(Routes.PROGRESSION_LIST) {
            ProtectedRoute(authState.isAuthenticated, navController) {
                ProgressionListScreen(
                    onNavigateBack    = { navController.popBackStack() },
                    onOpenProgression = { id -> navController.navigate(Routes.progressionDetail(id)) },
                )
            }
        }

        // ── Progression detail ───────────────────────────────────────────────
        composable(
            route     = Routes.PROGRESSION_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
        ) { back ->
            val progressionId = back.arguments?.getLong("id") ?: -1L
            ProtectedRoute(authState.isAuthenticated, navController) {
                if (progressionId != -1L) {
                    ProgressionDetailScreen(
                        progressionId   = progressionId,
                        onNavigateBack  = { navController.popBackStack() },
                        onOpenColorPicker = { uri ->
                            navController.navigate(Routes.colorPalette(Uri.encode(uri.toString())))
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }
        }
    }
}

// ── ProtectedRoute guard ──────────────────────────────────────────────────────

@Composable
private fun ProtectedRoute(
    isAuthenticated: Boolean,
    navController: NavHostController,
    content: @Composable () -> Unit,
) {
    if (!isAuthenticated) {
        LaunchedEffect(Unit) {
            navController.navigate(Routes.SIGN_IN) { popUpTo(0) { inclusive = true } }
        }
        LoadingScreen()
    } else {
        content()
    }
}
