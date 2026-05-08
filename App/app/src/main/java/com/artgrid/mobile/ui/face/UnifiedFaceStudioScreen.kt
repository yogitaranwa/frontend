/**
 * UnifiedFaceStudioScreen.kt
 * Responsibility : Unified human + animated face detection screen.
 *                  Shows multi-face detection results with domain labels (human/animated),
 *                  tap-to-select face UX, bbox overlays, landmark counts, and telemetry.
 * Pattern used   : Stateless composable + HiltViewModel
 * Dependencies   : UnifiedFaceViewModel, Coil
 */
package com.artgrid.mobile.ui.face

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.artgrid.mobile.domain.ml.model.FaceDetection
import com.artgrid.mobile.domain.ml.model.FaceDomain
import com.artgrid.mobile.ui.common.ErrorScreen
import com.artgrid.mobile.ui.common.LoadingScreen

private val Charcoal   = Color(0xFF1C1C1E)
private val Cream      = Color(0xFFF5F0E8)
private val Sienna     = Color(0xFFC85C3A)
private val HumanBlue  = Color(0xFF1976D2)
private val AnimeViolet= Color(0xFF8B5CF6)
private val AmbigGray  = Color(0xFF8E8E93)
private val Sage       = Color(0xFF6B8F71)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedFaceStudioScreen(
    imageUri: Uri,
    onNavigateBack: () -> Unit,
    viewModel: UnifiedFaceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(imageUri) { viewModel.runUnifiedDetection(imageUri) }

    Scaffold(
        containerColor = Charcoal,
        topBar = {
            TopAppBar(
                title = {
                    Text("Face Studio · Unified", color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Cream)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.runUnifiedDetection(imageUri) }) {
                        Icon(Icons.Outlined.Refresh, null, tint = Cream)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111113)),
            )
        },
    ) { padding ->
        when (val state = uiState) {
            is UnifiedFaceUiState.Idle    -> LoadingScreen()
            is UnifiedFaceUiState.Loading -> LoadingScreen()
            is UnifiedFaceUiState.Error   -> ErrorScreen(
                message = state.message,
                onRetry = { viewModel.runUnifiedDetection(imageUri) },
            )
            is UnifiedFaceUiState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    // ── Image with face overlays ───────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF0A0A0C)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model              = imageUri,
                            contentDescription = "Source image",
                            contentScale       = ContentScale.Fit,
                            modifier           = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { canvasSize = it.size },
                        )
                        FaceOverlayCanvas(
                            faces         = state.result.faces,
                            selectedIndex = state.selectedIndex,
                            canvasSize    = canvasSize,
                            onFaceTap     = { viewModel.selectFace(it) },
                            modifier      = Modifier.fillMaxSize(),
                        )
                    }

                    // ── Summary row ───────────────────────────────────────────
                    Surface(color = Color(0xFF111113)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${state.result.faceCount} face${if (state.result.faceCount != 1) "s" else ""} detected",
                                color = Cream, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "GPU: ${state.result.telemetry.totalMs} ms",
                                color = Color(0xFF8E8E93), fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }

                    // ── Face selector chips ───────────────────────────────────
                    if (state.result.faceCount > 1) {
                        LazyRow(
                            contentPadding    = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1C1C1E)),
                        ) {
                            itemsIndexed(state.result.faces) { index, face ->
                                FaceChip(
                                    index    = index,
                                    face     = face,
                                    selected = index == state.selectedIndex,
                                    onClick  = { viewModel.selectFace(index) },
                                )
                            }
                        }
                    }

                    // ── Selected face detail card ──────────────────────────────
                    state.result.faces.getOrNull(state.selectedIndex)?.let { face ->
                        FaceDetailCard(face = face, modifier = Modifier.fillMaxWidth())
                    }

                    // ── Telemetry footer ──────────────────────────────────────
                    TelemetryFooter(state.result.telemetry)
                }
            }
        }
    }
}

