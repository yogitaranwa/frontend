/**
 * AnalysisResultScreen.kt
 * Responsibility : Image playground — on-device Native C++ filters and overlays.
 *
 * Changes in this version:
 *  - Grid step selector (1 / 2 / 5 / 10 cm chips) with explicit "Adjacent lines: X cm" readout
 *  - Fallback (uncalibrated) division grid now labels each line as 0% / 25% / 50% etc.
 *  - EdgeMode tri-state: Off / Dimmed+Edges / Edges Only
 *  - Sensitivity slider for edge extraction (0.5 – 3.0)
 *  - A→B tap-to-measure: tap once sets A, second tap sets B, shows distance in cm
 *    (zoom is auto-reset to 1× when measurement mode is enabled for accuracy)
 *  - "Original Image Size" wired to canvas calibration sheet
 *  - Export writes to Pictures/ArtGrid
 */
package com.artgrid.mobile.ui.results

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint as NativePaint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.ui.canvas.CanvasCalibrationSheet
import com.artgrid.mobile.ui.canvas.CanvasViewModel
import com.artgrid.mobile.ui.home.NativePipelineState
import com.artgrid.mobile.ui.home.NativePipelineUiState
import com.artgrid.mobile.ui.home.NativePipelineViewModel
import kotlin.math.sqrt

// ── Edge display mode ─────────────────────────────────────────────────────────

enum class EdgeMode {
    Off,          // no edge overlay
    DimmedPhoto,  // source drawn at 40% alpha + edge lines on top (high contrast)
    EdgesOnly,    // black background, white edge lines only
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisResultScreen(
    imageUri: Uri,
    onNavigateBack: () -> Unit,
    viewModel: NativePipelineViewModel = hiltViewModel(),
    canvasVm: CanvasViewModel          = hiltViewModel(),
) {
    val uiState     by viewModel.uiState.collectAsStateWithLifecycle()
    val canvasState by canvasVm.state.collectAsStateWithLifecycle()
    val context     = LocalContext.current

    LaunchedEffect(imageUri) { viewModel.loadImage(imageUri) }
    LaunchedEffect(uiState.sourceBitmap) {
        uiState.sourceBitmap?.let { bmp ->
            if (!canvasState.confirmed) canvasVm.onImageLoaded(bmp.width, bmp.height)
        }
    }

    // ── Overlay toggles ───────────────────────────────────────────────────────
    var showGrid       by remember { mutableStateOf(false) }
    var edgeMode       by remember { mutableStateOf(EdgeMode.Off) }
    var edgeSensitivity by remember { mutableFloatStateOf(1.2f) }
    var showCmLabels   by remember { mutableStateOf(true) }
    var showAbMeasure  by remember { mutableStateOf(false) }
    var showCanvasSheet by remember { mutableStateOf(false) }
    var gridStepCm     by remember { mutableFloatStateOf(2f) }

    // A→B measurement state (normalised 0-1 in composable space)
    var measureA by remember { mutableStateOf<Offset?>(null) }
    var measureB by remember { mutableStateOf<Offset?>(null) }

    // Zoom state — reset when entering A-B measure mode
    var scale   by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(showAbMeasure) {
        if (showAbMeasure) { scale = 1f; offsetX = 0f; offsetY = 0f; measureA = null; measureB = null }
    }

    // Trigger edge extraction whenever mode or sensitivity changes (but not Off)
    LaunchedEffect(edgeMode, edgeSensitivity) {
        when (edgeMode) {
            EdgeMode.Off         -> { /* nothing — old result stays in state but canvas won't use it */ }
            EdgeMode.DimmedPhoto -> viewModel.runEdgeExtraction(sensitivity = edgeSensitivity, overlay = true)
            EdgeMode.EdgesOnly   -> viewModel.runEdgeExtraction(sensitivity = edgeSensitivity, overlay = false)
        }
    }

    if (showCanvasSheet) {
        ModalBottomSheet(onDismissRequest = { showCanvasSheet = false }) {
            CanvasCalibrationSheet(
                canvasState           = canvasState,
                onSelectPreset        = canvasVm::selectPreset,
                onSetCustom           = canvasVm::setCustomSize,
                onSelectOriginalImage = { dpi -> canvasVm.selectOriginalImageSize(dpi) },
                onConfirm             = { canvasVm.confirmMapping(); showCanvasSheet = false },
            )
        }
    }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    )

