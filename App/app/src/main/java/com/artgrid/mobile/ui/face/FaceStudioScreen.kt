/**
 * FaceStudioScreen.kt
 * Responsibility : F-10 Face Analysis — shows original image with 68-point landmark overlay,
 *                  grid in real cm, EdgeMode tri-state (Off/Dimmed/EdgesOnly), sensitivity slider,
 *                  grid step selector, measurement panel (distances in cm),
 *                  canvas size selection (once per import), and navigation to isolated face detail.
 * API calls      : none (consumes FaceResult from HomeViewModel via nav args)
 * Injects        : NativePipelineViewModel, CanvasViewModel
 */
package com.artgrid.mobile.ui.face

import android.graphics.Bitmap
import android.graphics.Paint as NativePaint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.domain.ml.model.FaceLandmark
import com.artgrid.mobile.domain.ml.model.FaceResult
import com.artgrid.mobile.domain.ml.model.NormBbox
import com.artgrid.mobile.ui.canvas.CanvasCalibrationSheet
import com.artgrid.mobile.ui.canvas.CanvasViewModel
import com.artgrid.mobile.ui.home.NativePipelineState
import com.artgrid.mobile.ui.home.NativePipelineViewModel
import kotlin.math.sqrt

// dlib landmark index groups for artistic measurement
private val FACE_OUTLINE   = (0..16).toList()
private val LEFT_BROW      = (17..21).toList()
private val RIGHT_BROW     = (22..26).toList()
private val NOSE_BRIDGE    = (27..30).toList()
private val NOSE_BASE      = (30..35).toList()
private val LEFT_EYE       = (36..41).toList() + listOf(36)
private val RIGHT_EYE      = (42..47).toList() + listOf(42)
private val OUTER_LIP      = (48..59).toList() + listOf(48)
private val INNER_LIP      = (60..67).toList() + listOf(60)

