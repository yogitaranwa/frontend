/**
 * HomeScreen.kt
 * Responsibility : Main hub screen with polished visual design.
 *
 * Visual improvements in this version:
 *  - Gradient hero banner with ArtGrid branding and tagline
 *  - Color-coded ML feature cards (teal/blue for Face, amber for Objects, green for Segment)
 *  - Distinct on-device tools section with green-tinted cards
 *  - Server status row with animated dot
 *  - Extended FAB with label
 *  - Better typography, spacing and surface elevations
 */
package com.artgrid.mobile.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.domain.ml.model.FaceResult
import com.artgrid.mobile.domain.ml.model.ObjectInferResult
import com.artgrid.mobile.domain.ml.model.SegmentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Theme accent colours (used without changing the app-wide MaterialTheme) ───

private val TealPrimary   = Color(0xFF00897B)   // teal 600
private val TealContainer = Color(0xFF80CBC4)   // teal 200
private val BluePrimary   = Color(0xFF1976D2)   // blue 700
private val BlueContainer = Color(0xFFBBDEFB)   // blue 100
private val AmberPrimary  = Color(0xFFF57C00)   // amber 800
private val AmberContainer= Color(0xFFFFE0B2)   // amber 100
private val GreenPrimary  = Color(0xFF388E3C)   // green 700
private val GreenContainer= Color(0xFFC8E6C9)   // green 100

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToChat: () -> Unit,
    onNavigateToPlayground: (Uri) -> Unit,
    onNavigateToFaceStudio: (Uri, FaceResult) -> Unit,
    onNavigateToUnifiedFace: (Uri) -> Unit,
    onNavigateToObjectLocator: (Uri, ObjectInferResult) -> Unit,
    onNavigateToBackgroundRemover: (Uri, SegmentResult) -> Unit,
    onNavigateToColorPalette: (Uri) -> Unit,
    onNavigateToCrop: (Uri) -> Unit,
    onNavigateToPaperMapping: (Uri) -> Unit,
    onNavigateToTrace: (Uri?) -> Unit,
    onNavigateToReferenceHistory: () -> Unit,
    onNavigateToProgressions: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onImageSelected(it) } }

    suspend fun uriToFile(uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File.createTempFile("artgrid_", ".jpg", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
            tmp
        }.getOrNull()
    }

    // No top bar — replaced by gradient hero
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick             = onNavigateToChat,
                icon                = { Icon(Icons.AutoMirrored.Outlined.Chat, null) },
                text                = { Text("AI Artist") },
                containerColor      = TealPrimary,
                contentColor        = Color.White,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Hero banner ───────────────────────────────────────────────────
            HeroBanner(onPickImage = { imagePickerLauncher.launch("image/*") })

            // Persistent image state indicator
            if (uiState.selectedImageUri != null) {
                Surface(color = TealContainer.copy(0.4f), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = TealPrimary, modifier = Modifier.size(16.dp))
                        Text("Image imported — choose an analysis tool below",
                            style = MaterialTheme.typography.labelMedium, color = TealPrimary)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── ML Features ──────────────────────────────────────────────────
            val serverOk      = uiState.mlHealth?.isAvailable == true
            val imageSelected = uiState.selectedImageUri != null

            // Server status
            MlStatusRow(
                health    = uiState.mlHealth,
                onRetry   = viewModel::probeHealth,
                modifier  = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(14.dp))

            if (serverOk) {
                SectionHeader("ML Analysis", Icons.Outlined.Psychology, BluePrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

                // Face Analysis
                MlFeatureCard(
                    title        = "Face Analysis",
                    subtitle     = "Detect 68 landmarks, measure distances in cm, grid overlay",
                    icon         = Icons.Outlined.Face,
                    accentColor  = BluePrimary,
                    accentBg     = BlueContainer,
                    state        = uiState.faceState,
                    enabled      = imageSelected,
                    onRun        = { scope.launch { uriToFile(uiState.selectedImageUri!!)?.let(viewModel::runFaceDetection) } },
                    onViewResult = {
                        val face = (uiState.faceState as? MlFeatureState.Success<*>)?.result as? FaceResult
                        val uri  = uiState.selectedImageUri
                        if (face != null && uri != null) onNavigateToFaceStudio(uri, face)
                    },
                    resultLabel  = "Open Face Studio →",
                    modifier     = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // Object Locator
                MlFeatureCard(
                    title        = "Object Detection",
                    subtitle     = "Locate & label objects, show grid-cell spans and cm dimensions",
                    icon         = Icons.Outlined.FindInPage,
                    accentColor  = AmberPrimary,
                    accentBg     = AmberContainer,
                    state        = uiState.objectsState,
                    enabled      = imageSelected,
                    onRun        = { scope.launch { uriToFile(uiState.selectedImageUri!!)?.let(viewModel::runObjectDetection) } },
                    onViewResult = {
                        val objs = (uiState.objectsState as? MlFeatureState.Success<*>)?.result as? ObjectInferResult
                        val uri  = uiState.selectedImageUri
                        if (objs != null && uri != null) onNavigateToObjectLocator(uri, objs)
                    },
                    resultLabel  = "Open Object Locator →",
                    modifier     = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // Background Remover
                MlFeatureCard(
                    title        = "Background Removal",
                    subtitle     = "Separate subject from background with U²-Net segmentation",
                    icon         = Icons.Outlined.LayersClear,
                    accentColor  = GreenPrimary,
                    accentBg     = GreenContainer,
                    state        = uiState.segmentState,
                    enabled      = imageSelected,
                    onRun        = { scope.launch { uriToFile(uiState.selectedImageUri!!)?.let(viewModel::runSegmentation) } },
                    onViewResult = {
                        val seg = (uiState.segmentState as? MlFeatureState.Success<*>)?.result as? SegmentResult
                        val uri = uiState.selectedImageUri
                        if (seg != null && uri != null) onNavigateToBackgroundRemover(uri, seg)
                    },
                    resultLabel  = "Open Background Remover →",
                    modifier     = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                // ML unavailable notice
                if (uiState.mlHealth != null && !serverOk) {
                    Surface(
                        color    = MaterialTheme.colorScheme.errorContainer,
                        shape    = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.WifiOff, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text("ML server unavailable — Face, Object and Background tools offline. Tap Retry above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── On-device tools ───────────────────────────────────────────────
            Surface(
                color    = TealContainer.copy(0.2f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(bottom = 16.dp)) {
                    SectionHeader("On-Device Tools", Icons.Outlined.PhoneAndroid, TealPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    Text("No internet required · Processes images locally on your device",
                        style = MaterialTheme.typography.bodySmall,
                        color = TealPrimary.copy(0.75f),
                        modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(12.dp))

                    // Image Playground
                    OnDeviceCard(
                        title       = "Image Playground",
                        description = "Edge extraction, greyscale, tonal maps, white balance, invert, Kuwahara, perspective. Grid in cm with A→B measurement.",
                        icon        = Icons.Outlined.AutoAwesome,
                        accentColor = TealPrimary,
                        buttonLabel = "Open Playground",
                        enabled     = imageSelected,
                        hint        = if (!imageSelected) "Import an image first" else null,
                        onClick     = { uiState.selectedImageUri?.let(onNavigateToPlayground) },
                        modifier    = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    // Color Palette
                    OnDeviceCard(
                        title       = "Color Palette",
                        description = "Tap-to-sample colors · Oklab / HSL / Kelvin readout · K-M paint mix · 8 artist palette groups · Auto dominant palette.",
                        icon        = Icons.Outlined.Palette,
                        accentColor = Color(0xFF7B1FA2),   // purple 700
                        buttonLabel = "Open Color Palette",
                        enabled     = imageSelected,
                        hint        = if (!imageSelected) "Import an image first" else null,
                        onClick     = { uiState.selectedImageUri?.let(onNavigateToColorPalette) },
                        modifier    = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    // Crop (F-28)
                    OnDeviceCard(
                        title       = "Crop Image",
                        description = "Non-destructive in-app crop with draggable handles. Lock aspect ratios (A4, A5, 16:9, 1:1 …) or use freehand.",
                        icon        = Icons.Outlined.Crop,
                        accentColor = Color(0xFFC85C3A),
                        buttonLabel = "Open Crop",
                        enabled     = imageSelected,
                        hint        = if (!imageSelected) "Import an image first" else null,
                        onClick     = { uiState.selectedImageUri?.let(onNavigateToCrop) },
                        modifier    = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    // Paper Mapping (F-29)
                    OnDeviceCard(
                        title       = "Paper Mapping",
                        description = "Map image from one paper size to another (A5 → A3). cm grid overlay, opacity/spacing controls, measurement scaler.",
                        icon        = Icons.Outlined.GridOn,
                        accentColor = Color(0xFF6B8F71),
                        buttonLabel = "Open Paper Mapping",
                        enabled     = imageSelected,
                        hint        = if (!imageSelected) "Import an image first" else null,
                        onClick     = { uiState.selectedImageUri?.let(onNavigateToPaperMapping) },
                        modifier    = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    // Trace Mode (F-27-MVP)
                    OnDeviceCard(
                        title       = "Trace Mode",
                        description = "Camera underlay — overlay your reference image on the live camera feed. Pan, pinch, rotate, and adjust opacity.",
                        icon        = Icons.Outlined.CameraAlt,
                        accentColor = Color(0xFF1976D2),
                        buttonLabel = "Open Trace Mode",
                        enabled     = true,
                        hint        = null,
                        onClick     = { onNavigateToTrace(uiState.selectedImageUri) },
                        modifier    = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Studio & History tools ─────────────────────────────────────────
            Surface(
                color    = Color(0xFF2C2C2E).copy(0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(bottom = 16.dp)) {
                    SectionHeader("Studio", Icons.Outlined.Layers, Color(0xFFC85C3A),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))

                    // Reference History (F-31)
                    OnDeviceCard(
                        title       = "Reference History",
                        description = "Browse saved reference images, filter by tag, re-open in any tool. Save to local library directly from gallery.",
                        icon        = Icons.Outlined.PhotoLibrary,
                        accentColor = Color(0xFFAA8855),
                        buttonLabel = "Open History",
                        enabled     = true,
                        hint        = null,
                        onClick     = onNavigateToReferenceHistory,
                        modifier    = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    // Progression Comparator (F-32)
                    OnDeviceCard(
                        title       = "Progression Comparator",
                        description = "Track your artwork journey stage-by-stage. Side-by-side compare sketch → ink → color passes with grid overlay.",
                        icon        = Icons.AutoMirrored.Outlined.CompareArrows,
                        accentColor = Color(0xFF8B5CF6),
                        buttonLabel = "Open Progressions",
                        enabled     = true,
                        hint        = null,
                        onClick     = onNavigateToProgressions,
                        modifier    = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    // F-34 Unified Face — ML gated
                    if (serverOk) {
                        OnDeviceCard(
                            title       = "Unified Face Studio",
                            description = "Detect human (68-pt landmarks) AND animated faces (YOLOv8) in one pass. Domain-aware fusion. Tap-to-select any face.",
                            icon        = Icons.Outlined.Face,
                            accentColor = Color(0xFF8B5CF6),
                            buttonLabel = "Open Unified Face",
                            enabled     = imageSelected,
                            hint        = if (!imageSelected) "Import an image first" else null,
                            onClick     = { uiState.selectedImageUri?.let(onNavigateToUnifiedFace) },
                            modifier    = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(80.dp)) // FAB clearance
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun HeroBanner(onPickImage: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF004D40),  // teal 900
                        Color(0xFF00695C),  // teal 800
                        Color(0xFF00897B),  // teal 600
                        Color(0xFF26A69A),  // teal 400
                    )
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.GridOn, null, tint = Color(0xFF80CBC4), modifier = Modifier.size(28.dp))
                Text("ArtGrid",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White)
            }
            Text("Reference-perfect art with ML-powered\ngrid, measurement & color analysis",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(0.85f))
            // Import button in banner
            FilledTonalButton(
                onClick  = onPickImage,
                colors   = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(0.2f),
                    contentColor   = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import Reference Image", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun MlStatusRow(
    health: com.artgrid.mobile.domain.ml.model.MlHealth?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition("pulse").animateFloat(
        initialValue   = 0.8f, targetValue = 1.0f,
        animationSpec  = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label          = "statusPulse",
    )

    Surface(
        color    = MaterialTheme.colorScheme.surfaceVariant.copy(0.7f),
        shape    = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    health == null -> {
                        Box(Modifier.size(10.dp).scale(pulse).clip(CircleShape).background(Color(0xFFFFA000)))
                        Text("Connecting to ML server…", style = MaterialTheme.typography.bodySmall)
                    }
                    health.isAvailable -> {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(TealPrimary))
                        Column {
                            val gpuLabel = if (health.gpuAvailable) "GPU" else "CPU"
                            Text("ML server online · $gpuLabel", style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold, color = TealPrimary)
                            Text(health.modelsLoaded.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                        Text("ML server offline", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (health?.isAvailable == false) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Retry", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun <T> MlFeatureCard(
    title: String, subtitle: String, icon: ImageVector,
    accentColor: Color, accentBg: Color,
    state: MlFeatureState<T>,
    enabled: Boolean,
    onRun: () -> Unit, onViewResult: () -> Unit,
    resultLabel: String,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        // Accent strip at top
        Box(Modifier.fillMaxWidth().height(4.dp).background(accentColor))

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(38.dp).background(accentBg, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = accentColor)
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            when (state) {
                is MlFeatureState.Idle ->
                    Button(
                        onClick  = onRun,
                        enabled  = enabled,
                        colors   = ButtonDefaults.buttonColors(containerColor = accentColor),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Analyse") }

                is MlFeatureState.Loading ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accentColor)

                is MlFeatureState.Success<*> ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = accentColor)
                            Text("Analysis complete", style = MaterialTheme.typography.bodySmall,
                                color = accentColor, fontWeight = FontWeight.Medium)
                        }
                        Button(onClick = onViewResult, colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            modifier = Modifier.fillMaxWidth()) {
                            Text(resultLabel)
                        }
                        FilledTonalButton(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("Re-analyse") }
                    }

                is MlFeatureState.Empty ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            color = AmberContainer.copy(0.6f),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.SearchOff, null, Modifier.size(16.dp), tint = AmberPrimary)
                                Text(state.hint, style = MaterialTheme.typography.bodySmall, color = AmberPrimary)
                            }
                        }
                        FilledTonalButton(onClick = onRun, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text("Try Again")
                        }
                    }

                is MlFeatureState.Error ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⚠ ${state.message}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRun, enabled = enabled,
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                    }
            }
        }
    }
}

@Composable
private fun OnDeviceCard(
    title: String, description: String, icon: ImageVector,
    accentColor: Color, buttonLabel: String,
    enabled: Boolean, hint: String?, onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp).background(accentColor.copy(0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(20.dp), tint = accentColor)
                }
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(
                onClick  = onClick,
                enabled  = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(buttonLabel) }
            hint?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
