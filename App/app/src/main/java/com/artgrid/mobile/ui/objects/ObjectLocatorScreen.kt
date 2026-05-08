/**
 * ObjectLocatorScreen.kt
 * Responsibility : Object detection — full image with bounding boxes, grid-cell
 *                  span labels in cm, dropdown object selector, tap-to-select, zoom to cell.
 * API calls      : none (consumes ObjectInferResult passed via nav)
 * Injects        : NativePipelineViewModel, CanvasViewModel
 */
package com.artgrid.mobile.ui.objects

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.domain.ml.model.NormBbox
import com.artgrid.mobile.domain.ml.model.ObjectDetection
import com.artgrid.mobile.domain.ml.model.ObjectInferResult
import com.artgrid.mobile.ui.canvas.CanvasCalibrationSheet
import com.artgrid.mobile.ui.canvas.CanvasViewModel
import com.artgrid.mobile.ui.home.NativePipelineViewModel
import kotlin.math.abs

// Distinct colors for up to 10 object classes
private val BBOX_COLORS = listOf(
    Color(0xFFFF5252), Color(0xFF69F0AE), Color(0xFFFFD740), Color(0xFF40C4FF),
    Color(0xFFE040FB), Color(0xFFFF6D00), Color(0xFF00E5FF), Color(0xFFCCFF90),
    Color(0xFFFF80AB), Color(0xFFFFFF00),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectLocatorScreen(
    imageUri: Uri,
    objectResult: ObjectInferResult,
    onNavigateBack: () -> Unit,
    onNavigateToObjectDetail: (Uri, NormBbox) -> Unit,
    nativeVm: NativePipelineViewModel = hiltViewModel(),
    canvasVm: CanvasViewModel          = hiltViewModel(),
) {
    val uiState     by nativeVm.uiState.collectAsStateWithLifecycle()
    val canvasState by canvasVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(imageUri) { nativeVm.loadImage(imageUri) }
    LaunchedEffect(uiState.sourceBitmap) {
        uiState.sourceBitmap?.let { bmp ->
            if (!canvasState.confirmed) canvasVm.onImageLoaded(bmp.width, bmp.height)
        }
    }

    var selectedIdx     by remember { mutableStateOf<Int?>(null) }
    var showGrid        by remember { mutableStateOf(true) }
    var showLabels      by remember { mutableStateOf(true) }
    var showCmSpan      by remember { mutableStateOf(true) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var canvasSize      by remember { mutableStateOf(IntSize.Zero) }
    var showCanvasSheet by remember { mutableStateOf(!canvasState.confirmed) }

    LaunchedEffect(canvasState.confirmed) { if (!canvasState.confirmed) showCanvasSheet = true }

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
        sheetPeekHeight = 220.dp,
        topBar = {
            TopAppBar(
                title = { Text("Object Locator", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showCanvasSheet = true }) {
                        Icon(Icons.Outlined.CropFree, "Canvas Size", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
        sheetContent = {
            ObjectLocatorControls(
                detections       = objectResult.detections,
                selectedIdx      = selectedIdx,
                canvasState      = canvasState,
                showGrid         = showGrid,
                showLabels       = showLabels,
                showCmSpan       = showCmSpan,
                dropdownExpanded = dropdownExpanded,
                onSelectIdx         = { selectedIdx = it; dropdownExpanded = false },
                onToggleGrid        = { showGrid = !showGrid },
                onToggleLabels      = { showLabels = !showLabels },
                onToggleCmSpan      = { showCmSpan = !showCmSpan },
                onToggleDropdown    = { dropdownExpanded = !dropdownExpanded },
                onOpenDetail        = {
                    val bbox = selectedIdx?.let { objectResult.detections.getOrNull(it)?.bbox }
                    bbox?.let { onNavigateToObjectDetail(imageUri, it) }
                },
                inferenceMs = objectResult.inferenceMs,
            )
        },
    ) { innerPadding ->
        var scale   by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale   = (scale * zoom).coerceIn(0.5f, 8f)
            offsetX += pan.x; offsetY += pan.y
        }

        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { canvasSize = it },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = uiState.sourceBitmap
            if (bmp == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .transformable(state = transform)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                        .pointerInput(objectResult.detections, canvasSize) {
                            detectTapGestures { tap ->
                                if (canvasSize == IntSize.Zero) return@detectTapGestures
                                val relX = tap.x / canvasSize.width
                                val relY = tap.y / canvasSize.height
                                // Find nearest bbox by overlap with tap point
                                val nearest = objectResult.detections.indexOfFirst { det ->
                                    relX >= det.bbox.xNorm && relX <= det.bbox.xNorm + det.bbox.wNorm &&
                                    relY >= det.bbox.yNorm && relY <= det.bbox.yNorm + det.bbox.hNorm
                                }
                                selectedIdx = if (nearest >= 0) nearest else null
                            }
                        },
                ) {
                    drawImage(bmp.asImageBitmap(), dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                    if (showGrid && canvasState.confirmed) {
                        drawObjectGrid(canvasState, bmp, size)
                    }
                    objectResult.detections.forEachIndexed { idx, det ->
                        val color = BBOX_COLORS[idx % BBOX_COLORS.size]
                        val isSelected = selectedIdx == idx
                        drawObjectBbox(det.bbox, det.label, det.confidence, color, isSelected,
                            size, canvasState, showLabels, showCmSpan)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawObjectGrid(
    canvasState: com.artgrid.mobile.ui.canvas.CanvasUiState,
    bmp: Bitmap, area: androidx.compose.ui.geometry.Size,
) {
    val cmX = canvasState.cmPerPxX ?: return
    val cmY = canvasState.cmPerPxY ?: return
    val stepCm = 2f
    val stepPxX = stepCm / cmX * (area.width / bmp.width)
    val stepPxY = stepCm / cmY * (area.height / bmp.height)
    var x = 0f; while (x <= area.width) { drawLine(Color(0x44FFFFFF), Offset(x, 0f), Offset(x, area.height), 1f); x += stepPxX }
    var y = 0f; while (y <= area.height) { drawLine(Color(0x44FFFFFF), Offset(0f, y), Offset(area.width, y), 1f); y += stepPxY }
}

private fun DrawScope.drawObjectBbox(
    bbox: NormBbox, label: String, conf: Float,
    color: Color, isSelected: Boolean,
    area: androidx.compose.ui.geometry.Size,
    canvasState: com.artgrid.mobile.ui.canvas.CanvasUiState,
    showLabels: Boolean, showCmSpan: Boolean,
) {
    val x = bbox.xNorm * area.width
    val y = bbox.yNorm * area.height
    val w = bbox.wNorm * area.width
    val h = bbox.hNorm * area.height
    drawRect(color, Offset(x, y), Size(w, h), style = Stroke(width = if (isSelected) 4f else 2f))
    if (isSelected) drawRect(color.copy(alpha = 0.15f), Offset(x, y), Size(w, h))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ObjectLocatorControls(
    detections: List<ObjectDetection>,
    selectedIdx: Int?,
    canvasState: com.artgrid.mobile.ui.canvas.CanvasUiState,
    showGrid: Boolean, showLabels: Boolean, showCmSpan: Boolean,
    dropdownExpanded: Boolean,
    onSelectIdx: (Int?) -> Unit,
    onToggleGrid: () -> Unit, onToggleLabels: () -> Unit, onToggleCmSpan: () -> Unit,
    onToggleDropdown: () -> Unit, onOpenDetail: () -> Unit,
    inferenceMs: Int,
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
        Text("Object Locator", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        if (detections.isEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Text("No objects detected. Try a different image.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        } else {
            // Dropdown selector
            ExposedDropdownMenuBox(expanded = dropdownExpanded, onExpandedChange = { onToggleDropdown() }) {
                OutlinedTextField(
                    value = selectedIdx?.let {
                        detections.getOrNull(it)?.run { "$label (${(confidence * 100).toInt()}%)" }
                    } ?: "Tap image or select object…",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Selected Object") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = dropdownExpanded, onDismissRequest = { onToggleDropdown() }) {
                    detections.forEachIndexed { idx, det ->
                        DropdownMenuItem(
                            text = { Text("${det.label} (${(det.confidence * 100).toInt()}%)") },
                            onClick = { onSelectIdx(idx) },
                            leadingIcon = {
                                Surface(color = BBOX_COLORS[idx % BBOX_COLORS.size], shape = RoundedCornerShape(4.dp)) {
                                    Spacer(Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                }
            }

            // Selected object info
            selectedIdx?.let { idx ->
                detections.getOrNull(idx)?.let { det ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${det.label.replaceFirstChar { it.uppercase() }} · ${(det.confidence * 100).toInt()}% confidence",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            if (canvasState.confirmed) {
                                val wCm = det.bbox.wNorm * canvasState.canvasWidthCm
                                val hCm = det.bbox.hNorm * canvasState.canvasHeightCm
                                val gridStepCm = 2f
                                val colStart = (det.bbox.xNorm * canvasState.canvasWidthCm / gridStepCm).toInt()
                                val rowStart = (det.bbox.yNorm * canvasState.canvasHeightCm / gridStepCm).toInt()
                                val colEnd   = colStart + (wCm / gridStepCm).toInt()
                                val rowEnd   = rowStart + (hCm / gridStepCm).toInt()
                                Text("Grid span: Col%d–%d · Row%d–%d".format(colStart, colEnd, rowStart, rowEnd),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text("Physical size: %.1f cm × %.1f cm".format(wCm, hCm),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    Button(onClick = onOpenDetail, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.ZoomIn, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Object Detail")
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ObjectChip("Grid", showGrid, onToggleGrid, Modifier.weight(1f))
            ObjectChip("Labels", showLabels, onToggleLabels, Modifier.weight(1f))
            ObjectChip("cm Span", showCmSpan, onToggleCmSpan, Modifier.weight(1f))
        }

        Text("${detections.size} objects detected · ${inferenceMs}ms · YOLOv8n",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ObjectChip(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color    = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape    = RoundedCornerShape(8.dp),
        onClick  = onClick,
        modifier = modifier,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}
