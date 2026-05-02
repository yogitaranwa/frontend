/**
 * ObjectDetailScreen.kt
 * Responsibility : Shows the cropped region of a selected object with full filter palette,
 *                  color picker tap-to-sample, K-M paint mix, and export.
 * API calls      : none
 * Injects        : NativePipelineViewModel
 */
package com.artgrid.mobile.ui.objects

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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.domain.ml.model.NormBbox
import com.artgrid.mobile.nativebridge.ArtGridNative
import com.artgrid.mobile.nativebridge.ColorSampleResult
import com.artgrid.mobile.nativebridge.KmResult
import com.artgrid.mobile.ui.home.NativePipelineState
import com.artgrid.mobile.ui.home.NativePipelineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetailScreen(
    imageUri: Uri,
    objectBbox: NormBbox,
    objectLabel: String = "Object",
    onNavigateBack: () -> Unit,
    nativeVm: NativePipelineViewModel = hiltViewModel(),
) {
    val uiState  by nativeVm.uiState.collectAsStateWithLifecycle()
    val context  = LocalContext.current

    LaunchedEffect(imageUri) { nativeVm.loadImage(imageUri) }

    // Crop object region
    var objectCrop by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uiState.sourceBitmap) {
        objectCrop = uiState.sourceBitmap?.let { cropRegion(it, objectBbox) }
    }

    var showColorPicker by remember { mutableStateOf(false) }
    var sampledColor    by remember { mutableStateOf<ColorSampleResult?>(null) }
    var kmResult        by remember { mutableStateOf<KmResult?>(null) }
    var selectedMedium  by remember { mutableIntStateOf(0) } // 0=watercolour, 1=acrylic
    var canvasSize      by remember { mutableStateOf(IntSize.Zero) }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    )

    BottomSheetScaffold(
        scaffoldState   = scaffoldState,
        sheetPeekHeight = 200.dp,
        topBar = {
            TopAppBar(
                title = { Text(objectLabel.replaceFirstChar { it.uppercase() } + " Detail", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    // Filters
                    IconButton(onClick = { nativeVm.runEdgeExtraction() }) { Icon(Icons.Outlined.GridOn, "Edges") }
                    IconButton(onClick = { nativeVm.runGreyscale() }) { Icon(Icons.Outlined.Contrast, "Greyscale") }
                    IconButton(onClick = { nativeVm.runKuwahara() }) { Icon(Icons.Outlined.Grain, "Simplify") }
                    objectCrop?.let { bmp ->
                        IconButton(onClick = { exportBitmapToPng(context, bmp, "object_detail") }) {
                            Icon(Icons.Outlined.FileDownload, "Export", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
            )
        },
        sheetContent = {
            ObjectDetailControls(
                sampledColor   = sampledColor,
                kmResult       = kmResult,
                selectedMedium = selectedMedium,
                showColorPicker = showColorPicker,
                onToggleColorPicker = { showColorPicker = !showColorPicker },
                onSelectMedium = { selectedMedium = it },
                onSolveMix = {
                    sampledColor?.let { c ->
                        nativeVm.runKmSolve(
                            (c.r * 255).toInt(), (c.g * 255).toInt(), (c.b * 255).toInt(), selectedMedium)
                    }
                },
                onReset = { objectCrop?.let { nativeVm.loadImage(imageUri) } },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.padding(innerPadding).fillMaxSize().background(Color.Black).onSizeChanged { canvasSize = it },
            contentAlignment = Alignment.Center,
        ) {
            val displayBitmap = listOf(
                uiState.edgeState, uiState.greyscaleState, uiState.kuwaharaState,
            ).filterIsInstance<NativePipelineState.Success<Bitmap>>().firstOrNull()?.value ?: objectCrop

            if (displayBitmap == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }
                val t = rememberTransformableState { z, p, _ -> scale=(scale*z).coerceIn(0.5f,8f); offsetX+=p.x; offsetY+=p.y }
                Canvas(
                    modifier = Modifier.fillMaxSize().clipToBounds()
                        .transformable(t)
                        .graphicsLayer(scaleX=scale, scaleY=scale, translationX=offsetX, translationY=offsetY)
                        .pointerInput(showColorPicker, displayBitmap) {
                            if (!showColorPicker) return@pointerInput
                            detectTapGestures { tap ->
                                // Map tap into displayBitmap pixel space — displayBitmap is the crop,
                                // so sampling it directly gives the correct on-screen colour.
                                val bx = ((tap.x / canvasSize.width) * displayBitmap.width).toInt().coerceIn(0, displayBitmap.width - 1)
                                val by_ = ((tap.y / canvasSize.height) * displayBitmap.height).toInt().coerceIn(0, displayBitmap.height - 1)
                                nativeVm.runColorSampleOn(displayBitmap, bx, by_)
                            }
                        },
                ) {
                    drawImage(displayBitmap.asImageBitmap(), dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
                    if (showColorPicker) drawRect(Color(0x33FFD700), size = Size(size.width, size.height), style = Stroke(3f))
                }
            }
        }
    }

    // Sync sampled color
    LaunchedEffect(uiState.colorSampleState) {
        (uiState.colorSampleState as? NativePipelineState.Success<*>)?.value?.let {
            sampledColor = it as? ColorSampleResult
        }
    }
    LaunchedEffect(uiState.kmState) {
        (uiState.kmState as? NativePipelineState.Success<*>)?.value?.let {
            kmResult = it as? KmResult
        }
    }
}

private fun cropRegion(src: Bitmap, bbox: NormBbox): Bitmap {
    val x = (bbox.xNorm * src.width).toInt().coerceIn(0, src.width - 1)
    val y = (bbox.yNorm * src.height).toInt().coerceIn(0, src.height - 1)
    val w = (bbox.wNorm * src.width).toInt().coerceIn(1, src.width - x)
    val h = (bbox.hNorm * src.height).toInt().coerceIn(1, src.height - y)
    return Bitmap.createBitmap(src, x, y, w, h)
}

@Composable
private fun ObjectDetailControls(
    sampledColor: ColorSampleResult?,
    kmResult: KmResult?,
    selectedMedium: Int,
    showColorPicker: Boolean,
    onToggleColorPicker: () -> Unit,
    onSelectMedium: (Int) -> Unit,
    onSolveMix: () -> Unit,
    onReset: () -> Unit,
) {
    val mediums = listOf("Watercolour", "Acrylic")
    Column(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(40.dp).height(4.dp).background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f), RoundedCornerShape(2.dp)))
        }
        Text("Object Detail", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = if (showColorPicker) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp), onClick = onToggleColorPicker, modifier = Modifier.weight(1f),
            ) {
                Text("Color Picker", Modifier.padding(10.dp, 8.dp), style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (showColorPicker) FontWeight.Bold else FontWeight.Normal,
                    color = if (showColorPicker) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp), onClick = onReset, modifier = Modifier.weight(1f),
            ) {
                Row(Modifier.padding(10.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Reset", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        sampledColor?.let { c ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(c.r, c.g, c.b), shape = RoundedCornerShape(8.dp), modifier = Modifier.size(44.dp)) {}
                Column {
                    val hex = "#%02X%02X%02X".format((c.r*255).toInt(), (c.g*255).toInt(), (c.b*255).toInt())
                    Text(hex, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text("L=%.2f a=%.2f b=%.2f".format(c.oklabL, c.oklabA, c.oklabB), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("~${c.kelvin.toInt()}K", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mediums.forEachIndexed { idx, name ->
                    FilterChip(
                        selected = selectedMedium == idx,
                        onClick  = { onSelectMedium(idx) },
                        label    = { Text(name) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Button(onClick = onSolveMix, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.ColorLens, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Get Paint Mix")
            }
            kmResult?.let { km ->
                HorizontalDivider()
                Text("Paint Mix — ${(km.similarityScore * 100).toInt()}% match",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                km.recipe.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.name, style = MaterialTheme.typography.bodySmall)
                        Text("${(entry.weight * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
                Text("⚠ Mix suggestion approximate — accuracy varies by paint brand.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun exportBitmapToPng(context: Context, bitmap: Bitmap, prefix: String) {
    val name = "${prefix}_${System.currentTimeMillis()}.png"
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArtGrid")
    }
    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        Toast.makeText(context, "Saved to Pictures/ArtGrid", Toast.LENGTH_SHORT).show()
    } ?: Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
}
