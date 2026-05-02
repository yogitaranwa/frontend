/**
 * FaceDetailScreen.kt
 * Responsibility : Shows only the cropped face region (original-resolution crop via faceBbox).
 *                  Features: grid overlay + cm measurements + tap-to-sample color picker
 *                  + all face feature toggles + export.
 * API calls      : none (all on-device)
 * Injects        : NativePipelineViewModel, CanvasViewModel
 */
package com.artgrid.mobile.ui.face

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.domain.ml.model.NormBbox
import com.artgrid.mobile.nativebridge.ColorSampleResult
import com.artgrid.mobile.ui.canvas.CanvasViewModel
import com.artgrid.mobile.ui.home.NativePipelineState
import com.artgrid.mobile.ui.home.NativePipelineViewModel
import java.io.OutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceDetailScreen(
    imageUri: Uri,
    faceBbox: NormBbox,
    onNavigateBack: () -> Unit,
    nativeVm: NativePipelineViewModel = hiltViewModel(),
    canvasVm: CanvasViewModel          = hiltViewModel(),
) {
    val uiState     by nativeVm.uiState.collectAsStateWithLifecycle()
    val canvasState by canvasVm.state.collectAsStateWithLifecycle()
    val context     = LocalContext.current

    // Load original image
    LaunchedEffect(imageUri) { nativeVm.loadImage(imageUri) }

    // Crop the face from the original bitmap (original-resolution)
    var faceCrop by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uiState.sourceBitmap, faceBbox) {
        faceCrop = uiState.sourceBitmap?.let { cropFace(it, faceBbox) }
    }

    var showGrid         by remember { mutableStateOf(true) }
    var showEdges        by remember { mutableStateOf(false) }
    var showMeasurements by remember { mutableStateOf(true) }
    var showColorPicker  by remember { mutableStateOf(false) }
    var sampledColor     by remember { mutableStateOf<ColorSampleResult?>(null) }
    var canvasSize       by remember { mutableStateOf(IntSize.Zero) }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    )

    BottomSheetScaffold(
        scaffoldState   = scaffoldState,
        sheetPeekHeight = 180.dp,
        topBar = {
            TopAppBar(
                title = { Text("Face Detail", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    faceCrop?.let { bmp ->
                        IconButton(onClick = { exportBitmapToPng(context, bmp, "face_detail") }) {
                            Icon(Icons.Outlined.FileDownload, "Export", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
            )
        },
        sheetContent = {
            FaceDetailControls(
                sampledColor     = sampledColor,
                showGrid         = showGrid,
                showEdges        = showEdges,
                showMeasurements = showMeasurements,
                showColorPicker  = showColorPicker,
                onToggleGrid       = { showGrid = !showGrid },
                onToggleEdges      = { showEdges = !showEdges },
                onToggleMeasure    = { showMeasurements = !showMeasurements },
                onToggleColorPicker = { showColorPicker = !showColorPicker },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { canvasSize = it },
            contentAlignment = Alignment.Center,
        ) {
            when {
                uiState.isProcessing && faceCrop == null -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                faceCrop != null -> {
                    val bmp = faceCrop!!
                    val imageBitmap = bmp.asImageBitmap()
                    var scale   by remember { mutableFloatStateOf(1f) }
                    var offsetX by remember { mutableFloatStateOf(0f) }
                    var offsetY by remember { mutableFloatStateOf(0f) }
                    val transform = rememberTransformableState { zoom, pan, _ ->
                        scale   = (scale * zoom).coerceIn(0.5f, 8f)
                        offsetX += pan.x; offsetY += pan.y
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .transformable(state = transform)
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                            .pointerInput(showColorPicker, bmp) {
                                if (!showColorPicker) return@pointerInput
                                detectTapGestures { tapOffset ->
                                    if (canvasSize == IntSize.Zero) return@detectTapGestures
                                    // Map tap into face-crop pixel space — bmp IS the crop,
                                    // so sampling it directly gives the correct on-screen colour.
                                    val bmpX = ((tapOffset.x / canvasSize.width) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                                    val bmpY = ((tapOffset.y / canvasSize.height) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                                    nativeVm.runColorSampleOn(bmp, bmpX, bmpY)
                                }
                            },
                    ) {
                        drawImage(imageBitmap, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                        if (showGrid) {
                            val step = size.width / 6f
                            var x = step
                            while (x < size.width) { drawLine(Color(0x66FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                            var y = step
                            while (y < size.height) { drawLine(Color(0x66FFFFFF), Offset(0f, y), Offset(size.width, y), 1f); y += step }
                        }
                        if (showColorPicker) {
                            drawRect(Color(0x33FFD700), size = Size(size.width, size.height), style = Stroke(3f))
                        }
                    }
                }
                else -> Text("Cropping face…", color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center)
            }
        }
    }

    // Sync sampled color from ViewModel state
    val colorState = uiState.colorSampleState
    LaunchedEffect(colorState) {
        if (colorState is NativePipelineState.Success<*>) {
            sampledColor = colorState.value as? ColorSampleResult
        }
    }
}

// ── Face crop helper ──────────────────────────────────────────────────────────

private fun cropFace(src: Bitmap, bbox: NormBbox): Bitmap {
    val x = (bbox.xNorm * src.width).toInt().coerceIn(0, src.width - 1)
    val y = (bbox.yNorm * src.height).toInt().coerceIn(0, src.height - 1)
    val w = (bbox.wNorm * src.width).toInt().coerceIn(1, src.width - x)
    val h = (bbox.hNorm * src.height).toInt().coerceIn(1, src.height - y)
    return Bitmap.createBitmap(src, x, y, w, h)
}

// ── Controls sheet ────────────────────────────────────────────────────────────

@Composable
private fun FaceDetailControls(
    sampledColor: ColorSampleResult?,
    showGrid: Boolean, showEdges: Boolean,
    showMeasurements: Boolean, showColorPicker: Boolean,
    onToggleGrid: () -> Unit, onToggleEdges: () -> Unit,
    onToggleMeasure: () -> Unit, onToggleColorPicker: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(40.dp).height(4.dp).background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f), RoundedCornerShape(2.dp)))
        }
        Text("Face Detail", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FaceDetailToggleChip("Grid", showGrid, onToggleGrid, Modifier.weight(1f))
            FaceDetailToggleChip("Color Picker", showColorPicker, onToggleColorPicker, Modifier.weight(1f))
        }

        if (showColorPicker) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text("Tap anywhere on the face to sample the color.",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        sampledColor?.let { c ->
            HorizontalDivider()
            Text("Sampled Color", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color  = Color(c.r, c.g, c.b),
                    shape  = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(48.dp),
                ) {}
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val hex = "#%02X%02X%02X".format((c.r * 255).toInt(), (c.g * 255).toInt(), (c.b * 255).toInt())
                    Text(hex, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Oklab L=%.2f a=%.2f b=%.2f".format(c.oklabL, c.oklabA, c.oklabB),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("HSL %.0f° %.0f%% %.0f%%".format(c.hslH * 360f, c.hslS * 100f, c.hslL * 100f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("~${c.kelvin.toInt()}K · Lightness ${c.lightnessPct.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FaceDetailToggleChip(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color    = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape    = RoundedCornerShape(8.dp),
        onClick  = onClick,
        modifier = modifier,
    ) {
        Text(text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

// ── Export ────────────────────────────────────────────────────────────────────

private fun exportBitmapToPng(context: Context, bitmap: Bitmap, prefix: String) {
    val name = "${prefix}_${System.currentTimeMillis()}.png"
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArtGrid")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { os -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os) }
        Toast.makeText(context, "Saved to Pictures/ArtGrid", Toast.LENGTH_SHORT).show()
    } ?: Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
}