@Composable
private fun FaceChip(index: Int, face: FaceDetection, selected: Boolean, onClick: () -> Unit) {
    val color = when (face.domain) {
        FaceDomain.HUMAN     -> HumanBlue
        FaceDomain.ANIMATED  -> AnimeViolet
        FaceDomain.AMBIGUOUS -> AmbigGray
    }
    FilterChip(
        selected = selected,
        onClick  = onClick,
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        },
        label = {
            Text(
                "Face ${index + 1} · ${face.domain.name.lowercase().replaceFirstChar { it.uppercase() }}",
                fontSize = 12.sp,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.25f),
            selectedLabelColor     = Cream,
            containerColor         = Color(0xFF2C2C2E),
            labelColor             = Color(0xFFAEAEB2),
        ),
    )
}

@Composable
private fun FaceDetailCard(face: FaceDetection, modifier: Modifier = Modifier) {
    val domainColor = when (face.domain) {
        FaceDomain.HUMAN     -> HumanBlue
        FaceDomain.ANIMATED  -> AnimeViolet
        FaceDomain.AMBIGUOUS -> AmbigGray
    }
    Surface(color = Color(0xFF2C2C2E), modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = domainColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        face.domain.name,
                        color    = domainColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    "Conf: ${String.format("%.1f", face.confidence * 100)}%",
                    color = Color(0xFF8E8E93), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
            val landmarkText = buildString {
                if (face.landmarks68.isNotEmpty()) append("68-pt human landmarks  ")
                if (face.landmarks28.isNotEmpty()) append("28-pt anime landmarks")
                if (face.landmarks68.isEmpty() && face.landmarks28.isEmpty()) append("No landmarks (bbox only)")
            }
            Text(landmarkText, color = Cream, fontSize = 12.sp)

            face.eyeDistanceNorm?.let { ed ->
                Text("Eye distance: ${String.format("%.4f", ed)} (normalised)", color = Color(0xFF8E8E93), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }
            face.gammaEstimate?.let { g ->
                Text("γ estimate: ${String.format("%.3f", g)}", color = Color(0xFF8E8E93), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun TelemetryFooter(telemetry: com.artgrid.mobile.domain.ml.model.FaceUnifiedTelemetry) {
    Surface(color = Color(0xFF111113)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TelChip("Human head", "${telemetry.humanHeadMs} ms", HumanBlue)
            TelChip("Anime head", "${telemetry.animatedHeadMs} ms", AnimeViolet)
            TelChip("Fusion", "${telemetry.fusionMs} ms", Sage)
        }
    }
}

@Composable
private fun TelChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = color, fontSize = 9.sp)
        Text(value, color = Cream, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FaceOverlayCanvas(
    faces: List<FaceDetection>,
    selectedIndex: Int,
    canvasSize: IntSize,
    onFaceTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.pointerInput(faces, canvasSize) {
            detectTapGestures { offset ->
                val w = canvasSize.width.toFloat()
                val h = canvasSize.height.toFloat()
                for ((index, face) in faces.withIndex()) {
                    val l = face.bbox.xNorm * w
                    val t = face.bbox.yNorm * h
                    val r = l + face.bbox.wNorm * w
                    val b = t + face.bbox.hNorm * h
                    if (offset.x in l..r && offset.y in t..b) {
                        onFaceTap(index)
                        break
                    }
                }
            }
        }
    ) {
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        if (w == 0f || h == 0f) return@Canvas

        faces.forEachIndexed { index, face ->
            val isSelected = index == selectedIndex
            val color = when (face.domain) {
                FaceDomain.HUMAN     -> HumanBlue
                FaceDomain.ANIMATED  -> AnimeViolet
                FaceDomain.AMBIGUOUS -> AmbigGray
            }
            val l = face.bbox.xNorm * w
            val t = face.bbox.yNorm * h
            val bw = face.bbox.wNorm * w
            val bh = face.bbox.hNorm * h

            // Bbox rect
            drawRect(
                color   = color.copy(alpha = if (isSelected) 1f else 0.6f),
                topLeft = Offset(l, t),
                size    = Size(bw, bh),
                style   = Stroke(width = if (isSelected) 3f else 1.5f),
            )

            // 68-pt landmarks (human)
            face.landmarks68.forEach { lm ->
                drawCircle(color.copy(alpha = 0.8f), radius = 3f, center = Offset(lm.xNorm * w, lm.yNorm * h))
            }
            // 28-pt landmarks (anime)
            face.landmarks28.forEach { lm ->
                drawCircle(AnimeViolet.copy(alpha = 0.9f), radius = 4f, center = Offset(lm.xNorm * w, lm.yNorm * h))
            }
        }
    }
}