private data class MeasurementPair(val name: String, val idxA: Int, val idxB: Int)
private val MEASUREMENTS = listOf(
    MeasurementPair("Eye Distance (IPD)", 36, 45),
    MeasurementPair("Nose Bridge Height", 27, 30),
    MeasurementPair("Lip Width",          48, 54),
    MeasurementPair("Jaw Width",           0, 16),
    MeasurementPair("Face Height",        27,  8),
    MeasurementPair("Left Eye Width",     36, 39),
    MeasurementPair("Right Eye Width",    42, 45),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceStudioScreen(
    imageUri: Uri,
    faceResult: FaceResult,
    onNavigateBack: () -> Unit,
    onNavigateToFaceDetail: (Uri, NormBbox) -> Unit,
    nativeVm: NativePipelineViewModel = hiltViewModel(),
    canvasVm: CanvasViewModel         = hiltViewModel(),
) {
    val uiState     by nativeVm.uiState.collectAsStateWithLifecycle()
    val canvasState by canvasVm.state.collectAsStateWithLifecycle()
    val context     = LocalContext.current

    // Load image once
    LaunchedEffect(imageUri) {
        nativeVm.loadImage(imageUri)
    }

    // Notify canvas VM of image dimensions once bitmap loaded
    LaunchedEffect(uiState.sourceBitmap) {
        uiState.sourceBitmap?.let { bmp ->
            if (!canvasState.confirmed) canvasVm.onImageLoaded(bmp.width, bmp.height)
        }
    }

    // Toggle states
    var showGrid         by remember { mutableStateOf(true) }
    var showLandmarks    by remember { mutableStateOf(true) }
    var showMeasurements by remember { mutableStateOf(true) }
    var showCmLabels     by remember { mutableStateOf(true) }
    var gridStepCm       by remember { mutableFloatStateOf(2f) }

    // Edge mode + sensitivity
    var edgeMode         by remember { mutableStateOf(com.artgrid.mobile.ui.results.EdgeMode.Off) }
    var edgeSensitivity  by remember { mutableFloatStateOf(1.2f) }

    // Re-run edge extraction whenever mode or sensitivity changes
    LaunchedEffect(edgeMode, edgeSensitivity) {
        when (edgeMode) {
            com.artgrid.mobile.ui.results.EdgeMode.Off         -> { /* no extraction */ }
            com.artgrid.mobile.ui.results.EdgeMode.DimmedPhoto -> nativeVm.runEdgeExtraction(sensitivity = edgeSensitivity, overlay = true)
            com.artgrid.mobile.ui.results.EdgeMode.EdgesOnly   -> nativeVm.runEdgeExtraction(sensitivity = edgeSensitivity, overlay = false)
        }
    }

    // Bottom sheets
    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        )
    )
    var showCanvasSheet by remember { mutableStateOf(!canvasState.confirmed) }

    // Open canvas sheet automatically if not confirmed yet
    LaunchedEffect(canvasState.confirmed) {
        if (!canvasState.confirmed) showCanvasSheet = true
    }

    if (showCanvasSheet) {
        ModalBottomSheet(onDismissRequest = { showCanvasSheet = false }) {
            CanvasCalibrationSheet(
                canvasState          = canvasState,
                onSelectPreset       = canvasVm::selectPreset,
                onSetCustom          = canvasVm::setCustomSize,
                onSelectOriginalImage = { dpi -> canvasVm.selectOriginalImageSize(dpi) },
                onConfirm            = {
                    canvasVm.confirmMapping()
                    showCanvasSheet = false
                },
            )
        }
    }

    BottomSheetScaffold(
        scaffoldState  = sheetState,
        sheetPeekHeight = 200.dp,
        topBar = {
            TopAppBar(
                title = { Text("Face Studio", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Canvas picker icon
                    IconButton(onClick = { showCanvasSheet = true }) {
                        Icon(Icons.Outlined.CropFree, "Canvas Size", tint = MaterialTheme.colorScheme.primary)
                    }
                    // Export
                    val exportBmp = uiState.sourceBitmap
                    if (exportBmp != null) {
                        IconButton(onClick = { /* export handled in sheet */ }) {
                            Icon(Icons.Outlined.FileDownload, "Export")
                        }
                    }
                },
            )
        },
        sheetContent = {
            FaceStudioControls(
                faceResult         = faceResult,
                canvasState        = canvasState,
                showGrid           = showGrid,
                edgeMode           = edgeMode,
                edgeSensitivity    = edgeSensitivity,
                showLandmarks      = showLandmarks,
                showMeasurements   = showMeasurements,
                showCmLabels       = showCmLabels,
                gridStepCm         = gridStepCm,
                onToggleGrid       = { showGrid = !showGrid },
                onSetEdgeMode      = { edgeMode = it },
                onSensitivityChange = { edgeSensitivity = it },
                onToggleLandmarks  = { showLandmarks = !showLandmarks },
                onToggleMeasure    = { showMeasurements = !showMeasurements },
                onToggleCmLabels   = { showCmLabels = !showCmLabels },
                onSetGridStep      = { gridStepCm = it },
                onOpenFaceDetail   = {
                    faceResult.faceBbox?.let { bbox ->
                        onNavigateToFaceDetail(imageUri, bbox)
                    }
                },
            )
        },
    ) { innerPadding ->
        FaceStudioCanvas(
            sourceBitmap     = uiState.sourceBitmap,
            edgeBitmap       = (uiState.edgeState as? NativePipelineState.Success<*>)?.value as? Bitmap,
            landmarks        = faceResult.landmarks,
            faceBbox         = faceResult.faceBbox,
            canvasState      = canvasState,
            showGrid         = showGrid,
            edgeMode         = edgeMode,
            showLandmarks    = showLandmarks,
            showMeasurements = showMeasurements,
            showCmLabels     = showCmLabels,
            gridStepCm       = gridStepCm,
            isProcessing     = uiState.isProcessing,
            modifier         = Modifier.padding(innerPadding),
        )
    }
}

// ── Canvas rendering ──────────────────────────────────────────────────────────

