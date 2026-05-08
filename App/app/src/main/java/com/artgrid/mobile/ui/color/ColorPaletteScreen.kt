/**
 * ColorPaletteScreen.kt
 * Tap-to-sample image colours (ColorSampleResult: Oklab/HSL/Kelvin)
 * K–M paint mix — runKmSolve(r, g, b, medium: Int = 0)
 * Auto-palette extraction (K-means dominant colours)
 * NEW  : 8 artist palette groups × 16 swatches each, fully toggleable.
 *        Tap any artist swatch to get its K-M paint mix directly.
 * NEW  : Shadow construction overlay (perspective guides, light-type presets).
 */
package com.artgrid.mobile.ui.color

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.nativebridge.ColorSampleResult
import com.artgrid.mobile.nativebridge.KmRecipeEntry
import com.artgrid.mobile.nativebridge.KmResult
import com.artgrid.mobile.ui.home.NativePipelineState
import com.artgrid.mobile.ui.home.NativePipelineViewModel
import com.artgrid.mobile.ui.color.shadow.LightSourceType
import com.artgrid.mobile.ui.color.shadow.ShadowStudyGuideLayer
import com.artgrid.mobile.ui.color.shadow.ShadowStudyLayout
import com.artgrid.mobile.ui.color.shadow.ShadowHandleId
import com.artgrid.mobile.ui.color.shadow.nearestShadowHandle
import com.artgrid.mobile.ui.color.shadow.withDragDelta
import kotlin.math.roundToInt

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPaletteScreen(
    imageUri: Uri,
    onNavigateBack: () -> Unit,
    viewModel: NativePipelineViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(imageUri) { viewModel.loadImage(imageUri) }

    // ── State ─────────────────────────────────────────────────────────────────
    var crosshair      by remember { mutableStateOf<Offset?>(null) }
    var canvasSize     by remember { mutableStateOf(IntSize.Zero) }
    var sampledColor   by remember { mutableStateOf<ColorSampleResult?>(null) }
    var kmResult       by remember { mutableStateOf<KmResult?>(null) }
    var activeGroupIdx by remember { mutableIntStateOf(0) }
    var swatchArgb     by remember { mutableStateOf<Int?>(null) }   // ARGB from artist palette tap
    var autoPalette    by remember { mutableStateOf<List<Int>>(emptyList()) }
    var paletteCount   by remember { mutableIntStateOf(8) }

    var shadowStudyEnabled by remember { mutableStateOf(false) }
    var shadowLightType    by remember { mutableStateOf(LightSourceType.BULB) }
    var shadowLayout       by remember { mutableStateOf(ShadowStudyLayout.forType(LightSourceType.BULB)) }

    // Collect color sample results from VM
    LaunchedEffect(uiState.colorSampleState) {
        val s = uiState.colorSampleState
        if (s is NativePipelineState.Success<*>) {
            @Suppress("UNCHECKED_CAST")
            sampledColor = s.value as? ColorSampleResult
        }
    }
    LaunchedEffect(uiState.kmState) {
        val s = uiState.kmState
        if (s is NativePipelineState.Success<*>) {
            @Suppress("UNCHECKED_CAST")
            kmResult = s.value as? KmResult
        }
    }
    LaunchedEffect(uiState.paletteState) {
        val s = uiState.paletteState
        if (s is NativePipelineState.Success<*>) {
            @Suppress("UNCHECKED_CAST")
            autoPalette = (s.value as? List<*>)?.filterIsInstance<Int>() ?: emptyList()
        }
    }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
    )

    BottomSheetScaffold(
        scaffoldState   = scaffoldState,
        sheetPeekHeight = 300.dp,
        topBar = {
            TopAppBar(
                title          = { Text("Color Palette", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        sheetContent = {
            ColorControls(
                sampledColor    = sampledColor,
                kmResult        = kmResult,
                autoPalette     = autoPalette,
                swatchArgb      = swatchArgb,
                paletteCount    = paletteCount,
                activeGroupIdx  = activeGroupIdx,
                isLoading       = uiState.isProcessing,
                onGroupSelect   = { activeGroupIdx = it },
                onSwatchTap     = { argb ->
                    swatchArgb   = argb
                    sampledColor = null
                    kmResult     = null
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr  8) and 0xFF
                    val b =  argb         and 0xFF
                    viewModel.runKmSolve(r, g, b, medium = 0)
                },
                onKmFromSampled = { sc ->
                    // ColorSampleResult.r/g/b are normalised [0,1], convert to 0-255
                    viewModel.runKmSolve(
                        r = (sc.r * 255).roundToInt().coerceIn(0, 255),
                        g = (sc.g * 255).roundToInt().coerceIn(0, 255),
                        b = (sc.b * 255).roundToInt().coerceIn(0, 255),
                        medium = 0,
                    )
                },
                onGeneratePalette    = { viewModel.runPaletteExtraction(paletteCount) },
                onPaletteCountChange = { paletteCount = it },
                shadowStudyEnabled   = shadowStudyEnabled,
                onShadowStudyChange  = { shadowStudyEnabled = it },
                shadowLightType      = shadowLightType,
                onShadowLightType    = { t ->
                    shadowLightType = t
                    shadowLayout = ShadowStudyLayout.forType(t)
                },
                shadowLayout         = shadowLayout,
                onShadowLayoutReset  = { shadowLayout = ShadowStudyLayout.forType(shadowLightType) },
                onShadowShelfToggle  = { shadowLayout = shadowLayout.copy(shelfEnabled = !shadowLayout.shelfEnabled) },
            )
        },
    ) { innerPadding ->
        val bmp = uiState.sourceBitmap
        val latestShadowLayout = rememberUpdatedState(shadowLayout)
        val applyShadowLayout = rememberUpdatedState<(ShadowStudyLayout) -> Unit>({ shadowLayout = it })
        val latestBmp = rememberUpdatedState(bmp)
        val latestViewModel = rememberUpdatedState(viewModel)
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { canvasSize = it }
                .pointerInput(shadowStudyEnabled, canvasSize) {
                    if (!shadowStudyEnabled) {
                        detectTapGestures { tap ->
                            val b = latestBmp.value
                            if (canvasSize == IntSize.Zero || b == null) return@detectTapGestures
                            crosshair = tap
                            swatchArgb = null
                            kmResult = null
                            val bx = (tap.x / size.width * b.width).toInt().coerceIn(0, b.width - 1)
                            val by_ = (tap.y / size.height * b.height).toInt().coerceIn(0, b.height - 1)
                            latestViewModel.value.runColorSample(bx, by_)
                        }
                    } else {
                        var active: ShadowHandleId? = null
                        detectDragGestures(
                            onDragStart = { down ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                active = nearestShadowHandle(down, latestShadowLayout.value, w, h, hitRadiusPx = 44f)
                            },
                            onDrag = { change, dragAmount ->
                                val id = active ?: return@detectDragGestures
                                change.consume()
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val next = latestShadowLayout.value.withDragDelta(id, dragAmount, w, h)
                                applyShadowLayout.value(next)
                            },
                            onDragEnd = { active = null },
                            onDragCancel = { active = null },
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bmp == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(bmp.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    crosshair?.let { ch ->
                        drawLine(Color.White.copy(0.8f), Offset(ch.x, 0f), Offset(ch.x, size.height), 1.5f)
                        drawLine(Color.White.copy(0.8f), Offset(0f, ch.y), Offset(size.width, ch.y), 1.5f)
                        drawCircle(Color.White, 8f, ch)
                        drawCircle(Color.Black, 4f, ch)
                    }
                }
                if (shadowStudyEnabled) {
                    ShadowStudyGuideLayer(
                        layout = shadowLayout,
                        lightType = shadowLightType,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// ── Controls ──────────────────────────────────────────────────────────────────

@Composable
private fun ColorControls(
    sampledColor: ColorSampleResult?,
    kmResult: KmResult?,
    autoPalette: List<Int>,
    swatchArgb: Int?,
    paletteCount: Int,
    activeGroupIdx: Int,
    isLoading: Boolean,
    onGroupSelect: (Int) -> Unit,
    onSwatchTap: (Int) -> Unit,
    onKmFromSampled: (ColorSampleResult) -> Unit,
    onGeneratePalette: () -> Unit,
    onPaletteCountChange: (Int) -> Unit,
    shadowStudyEnabled: Boolean,
    onShadowStudyChange: (Boolean) -> Unit,
    shadowLightType: LightSourceType,
    onShadowLightType: (LightSourceType) -> Unit,
    shadowLayout: ShadowStudyLayout,
    onShadowLayoutReset: () -> Unit,
    onShadowShelfToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Drag handle
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(40.dp).height(4.dp).background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f), RoundedCornerShape(2.dp)))
        }

        // ── Shadow construction (perspective guides) ─────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Shadow construction", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Interactive guides (not automatic relighting). Turn off to sample colors from the image.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = shadowStudyEnabled, onCheckedChange = onShadowStudyChange)
        }
        if (shadowStudyEnabled) {
            Text("Light source", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(LightSourceType.entries.toList()) { type ->
                    val selected = type == shadowLightType
                    FilterChip(
                        selected = selected,
                        onClick = { onShadowLightType(type) },
                        label = { Text(type.displayLabel) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
            Text(
                shadowLightType.helperText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onShadowLayoutReset, modifier = Modifier.weight(1f)) {
                    Text("Reset layout")
                }
                OutlinedButton(
                    onClick = onShadowShelfToggle,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (shadowLayout.shelfEnabled) "Hide shelf quad" else "Show shelf quad")
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
        }

        // ── Sampled color (from tapping the image) ────────────────────────────
        sampledColor?.let { sc ->
            ImageSampleCard(sc = sc, kmResult = kmResult, isLoading = isLoading,
                onKmRequest = { onKmFromSampled(sc) })
        } ?: run {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("Tap the image above to sample a color",
                    Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ── Swatch tap result ─────────────────────────────────────────────────
        if (swatchArgb != null && sampledColor == null) {
            val r   = (swatchArgb shr 16) and 0xFF
            val g   = (swatchArgb shr  8) and 0xFF
            val b   =  swatchArgb         and 0xFF
            val hex = "#%02X%02X%02X".format(r, g, b)
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(Color(swatchArgb), RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)))
                    Column {
                        Text("Selected: $hex", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("RGB $r · $g · $b", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(0.8f))
                        if (isLoading) Text("Getting K-M mix…", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(0.6f))
                    }
                }
            }
            kmResult?.let { km -> KmResultCard(km = km, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))

        // ── Artist palette groups ─────────────────────────────────────────────
        Text("Artist Palettes — tap swatch for K-M paint mix",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))

        // Group selector chips (emoji + name)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ARTIST_PALETTE_GROUPS) { idx, group ->
                val selected = idx == activeGroupIdx
                Surface(
                    onClick  = { onGroupSelect(idx) },
                    color    = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape    = RoundedCornerShape(20.dp),
                    modifier = Modifier.border(
                        if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)),
                ) {
                    Text("${group.emoji} ${group.name}",
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color      = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                     else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // 4-column swatch grid for selected group
        val group   = ARTIST_PALETTE_GROUPS[activeGroupIdx]
        val columns = 4
        val rows    = (group.colors.size + columns - 1) / columns
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(columns) { col ->
                        val idx = row * columns + col
                        val argb = group.colors.getOrNull(idx)
                        if (argb != null) {
                            ArtistSwatch(argb = argb, isSelected = swatchArgb == argb,
                                onClick = { onSwatchTap(argb) }, modifier = Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))

        // ── Auto-palette extraction ───────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Auto-Extract Palette", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("K-means from your image · $paletteCount colors",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { if (paletteCount > 2) onPaletteCountChange(paletteCount - 1) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Remove, null, Modifier.size(16.dp)) }
                Text("$paletteCount", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                IconButton(onClick = { if (paletteCount < 24) onPaletteCountChange(paletteCount + 1) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) }
            }
        }
        Button(onClick = onGeneratePalette, enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            if (isLoading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (isLoading) "Extracting…" else "Generate Auto Palette")
        }

        if (autoPalette.isNotEmpty()) {
            Text("Extracted (tap for K-M mix):",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(4.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(autoPalette) { argb ->
                    ArtistSwatch(argb = argb, isSelected = swatchArgb == argb,
                        onClick = { onSwatchTap(argb) }, modifier = Modifier.size(52.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ArtistSwatch(argb: Int, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val r   = (argb shr 16) and 0xFF
    val g   = (argb shr  8) and 0xFF
    val b   =  argb         and 0xFF
    val hex = "#%02X%02X%02X".format(r, g, b)
    val lumText = if (0.299f * r + 0.587f * g + 0.114f * b > 128f) Color(0xFF222222) else Color(0xFFEEEEEE)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(argb))
            .border(
                if (isSelected) 3.dp else 1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(0.25f),
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Text(hex, style = MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.8),
            color = lumText.copy(0.85f), modifier = Modifier.padding(3.dp))
    }
}

@Composable
private fun ImageSampleCard(
    sc: ColorSampleResult,
    kmResult: KmResult?,
    isLoading: Boolean,
    onKmRequest: () -> Unit,
) {
    // sRGB 0-1 → 0-255 for display
    val r255 = (sc.r * 255).roundToInt().coerceIn(0, 255)
    val g255 = (sc.g * 255).roundToInt().coerceIn(0, 255)
    val b255 = (sc.b * 255).roundToInt().coerceIn(0, 255)
    val hex  = "#%02X%02X%02X".format(r255, g255, b255)
    val col  = Color(r255, g255, b255)

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(col, RoundedCornerShape(10.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(hex, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("RGB  $r255 · $g255 · $b255", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Oklab L=%.2f  a=%.3f  b=%.3f".format(sc.oklabL, sc.oklabA, sc.oklabB), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("HSL  %.0f°  %.0f%%  %.0f%%".format(sc.hslH * 360f, sc.hslS * 100f, sc.hslL * 100f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Kelvin ~${sc.kelvin.roundToInt()} K  ·  Lightness ${sc.lightnessPct.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(onClick = onKmRequest, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                if (isLoading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                Text(if (isLoading) "Mixing…" else "Get Paint Mix (K-M)")
            }
            kmResult?.let { KmResultCard(it) }
        }
    }
}

@Composable
private fun KmResultCard(km: KmResult, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Paint Mix — ${(km.similarityScore * 100).toInt()}% match",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            km.recipe.forEach { entry ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(entry.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text("${(entry.weight * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}
