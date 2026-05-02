/**
 * CropScreen.kt
 * Responsibility : F-28 in-app non-destructive image crop.
 *                  Renders crop handles over the image; the confirmed crop rect is passed
 *                  back to the caller — actual pixel cropping happens in the canvas pipeline.
 * Pattern used   : Stateless composable + HiltViewModel
 * Dependencies   : CropViewModel, Coil (image display)
 */
package com.artgrid.mobile.ui.crop

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

private val Charcoal    = Color(0xFF1C1C1E)
private val Cream       = Color(0xFFF5F0E8)
private val Sienna      = Color(0xFFC85C3A)
private val SiennaLight = Color(0xFFE8896A)
private val GridLine    = Color(0x99FFFFFF)
private val HandleColor = Color(0xFFFFFFFF)
private val MaskColor   = Color(0x88000000)

private data class AspectPreset(val label: String, val w: Float, val h: Float)

private val ASPECT_PRESETS = listOf(
    AspectPreset("Free", 0f, 0f),
    AspectPreset("1:1", 1f, 1f),
    AspectPreset("4:3", 4f, 3f),
    AspectPreset("16:9", 16f, 9f),
    AspectPreset("A4", 21f, 29.7f),
    AspectPreset("A5", 14.85f, 21f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    imageUri: Uri,
    onCropConfirmed: (CropRect) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: CropViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(imageUri) { viewModel.setImageUri(imageUri) }

    Scaffold(
        containerColor = Charcoal,
        topBar = {
            TopAppBar(
                title = {
                    Text("Crop Image", color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Cream)
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.resetCrop() }) {
                        Text("Reset", color = SiennaLight)
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { onCropConfirmed(state.cropRect) },
                        colors  = ButtonDefaults.buttonColors(containerColor = Sienna),
                        shape   = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Apply", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111113)),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Crop canvas ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0A0A0C)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model           = imageUri,
                    contentDescription = "Source image",
                    contentScale    = ContentScale.Fit,
                    modifier        = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { canvasSize = it.size },
                )

                // Crop overlay + draggable handles
                CropOverlay(
                    cropRect   = state.cropRect,
                    canvasSize = canvasSize,
                    onHandle   = { handle, dx, dy ->
                        if (canvasSize.width > 0 && canvasSize.height > 0) {
                            viewModel.onHandleDrag(
                                handle,
                                dx / canvasSize.width,
                                dy / canvasSize.height,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ── Controls ──────────────────────────────────────────────────────
            Surface(
                color    = Color(0xFF1C1C1E),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Aspect ratio chips
                    Text(
                        "Aspect Ratio",
                        color    = Color(0xFF8E8E93),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ASPECT_PRESETS.forEach { preset ->
                            val isFree     = preset.w == 0f
                            val isSelected = if (isFree) !state.aspectLocked else
                                state.aspectLocked && kotlin.math.abs(state.lockedAspect - preset.w / preset.h) < 0.01f
                            FilterChip(
                                selected = isSelected,
                                onClick  = {
                                    if (isFree) viewModel.toggleAspectLock(false)
                                    else viewModel.setPresetAspect(preset.w, preset.h)
                                },
                                label    = { Text(preset.label, fontSize = 12.sp) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor    = Sienna,
                                    selectedLabelColor        = Color.White,
                                    containerColor            = Color(0xFF2C2C2E),
                                    labelColor                = Cream,
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Crop info row
                    val rect = state.cropRect
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CropInfoChip("L: ${(rect.left * 100).toInt()}%")
                        CropInfoChip("T: ${(rect.top * 100).toInt()}%")
                        CropInfoChip("W: ${(rect.width * 100).toInt()}%")
                        CropInfoChip("H: ${(rect.height * 100).toInt()}%")
                    }
                }
            }
        }
    }
}

@Composable
private fun CropInfoChip(text: String) {
    Surface(
        color  = Color(0xFF2C2C2E),
        shape  = RoundedCornerShape(6.dp),
    ) {
        Text(
            text     = text,
            color    = Cream,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CropOverlay(
    cropRect: CropRect,
    canvasSize: IntSize,
    onHandle: (CropHandle, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeHandle by remember { mutableStateOf<CropHandle?>(null) }
    val handleRadiusDp = 14.dp
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { handleRadiusDp.toPx() }

    fun normToPixel(norm: Float, max: Int) = norm * max

    Canvas(
        modifier = modifier.pointerInput(canvasSize) {
            detectDragGestures(
                onDragStart = { offset ->
                    val w = canvasSize.width.toFloat()
                    val h = canvasSize.height.toFloat()
                    val tl = Offset(normToPixel(cropRect.left, canvasSize.width), normToPixel(cropRect.top, canvasSize.height))
                    val tr = Offset(normToPixel(cropRect.right, canvasSize.width), normToPixel(cropRect.top, canvasSize.height))
                    val bl = Offset(normToPixel(cropRect.left, canvasSize.width), normToPixel(cropRect.bottom, canvasSize.height))
                    val br = Offset(normToPixel(cropRect.right, canvasSize.width), normToPixel(cropRect.bottom, canvasSize.height))
                    activeHandle = when {
                        (offset - tl).getDistance() < handleRadiusPx * 2 -> CropHandle.TOP_LEFT
                        (offset - tr).getDistance() < handleRadiusPx * 2 -> CropHandle.TOP_RIGHT
                        (offset - bl).getDistance() < handleRadiusPx * 2 -> CropHandle.BOTTOM_LEFT
                        (offset - br).getDistance() < handleRadiusPx * 2 -> CropHandle.BOTTOM_RIGHT
                        offset.x > tl.x && offset.x < br.x && offset.y > tl.y && offset.y < br.y -> CropHandle.MOVE
                        else -> null
                    }
                },
                onDrag = { _, dragAmount ->
                    activeHandle?.let { onHandle(it, dragAmount.x, dragAmount.y) }
                },
                onDragEnd   = { activeHandle = null },
                onDragCancel = { activeHandle = null },
            )
        }
    ) {
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        if (w == 0f || h == 0f) return@Canvas

        val l = cropRect.left   * w
        val t = cropRect.top    * h
        val r = cropRect.right  * w
        val b = cropRect.bottom * h

        // Dark mask outside crop rect
        drawRect(MaskColor, topLeft = Offset(0f, 0f), size = Size(l, h))
        drawRect(MaskColor, topLeft = Offset(r, 0f), size = Size(w - r, h))
        drawRect(MaskColor, topLeft = Offset(l, 0f), size = Size(r - l, t))
        drawRect(MaskColor, topLeft = Offset(l, b), size = Size(r - l, h - b))

        // Rule-of-thirds grid lines
        val wThird = (r - l) / 3f
        val hThird = (b - t) / 3f
        for (i in 1..2) {
            drawLine(GridLine, start = Offset(l + wThird * i, t), end = Offset(l + wThird * i, b), strokeWidth = 1f)
            drawLine(GridLine, start = Offset(l, t + hThird * i), end = Offset(r, t + hThird * i), strokeWidth = 1f)
        }

        // Crop border
        drawRect(
            color     = Sienna,
            topLeft   = Offset(l, t),
            size      = Size(r - l, b - t),
            style     = Stroke(width = 2f),
        )

        // Corner handles
        val hr = handleRadiusPx
        val corners = listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))
        corners.forEach { c ->
            drawCircle(HandleColor, radius = hr, center = c)
            drawCircle(Sienna, radius = hr - 3f, center = c)
        }
    }
}