    BottomSheetScaffold(
        scaffoldState   = scaffoldState,
        sheetPeekHeight = 210.dp,
        topBar = {
            TopAppBar(
                title = { Text("Image Playground", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    // A-B toggle
                    IconButton(onClick = { showAbMeasure = !showAbMeasure }) {
                        Icon(if (showAbMeasure) Icons.Outlined.LinearScale else Icons.Outlined.Straighten,
                            "A-B Measure",
                            tint = if (showAbMeasure) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Grid toggle
                    IconButton(onClick = { showGrid = !showGrid }) {
                        Icon(Icons.Outlined.GridOn, "Grid",
                            tint = if (showGrid) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Canvas size
                    IconButton(onClick = { showCanvasSheet = true }) {
                        Icon(Icons.Outlined.CropFree, "Canvas Size",
                            tint = if (canvasState.confirmed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Export
                    resolveActiveBitmap(uiState)?.let { bmp ->
                        IconButton(onClick = { exportBitmapToPng(context, bmp) }) {
                            Icon(Icons.Outlined.FileDownload, "Export", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
            )
        },
        sheetContent = {
            FilterPalette(
                isProcessing      = uiState.isProcessing,
                canvasState       = canvasState,
                showGrid          = showGrid,
                edgeMode          = edgeMode,
                edgeSensitivity   = edgeSensitivity,
                showCmLabels      = showCmLabels,
                showAbMeasure     = showAbMeasure,
                gridStepCm        = gridStepCm,
                measureA          = measureA,
                measureB          = measureB,
                onEdge            = { viewModel.runEdgeExtraction(edgeSensitivity) },
                onGreyscale       = { viewModel.runGreyscale() },
                onTonal           = { viewModel.runTonalHeatmap() },
                onWhiteBalance    = { viewModel.runWhiteBalance() },
                onInvert          = { viewModel.runInvert() },
                onKuwahara        = { viewModel.runKuwahara() },
                onPerspective     = { viewModel.runPerspectiveCorrect() },
                onReset           = { viewModel.loadImage(imageUri) },
                onToggleGrid      = { showGrid = !showGrid },
                onSetEdgeMode     = { edgeMode = it },
                onSensitivityChange = { edgeSensitivity = it },
                onToggleCmLabels  = { showCmLabels = !showCmLabels },
                onToggleAbMeasure = { showAbMeasure = !showAbMeasure; measureA = null; measureB = null },
                onSetGridStep     = { gridStepCm = it },
                onOpenCanvas      = { showCanvasSheet = true },
                onClearMeasure    = { measureA = null; measureB = null },
            )
        },
    ) { innerPadding ->
        PlaygroundCanvas(
            uiState          = uiState,
            canvasState      = canvasState,
            showGrid         = showGrid,
            edgeMode         = edgeMode,
            showCmLabels     = showCmLabels,
            showAbMeasure    = showAbMeasure,
            gridStepCm       = gridStepCm,
            measureA         = measureA,
            measureB         = measureB,
            scale            = scale,
            offsetX          = offsetX,
            offsetY          = offsetY,
            onScaleChange    = { scale = it },
            onOffsetChange   = { dx, dy -> offsetX += dx; offsetY += dy },
            onTapForMeasure  = { norm ->
                when {
                    measureA == null -> measureA = norm
                    measureB == null -> measureB = norm
                    else             -> { measureA = norm; measureB = null }
                }
            },
            modifier         = Modifier.padding(innerPadding),
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun resolveActiveBitmap(uiState: NativePipelineUiState): Bitmap? = when {
    uiState.edgeState         is NativePipelineState.Success<*> -> (uiState.edgeState         as NativePipelineState.Success<*>).value as? Bitmap
    uiState.greyscaleState    is NativePipelineState.Success<*> -> (uiState.greyscaleState    as NativePipelineState.Success<*>).value as? Bitmap
    uiState.tonalState        is NativePipelineState.Success<*> -> (uiState.tonalState        as NativePipelineState.Success<*>).value as? Bitmap
    uiState.whiteBalanceState is NativePipelineState.Success<*> -> (uiState.whiteBalanceState as NativePipelineState.Success<*>).value as? Bitmap
    uiState.invertState       is NativePipelineState.Success<*> -> (uiState.invertState       as NativePipelineState.Success<*>).value as? Bitmap
    uiState.kuwaharaState     is NativePipelineState.Success<*> -> (uiState.kuwaharaState     as NativePipelineState.Success<*>).value as? Bitmap
    uiState.perspectiveState  is NativePipelineState.Success<*> -> (uiState.perspectiveState  as NativePipelineState.Success<*>).value as? Bitmap
    else                                                         -> uiState.sourceBitmap
}

/** Resolves the edge bitmap from state if available. */
private fun resolveEdgeBitmap(uiState: NativePipelineUiState): Bitmap? =
    (uiState.edgeState as? NativePipelineState.Success<*>)?.value as? Bitmap

// ── Canvas ────────────────────────────────────────────────────────────────────

@Composable
private fun PlaygroundCanvas(
    uiState: NativePipelineUiState,
    canvasState: com.artgrid.mobile.ui.canvas.CanvasUiState,
    showGrid: Boolean,
    edgeMode: EdgeMode,
    showCmLabels: Boolean,
    showAbMeasure: Boolean,
    gridStepCm: Float,
    measureA: Offset?,
    measureB: Offset?,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Float, Float) -> Unit,
    onTapForMeasure: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceBitmap = uiState.sourceBitmap
    val edgeBitmap   = resolveEdgeBitmap(uiState)
    // Determine what to render: for filter results (non-edge) use resolveActiveBitmap
    val filterBitmap = resolveActiveBitmap(uiState)
    var canvasDisplaySize by remember { mutableStateOf(IntSize.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        if (!showAbMeasure) {
            onScaleChange((scale * zoomChange).coerceIn(0.5f, 6f))
            onOffsetChange(panChange.x, panChange.y)
        }
    }

    Box(
        modifier         = modifier.fillMaxSize().background(Color.Black).onSizeChanged { canvasDisplaySize = it },
        contentAlignment = Alignment.Center,
    ) {
        when {
            uiState.isProcessing && filterBitmap == null ->
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
            filterBitmap != null -> {
                val bmpW = filterBitmap.width.toFloat()
                val bmpH = filterBitmap.height.toFloat()

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(showAbMeasure, canvasDisplaySize) {
                            if (!showAbMeasure) return@pointerInput
                            detectTapGestures { tap ->
                                val normX = tap.x / size.width.toFloat()
                                val normY = tap.y / size.height.toFloat()
                                onTapForMeasure(Offset(normX.coerceIn(0f, 1f), normY.coerceIn(0f, 1f)))
                            }
                        }
                        .transformable(state = transformState)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                ) {
                    val dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    val sX = size.width  / bmpW
                    val sY = size.height / bmpH
                    val cmPerPxX = canvasState.cmPerPxX
                    val cmPerPxY = canvasState.cmPerPxY

                    // ── Edge mode compositing ──────────────────────────────────
                    when (edgeMode) {
                        EdgeMode.Off -> {
                            drawImage(filterBitmap.asImageBitmap(), dstSize = dstSize)
                        }
                        EdgeMode.DimmedPhoto -> {
                            // Source at 40% alpha, then edge lines on top
                            drawImage(
                                filterBitmap.asImageBitmap(), dstSize = dstSize,
                                alpha = 0.40f,
                            )
                            edgeBitmap?.let {
                                drawImage(it.asImageBitmap(), dstSize = dstSize,
                                    blendMode = BlendMode.Screen)
                            }
                        }
                        EdgeMode.EdgesOnly -> {
                            // Black background, edge lines only (overlay=false produces white-on-black)
                            drawRect(Color.Black, size = size)
                            edgeBitmap?.let { drawImage(it.asImageBitmap(), dstSize = dstSize) }
                        }
                    }

                    // ── Grid ──────────────────────────────────────────────────
                    if (showGrid) {
                        if (canvasState.confirmed && cmPerPxX != null && cmPerPxY != null) {
                            drawCmGrid(cmPerPxX, cmPerPxY, bmpW, bmpH, sX, sY, showCmLabels, gridStepCm)
                        } else {
                            drawDivisionGrid(8)
                        }
                    }

                    // ── A-B Measurement ───────────────────────────────────────
                    if (showAbMeasure) {
                        drawAbMeasurement(
                            measureA   = measureA,
                            measureB   = measureB,
                            canvasState= canvasState,
                            bmpW       = bmpW,
                            bmpH       = bmpH,
                        )
                    }

                    // Spinner overlay while processing (edge re-extraction etc.)
                    if (uiState.isProcessing) {
                        drawRect(Color.Black.copy(alpha = 0.35f), size = size)
                    }
                }
            }
            else -> Text("Loading image…", style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(0.6f), textAlign = TextAlign.Center)
        }
    }
}

// ── DrawScope extensions ──────────────────────────────────────────────────────

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCmGrid(
    cmPerPxX: Float, cmPerPxY: Float,
    bmpW: Float, bmpH: Float, sX: Float, sY: Float,
    showLabels: Boolean,
    stepCm: Float = 2f,
) {
    if (cmPerPxX <= 0f || cmPerPxY <= 0f || stepCm <= 0f) return
    val stepX  = (stepCm / cmPerPxX) * sX
    val stepY  = (stepCm / cmPerPxY) * sY

    val textPaint = NativePaint().apply {
        color = 0xDDFFFFFF.toInt(); textSize = 26f; isAntiAlias = true
        typeface = Typeface.MONOSPACE; setShadowLayer(4f, 1f, 1f, 0xAA000000.toInt())
    }
    val minorPaint = NativePaint().apply {
        color = 0xAAFFFFFF.toInt(); textSize = 20f; isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, 0xAA000000.toInt())
    }

    var x = 0f; var ci = 0
    while (x <= size.width) {
        drawLine(Color(0x44FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
        val cmVal = ci * stepCm
        if (showLabels && ci > 0 && x < size.width - 30f) {
            val label = if (cmVal >= 10f && cmVal % 10f == 0f) "${cmVal.toInt()}" else "${cmVal.toInt()}"
            drawContext.canvas.nativeCanvas.drawText(
                label, x + 2f, 22f,
                if (cmVal % 10f == 0f) textPaint else minorPaint
            )
        }
        x += stepX; ci++
    }
    var y = 0f; var ri = 0
    while (y <= size.height) {
        drawLine(Color(0x44FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
        val cmVal = ri * stepCm
        if (showLabels && ri > 0 && y < size.height - 10f) {
            drawContext.canvas.nativeCanvas.drawText(
                "${cmVal.toInt()}cm", 4f, y - 4f,
                if (cmVal % 10f == 0f) textPaint else minorPaint
            )
        }
        y += stepY; ri++
    }
}

/**
 * Fallback grid when no canvas calibration is set.
 * Draws [divisions] equal-spaced lines and labels each at the percentage of image width/height
 * so the user clearly sees this is a relative—not real-world—measurement.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDivisionGrid(divisions: Int) {
    val stepX = size.width  / divisions.toFloat()
    val stepY = size.height / divisions.toFloat()
    val labelPaint = NativePaint().apply {
        color = 0xAAFFFFFF.toInt(); textSize = 22f; isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, 0xAA000000.toInt())
    }

    var x = stepX; var xi = 1
    while (x < size.width) {
        drawLine(Color(0x33FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
        val pct = (xi * 100 / divisions)
        drawContext.canvas.nativeCanvas.drawText("$pct%", x + 2f, 22f, labelPaint)
        x += stepX; xi++
    }
    var y = stepY; var yi = 1
    while (y < size.height) {
        drawLine(Color(0x33FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
        val pct = (yi * 100 / divisions)
        drawContext.canvas.nativeCanvas.drawText("$pct%", 4f, y - 4f, labelPaint)
        y += stepY; yi++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAbMeasurement(
    measureA: Offset?,
    measureB: Offset?,
    canvasState: com.artgrid.mobile.ui.canvas.CanvasUiState,
    bmpW: Float,
    bmpH: Float,
) {
    val abLabelPaint = NativePaint().apply {
        color = 0xFFFFFFFF.toInt(); textSize = 34f; isAntiAlias = true
        isFakeBoldText = true; setShadowLayer(5f, 2f, 2f, 0xFF000000.toInt())
    }
    val distPaint = NativePaint().apply {
        color = 0xFFFFDE00.toInt(); textSize = 30f; isAntiAlias = true
        isFakeBoldText = true; setShadowLayer(5f, 2f, 2f, 0xFF000000.toInt())
    }

    measureA?.let { a ->
        val ax = a.x * size.width
        val ay = a.y * size.height

        // Point A
        drawCircle(Color(0xFFFF4081), 14f, Offset(ax, ay))
        drawCircle(Color.White, 6f, Offset(ax, ay))
        drawContext.canvas.nativeCanvas.drawText("A", ax + 18f, ay - 8f, abLabelPaint)

        measureB?.let { b ->
            val bx = b.x * size.width
            val by_ = b.y * size.height

            // Point B
            drawCircle(Color(0xFF69F0AE), 14f, Offset(bx, by_))
            drawCircle(Color.White, 6f, Offset(bx, by_))
            drawContext.canvas.nativeCanvas.drawText("B", bx + 18f, by_ - 8f, abLabelPaint)

            // Dashed line A→B
            drawLine(
                Color.White, Offset(ax, ay), Offset(bx, by_),
                strokeWidth = 2.5f,
                pathEffect  = PathEffect.dashPathEffect(floatArrayOf(12f, 6f)),
            )

            // Distance in cm
            val cmX = canvasState.cmPerPxX
            val cmY = canvasState.cmPerPxY
            if (cmX != null && cmY != null) {
                val dxPx = (b.x - a.x) * bmpW
                val dyPx = (b.y - a.y) * bmpH
                val distCm = sqrt((dxPx * cmX) * (dxPx * cmX) + (dyPx * cmY) * (dyPx * cmY))
                val midX   = (ax + bx) / 2f
                val midY   = (ay + by_) / 2f
                val label  = "%.2f cm".format(distCm)
                val textW  = distPaint.measureText(label)
                drawRect(
                    Color(0xCC000000),
                    Offset(midX - textW / 2f - 8f, midY - 38f),
                    Size(textW + 16f, 44f),
                    style = androidx.compose.ui.graphics.drawscope.Fill,
                )
                drawContext.canvas.nativeCanvas.drawText(label, midX - textW / 2f, midY - 6f, distPaint)
            } else {
                val dxPx = (b.x - a.x) * bmpW
                val dyPx = (b.y - a.y) * bmpH
                val distPx = sqrt(dxPx * dxPx + dyPx * dyPx)
                val midX   = (ax + bx) / 2f
                val midY   = (ay + by_) / 2f
                val label  = "%.0f px (set canvas for cm)".format(distPx)
                val textW  = distPaint.measureText(label)
                drawRect(Color(0xCC000000), Offset(midX - textW / 2f - 8f, midY - 38f), Size(textW + 16f, 44f))
                drawContext.canvas.nativeCanvas.drawText(label, midX - textW / 2f, midY - 6f, distPaint)
            }
        }
    }

    // Instruction hint when no points set
    if (measureA == null) {
        val hintPaint = NativePaint().apply {
            color = 0xCCFFFFFF.toInt(); textSize = 28f; isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(4f, 1f, 1f, 0xAA000000.toInt())
        }
        drawContext.canvas.nativeCanvas.drawText(
            "Tap to set point A", size.width / 2f, size.height / 2f + 10f, hintPaint)
    } else if (measureB == null) {
        val hintPaint = NativePaint().apply {
            color = 0xCCFFFFFF.toInt(); textSize = 28f; isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(4f, 1f, 1f, 0xAA000000.toInt())
        }
        drawContext.canvas.nativeCanvas.drawText(
            "Tap to set point B", size.width / 2f, size.height - 30f, hintPaint)
    }
}

// ── Bottom-sheet filter palette ───────────────────────────────────────────────

private val GRID_STEP_OPTIONS = listOf(1f, 2f, 5f, 10f)

@Composable
private fun FilterPalette(
    isProcessing: Boolean,
    canvasState: com.artgrid.mobile.ui.canvas.CanvasUiState,
    showGrid: Boolean,
    edgeMode: EdgeMode,
    edgeSensitivity: Float,
    showCmLabels: Boolean,
    showAbMeasure: Boolean,
    gridStepCm: Float,
    measureA: Offset?,
    measureB: Offset?,
    onEdge: () -> Unit, onGreyscale: () -> Unit, onTonal: () -> Unit,
    onWhiteBalance: () -> Unit, onInvert: () -> Unit, onKuwahara: () -> Unit,
    onPerspective: () -> Unit, onReset: () -> Unit,
    onToggleGrid: () -> Unit,
    onSetEdgeMode: (EdgeMode) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onToggleCmLabels: () -> Unit, onToggleAbMeasure: () -> Unit,
    onSetGridStep: (Float) -> Unit,
    onOpenCanvas: () -> Unit, onClearMeasure: () -> Unit,
) {
    data class Filter(val label: String, val icon: ImageVector, val action: () -> Unit)

    val filters = listOf(
        Filter("Edges",       Icons.Outlined.GridOn,        onEdge),
        Filter("Greyscale",   Icons.Outlined.Contrast,      onGreyscale),
        Filter("Tonal Map",   Icons.Outlined.Brightness6,   onTonal),
        Filter("White Bal",   Icons.Outlined.WbSunny,       onWhiteBalance),
        Filter("Invert",      Icons.Outlined.InvertColors,  onInvert),
        Filter("Simplify",    Icons.Outlined.Grain,         onKuwahara),
        Filter("Perspective", Icons.Outlined.FlipToBack,    onPerspective),
    )

    Column(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(40.dp).height(4.dp).background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f), RoundedCornerShape(2.dp)))
        }

        // A-B Measurement banner
        if (showAbMeasure) {
            Surface(
                color    = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("A→B Measure Mode",
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        val status = when {
                            measureA == null && measureB == null -> "Tap image to set point A"
                            measureB == null -> "Point A set · Tap to set point B"
                            else -> "A and B set · tap A to start new"
                        }
                        Text(status, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.8f))
                        if (!canvasState.confirmed) {
                            Text("⚠ Set canvas size to get cm distances",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    FilledTonalButton(onClick = onClearMeasure) { Text("Clear") }
                }
            }
        }

        // Filter row
        Text("Filters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(filters) { f ->
                FilterChipIcon(f.label, f.icon, !isProcessing, f.action)
            }
        }

        // ── Edge Mode section ─────────────────────────────────────────────────
        Text("Edges", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EdgeMode.values().forEach { mode ->
                val label = when (mode) {
                    EdgeMode.Off         -> "Off"
                    EdgeMode.DimmedPhoto -> "Dimmed"
                    EdgeMode.EdgesOnly   -> "Lines Only"
                }
                Surface(
                    color    = if (edgeMode == mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape    = RoundedCornerShape(8.dp),
                    onClick  = { onSetEdgeMode(mode) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(label,
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = if (edgeMode == mode) FontWeight.Bold else FontWeight.Normal,
                        color      = if (edgeMode == mode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier   = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                        textAlign  = TextAlign.Center,
                    )
                }
            }
        }
        if (edgeMode != EdgeMode.Off) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Sensitivity", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(72.dp))
                Slider(
                    value         = edgeSensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange    = 0.5f..3.0f,
                    steps         = 9,
                    modifier      = Modifier.weight(1f),
                )
                Text("%.1f".format(edgeSensitivity), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(28.dp))
            }
        }

        // ── Grid + Overlays row ───────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverlayChip("Grid",    showGrid,       onToggleGrid,       Modifier.weight(1f))
            OverlayChip("cm",      showCmLabels,   onToggleCmLabels,   Modifier.weight(1f))
            OverlayChip("A→B",     showAbMeasure,  onToggleAbMeasure,  Modifier.weight(1f))
            FilledTonalButton(onClick = onOpenCanvas, modifier = Modifier.wrapContentWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Outlined.CropFree, null, Modifier.size(18.dp))
            }
        }

        // ── Grid step selector (only when grid is on) ─────────────────────────
        if (showGrid) {
            Text(
                text = if (canvasState.confirmed)
                    "Grid step · Adjacent lines: ${gridStepCm.toInt()} cm"
                else
                    "Grid step (approx — calibrate canvas for real cm)",
                style    = MaterialTheme.typography.labelSmall,
                color    = if (canvasState.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GRID_STEP_OPTIONS.forEach { step ->
                    Surface(
                        color    = if (gridStepCm == step) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape    = RoundedCornerShape(8.dp),
                        onClick  = { onSetGridStep(step) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${step.toInt()}cm",
                            style      = MaterialTheme.typography.labelSmall,
                            fontWeight = if (gridStepCm == step) FontWeight.Bold else FontWeight.Normal,
                            color      = if (gridStepCm == step) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier   = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                            textAlign  = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // Canvas info
        if (canvasState.confirmed) {
            Text(
                text = "Canvas: ${canvasState.selectedPreset.name} · 1px = %.4fcm × %.4fcm".format(
                    canvasState.cmPerPxX ?: 0f, canvasState.cmPerPxY ?: 0f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text("Tap ⬜ to set canvas → enable cm grid & A-B distances",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Reset
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onReset, enabled = !isProcessing, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset Filters")
            }
        }
    }
}

@Composable
private fun OverlayChip(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color    = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape    = RoundedCornerShape(8.dp), onClick = onClick, modifier = modifier,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun FilterChipIcon(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color    = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, label, Modifier.size(24.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Export ────────────────────────────────────────────────────────────────────

private fun exportBitmapToPng(context: Context, bitmap: Bitmap) {
    val name = "artgrid_${System.currentTimeMillis()}.png"
    val cv   = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArtGrid")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri      = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: run {
        Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show(); return
    }
    resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0); resolver.update(uri, cv, null, null)
    }
    Toast.makeText(context, "Saved to Pictures/ArtGrid", Toast.LENGTH_SHORT).show()
}