@Composable
private fun FaceStudioCanvas(
    sourceBitmap: Bitmap?,
    edgeBitmap: Bitmap?,
    landmarks: List<FaceLandmark>,
    faceBbox: NormBbox?,
    canvasState: com.artgrid.mobile.ui.canvas.CanvasUiState,
    showGrid: Boolean,
    edgeMode: com.artgrid.mobile.ui.results.EdgeMode,
    showLandmarks: Boolean,
    showMeasurements: Boolean,
    showCmLabels: Boolean,
    gridStepCm: Float,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
) {
    var scale   by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale   = (scale * zoom).coerceIn(0.5f, 8f)
        offsetX += pan.x; offsetY += pan.y
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { canvasSize = it },
        contentAlignment = Alignment.Center,
    ) {
        if (isProcessing && sourceBitmap == null) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            return@Box
        }
        // Base bitmap to show (source unless EdgesOnly where we show black + edge)
        val baseBitmap = sourceBitmap
        if (baseBitmap == null) {
            Text("Loading image…", color = Color.White.copy(alpha = 0.5f))
            return@Box
        }

        val bmpW = baseBitmap.width.toFloat()
        val bmpH = baseBitmap.height.toFloat()
        val dstSize = androidx.compose.ui.unit.IntSize(0, 0) // placeholder; computed in drawScope

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .transformable(state = transform)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
        ) {
            val canvasDst = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())

            // ── Edge-aware image compositing ───────────────────────────────────
            when (edgeMode) {
                com.artgrid.mobile.ui.results.EdgeMode.Off -> {
                    drawImage(baseBitmap.asImageBitmap(), dstSize = canvasDst)
                }
                com.artgrid.mobile.ui.results.EdgeMode.DimmedPhoto -> {
                    drawImage(baseBitmap.asImageBitmap(), dstSize = canvasDst, alpha = 0.40f)
                    edgeBitmap?.let {
                        drawImage(it.asImageBitmap(), dstSize = canvasDst, blendMode = BlendMode.Screen)
                    }
                }
                com.artgrid.mobile.ui.results.EdgeMode.EdgesOnly -> {
                    drawRect(Color.Black, size = size)
                    edgeBitmap?.let { drawImage(it.asImageBitmap(), dstSize = canvasDst) }
                }
            }

            val scaleX = size.width  / bmpW
            val scaleY = size.height / bmpH
            val cmPerPxX = canvasState.cmPerPxX ?: 0f
            val cmPerPxY = canvasState.cmPerPxY ?: 0f

            // Grid overlay
            if (showGrid) {
                if (canvasState.confirmed && cmPerPxX > 0f && cmPerPxY > 0f) {
                    drawGridOverlay(cmPerPxX, cmPerPxY, bmpW, bmpH, scaleX, scaleY, showCmLabels, gridStepCm)
                } else {
                    drawFallbackDivisionGrid(8)
                }
            }

            // Landmark overlay
            if (showLandmarks && landmarks.isNotEmpty()) {
                drawLandmarkOverlay(landmarks, bmpW, bmpH, scaleX, scaleY)
            }

            // Measurement lines
            if (showMeasurements && landmarks.isNotEmpty() && canvasState.confirmed) {
                drawMeasurementLines(landmarks, bmpW, bmpH, scaleX, scaleY, cmPerPxX, cmPerPxY, showCmLabels)
            }

            // Face bbox
            if (faceBbox != null) {
                drawFaceBbox(faceBbox, bmpW, bmpH, scaleX, scaleY)
            }
        }

        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(40.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        }
    }
}

private fun DrawScope.drawGridOverlay(
    cmPerPxX: Float, cmPerPxY: Float,
    bmpW: Float, bmpH: Float,
    scaleX: Float, scaleY: Float,
    showLabels: Boolean,
    gridStepCm: Float = 2f,
) {
    if (cmPerPxX <= 0f || cmPerPxY <= 0f || gridStepCm <= 0f) return
    val stepPxX    = gridStepCm / cmPerPxX
    val stepPxY    = gridStepCm / cmPerPxY
    val lineColor  = Color(0x55FFFFFF)

    val textPaint = NativePaint().apply {
        color     = 0xDDFFFFFF.toInt()
        textSize  = 28f
        isAntiAlias = true
        typeface  = Typeface.MONOSPACE
        setShadowLayer(4f, 1f, 1f, 0xAA000000.toInt())
    }
    val labelPaint = NativePaint().apply {
        color     = 0xBBFFFFFF.toInt()
        textSize  = 22f
        isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, 0xAA000000.toInt())
    }

    var x = 0f; var colIdx = 0
    while (x <= bmpW) {
        val px = x * scaleX
        drawLine(lineColor, Offset(px, 0f), Offset(px, bmpH * scaleY), strokeWidth = 1f)
        if (showLabels && colIdx > 0 && px < bmpW * scaleX - 30f) {
            val cmVal = colIdx * gridStepCm
            val paint = if (cmVal % 10f == 0f) textPaint else labelPaint
            drawContext.canvas.nativeCanvas.drawText(
                "${cmVal.toInt()}", px + 3f, 24f, paint
            )
        }
        colIdx++; x += stepPxX
    }
    var y = 0f; var rowIdx = 0
    while (y <= bmpH) {
        val py = y * scaleY
        drawLine(lineColor, Offset(0f, py), Offset(bmpW * scaleX, py), strokeWidth = 1f)
        if (showLabels && rowIdx > 0 && py < bmpH * scaleY - 10f) {
            val cmVal = rowIdx * gridStepCm
            val paint = if (cmVal % 10f == 0f) textPaint else labelPaint
            drawContext.canvas.nativeCanvas.drawText(
                "${cmVal.toInt()}cm", 4f, py - 4f, paint
            )
        }
        rowIdx++; y += stepPxY
    }
}

/** Fallback grid when canvas is uncalibrated — labels show percentage of image width/height. */
private fun DrawScope.drawFallbackDivisionGrid(divisions: Int) {
    val stepX = size.width  / divisions.toFloat()
    val stepY = size.height / divisions.toFloat()
    val labelPaint = NativePaint().apply {
        color = 0xAAFFFFFF.toInt(); textSize = 22f; isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, 0xAA000000.toInt())
    }
    var x = stepX; var xi = 1
    while (x < size.width) {
        drawLine(Color(0x33FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
        drawContext.canvas.nativeCanvas.drawText("${xi * 100 / divisions}%", x + 2f, 22f, labelPaint)
        x += stepX; xi++
    }
    var y = stepY; var yi = 1
    while (y < size.height) {
        drawLine(Color(0x33FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
        drawContext.canvas.nativeCanvas.drawText("${yi * 100 / divisions}%", 4f, y - 4f, labelPaint)
        y += stepY; yi++
    }
}

private fun DrawScope.drawLandmarkOverlay(
    landmarks: List<FaceLandmark>,
    bmpW: Float, bmpH: Float, scaleX: Float, scaleY: Float,
) {
    fun lm(idx: Int) = landmarks.getOrNull(idx)?.let {
        Offset(it.xNorm * bmpW * scaleX, it.yNorm * bmpH * scaleY)
    }

    val lineGroups = listOf(FACE_OUTLINE, LEFT_BROW, RIGHT_BROW, NOSE_BRIDGE, NOSE_BASE,
        LEFT_EYE, RIGHT_EYE, OUTER_LIP, INNER_LIP)
    lineGroups.forEach { group ->
        for (i in 0 until group.size - 1) {
            val a = lm(group[i]) ?: continue
            val b = lm(group[i + 1]) ?: continue
            drawLine(Color(0xFFFFD700), a, b, strokeWidth = 2f)
        }
    }
    landmarks.forEach { lm ->
        drawCircle(Color(0xFFFF4081), radius = 3f, center = Offset(lm.xNorm * bmpW * scaleX, lm.yNorm * bmpH * scaleY))
    }
}

private fun DrawScope.drawMeasurementLines(
    landmarks: List<FaceLandmark>,
    bmpW: Float, bmpH: Float, scaleX: Float, scaleY: Float,
    cmPerPxX: Float, cmPerPxY: Float, showLabels: Boolean,
) {
    MEASUREMENTS.forEach { m ->
        val a = landmarks.getOrNull(m.idxA) ?: return@forEach
        val b = landmarks.getOrNull(m.idxB) ?: return@forEach
        val ax = a.xNorm * bmpW * scaleX; val ay = a.yNorm * bmpH * scaleY
        val bx = b.xNorm * bmpW * scaleX; val by_ = b.yNorm * bmpH * scaleY
        drawLine(Color(0xFF00E5FF), Offset(ax, ay), Offset(bx, by_), strokeWidth = 2f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 4f)))
        val dxPx = (b.xNorm - a.xNorm) * bmpW
        val dyPx = (b.yNorm - a.yNorm) * bmpH
        val distCm = sqrt((dxPx * cmPerPxX) * (dxPx * cmPerPxX) + (dyPx * cmPerPxY) * (dyPx * cmPerPxY))
    }
}

private fun DrawScope.drawFaceBbox(bbox: NormBbox, bmpW: Float, bmpH: Float, scaleX: Float, scaleY: Float) {
    drawRect(
        color     = Color(0xFF76FF03),
        topLeft   = Offset(bbox.xNorm * bmpW * scaleX, bbox.yNorm * bmpH * scaleY),
        size      = androidx.compose.ui.geometry.Size(bbox.wNorm * bmpW * scaleX, bbox.hNorm * bmpH * scaleY),
        style     = Stroke(width = 3f),
    )
}

// ── Bottom-sheet controls ─────────────────────────────────────────────────────

private val FACE_GRID_STEP_OPTIONS = listOf(1f, 2f, 5f, 10f)

@Composable
private fun FaceStudioControls(
    faceResult: FaceResult,
    canvasState: com.artgrid.mobile.ui.canvas.CanvasUiState,
    showGrid: Boolean,
    edgeMode: com.artgrid.mobile.ui.results.EdgeMode,
    edgeSensitivity: Float,
    showLandmarks: Boolean, showMeasurements: Boolean, showCmLabels: Boolean,
    gridStepCm: Float,
    onToggleGrid: () -> Unit,
    onSetEdgeMode: (com.artgrid.mobile.ui.results.EdgeMode) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onToggleLandmarks: () -> Unit, onToggleMeasure: () -> Unit,
    onToggleCmLabels: () -> Unit,
    onSetGridStep: (Float) -> Unit,
    onOpenFaceDetail: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Handle
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(40.dp).height(4.dp).background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(2.dp)))
        }

        Text("Face Studio Controls", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        // Toggle row
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ToggleChip("Grid",         showGrid,         onToggleGrid,         Modifier.weight(1f))
            ToggleChip("Landmarks",    showLandmarks,    onToggleLandmarks,    Modifier.weight(1f))
            ToggleChip("Measures",     showMeasurements, onToggleMeasure,      Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ToggleChip("cm Labels",    showCmLabels,     onToggleCmLabels,     Modifier.weight(1f))
        }

        // Edge mode tri-state
        Text("Edge Mode", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            com.artgrid.mobile.ui.results.EdgeMode.values().forEach { mode ->
                val label = when (mode) {
                    com.artgrid.mobile.ui.results.EdgeMode.Off         -> "Off"
                    com.artgrid.mobile.ui.results.EdgeMode.DimmedPhoto -> "Dimmed"
                    com.artgrid.mobile.ui.results.EdgeMode.EdgesOnly   -> "Lines Only"
                }
                ToggleChip(label, edgeMode == mode, { onSetEdgeMode(mode) }, Modifier.weight(1f))
            }
        }
        if (edgeMode != com.artgrid.mobile.ui.results.EdgeMode.Off) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Sensitivity", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(72.dp))
                Slider(
                    value         = edgeSensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange    = 0.5f..3.0f,
                    steps         = 9,
                    modifier      = Modifier.weight(1f),
                )
                Text("%.1f".format(edgeSensitivity), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
            }
        }

        // Grid step selector
        if (showGrid) {
            Text(
                text = if (canvasState.confirmed)
                    "Grid step · Adjacent lines: ${gridStepCm.toInt()} cm"
                else
                    "Grid step (approx — calibrate for real cm)",
                style = MaterialTheme.typography.labelSmall,
                color = if (canvasState.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                FACE_GRID_STEP_OPTIONS.forEach { step ->
                    ToggleChip("${step.toInt()}cm", gridStepCm == step, { onSetGridStep(step) }, Modifier.weight(1f))
                }
            }
        }

        // Canvas size info
        if (canvasState.confirmed) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    text = "Canvas: ${canvasState.selectedPreset.name} · " +
                            "1px = %.4fcm × %.4fcm".format(canvasState.cmPerPxX ?: 0f, canvasState.cmPerPxY ?: 0f),
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        // Measurements table
        if (showMeasurements && faceResult.faceDetected && canvasState.confirmed) {
            HorizontalDivider()
            Text("Distances in cm", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            faceResult.landmarks.takeIf { it.size >= 68 }?.let { lms ->
                MEASUREMENTS.forEach { m ->
                    val a = lms.getOrNull(m.idxA); val b = lms.getOrNull(m.idxB)
                    if (a != null && b != null && canvasState.imagePxW > 0) {
                        val dxPx = (b.xNorm - a.xNorm) * canvasState.imagePxW
                        val dyPx = (b.yNorm - a.yNorm) * canvasState.imagePxH
                        val cmX = canvasState.cmPerPxX ?: 0f
                        val cmY = canvasState.cmPerPxY ?: 0f
                        val dist = sqrt((dxPx * cmX) * (dxPx * cmX) + (dyPx * cmY) * (dyPx * cmY))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(m.name, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("%.2f cm".format(dist), style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            } ?: Text("Need full 68 landmarks for measurements", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!faceResult.faceDetected) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Text("No face detected in this image.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        // Open face detail
        if (faceResult.faceDetected && faceResult.faceBbox != null) {
            Button(onClick = onOpenFaceDetail, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Face, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Isolated Face")
            }
        }

        Text(
            text = "Face analysis is optimised for photographic references. Inference: ${faceResult.inferenceMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleChip(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color    = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape    = RoundedCornerShape(8.dp),
        onClick  = onClick,
        modifier = modifier,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

// (Surface with onClick is provided by Material3 — no stub needed)
