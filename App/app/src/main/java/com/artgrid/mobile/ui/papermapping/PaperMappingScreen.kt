/**
 * PaperMappingScreen.kt
 * Responsibility : Paper-to-paper rescaling UI.
 *                  Shows source/destination paper pickers, cm grid overlay, and scaled
 *                  measurement table. All arithmetic is pure; no ML calls.
 * Pattern used   : Stateless composable + HiltViewModel
 * Dependencies   : PaperMappingViewModel, Coil
 */
package com.artgrid.mobile.ui.papermapping

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset

private val Charcoal  = Color(0xFF1C1C1E)
private val Cream     = Color(0xFFF5F0E8)
private val Sienna    = Color(0xFFC85C3A)
private val Sage      = Color(0xFF6B8F71)
private val Surface2  = Color(0xFF2C2C2E)
private val Surface3  = Color(0xFF3A3A3C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperMappingScreen(
    imageUri: Uri,
    onNavigateBack: () -> Unit,
    viewModel: PaperMappingViewModel = hiltViewModel(),
) {
    val state    by viewModel.uiState.collectAsStateWithLifecycle()
    val scaling  = viewModel.computeScaling()
    val scroll   = rememberScrollState()

    var newMeasInput by remember { mutableStateOf("") }
    var srcExpanded  by remember { mutableStateOf(false) }
    var dstExpanded  by remember { mutableStateOf(false) }

    LaunchedEffect(imageUri) { viewModel.setImageUri(imageUri) }

    Scaffold(
        containerColor = Charcoal,
        topBar = {
            TopAppBar(
                title = {
                    Text("Paper Mapping", color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Cream)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleOrientation() }) {
                        Icon(
                            if (state.isPortrait) Icons.Outlined.StayCurrentPortrait
                            else Icons.Outlined.StayCurrentLandscape,
                            contentDescription = "Toggle orientation",
                            tint = Cream,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111113)),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll),
        ) {
            // ── Preview with cm grid ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if (state.isPortrait) scaling.sourceWidthMm / scaling.sourceHeightMm
                        else scaling.sourceHeightMm / scaling.sourceWidthMm
                    )
                    .background(Color(0xFF0A0A0C)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model              = imageUri,
                    contentDescription = "Source reference",
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize(),
                )
                GridOverlay(
                    gridOpacity   = state.gridOpacity,
                    labelOpacity  = state.labelOpacity,
                    spacingCm     = state.gridSpacingCm,
                    widthMm       = scaling.sourceWidthMm,
                    heightMm      = scaling.sourceHeightMm,
                    modifier      = Modifier.fillMaxSize(),
                )
            }

            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // ── Paper selectors ───────────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaperDropdown(
                        label      = "Source",
                        selected   = state.sourcePaper,
                        expanded   = srcExpanded,
                        onExpand   = { srcExpanded = true },
                        onDismiss  = { srcExpanded = false },
                        onSelect   = { viewModel.setSourcePaper(it); srcExpanded = false },
                        modifier   = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "to",
                        tint     = Cream,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    PaperDropdown(
                        label      = "Destination",
                        selected   = state.destPaper,
                        expanded   = dstExpanded,
                        onExpand   = { dstExpanded = true },
                        onDismiss  = { dstExpanded = false },
                        onSelect   = { viewModel.setDestPaper(it); dstExpanded = false },
                        modifier   = Modifier.weight(1f),
                    )
                }

                // Scaling ratio cards
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScaleChip("Scale X", "×${String.format("%.3f", scaling.scaleX)}", modifier = Modifier.weight(1f))
                    ScaleChip("Scale Y", "×${String.format("%.3f", scaling.scaleY)}", modifier = Modifier.weight(1f))
                }

                // ── Opacity sliders ───────────────────────────────────────────
                SectionCard(title = "Grid Overlay") {
                    SliderRow("Line opacity", state.gridOpacity, viewModel::setGridOpacity)
                    SliderRow("Label opacity", state.labelOpacity, viewModel::setLabelOpacity)
                    SliderRow("Grid spacing (${state.gridSpacingCm} cm)", state.gridSpacingCm / 10f) {
                        viewModel.setGridSpacing(it * 10f)
                    }
                }

                // ── Measurement calculator ─────────────────────────────────────
                SectionCard(title = "Measurement Scaler") {
                    if (state.customMeasurements.isEmpty()) {
                        Text(
                            "Add source measurements (mm) to see scaled output.",
                            color = Color(0xFF8E8E93),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    } else {
                        state.customMeasurements.forEachIndexed { index, mm ->
                            val scaled = scaling.scaledMeasurements.getOrNull(index)
                            MeasurementRow(
                                source  = mm,
                                scaled  = scaled?.scaledMm ?: 0f,
                                onDelete = { viewModel.removeMeasurement(index) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value         = newMeasInput,
                            onValueChange = { newMeasInput = it },
                            label         = { Text("mm on source", color = Color(0xFF8E8E93)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors        = OutlinedTextFieldDefaults.colors(
                                focusedTextColor     = Cream,
                                unfocusedTextColor   = Cream,
                                focusedBorderColor   = Sienna,
                                unfocusedBorderColor = Surface3,
                            ),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        FilledTonalButton(
                            onClick = {
                                newMeasInput.toFloatOrNull()?.takeIf { it > 0f }?.let {
                                    viewModel.addMeasurement(it)
                                    newMeasInput = ""
                                }
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Sage),
                        ) {
                            Icon(Icons.Outlined.Add, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaperDropdown(
    label: String,
    selected: PaperSize,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (PaperSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { onExpand() }, modifier = modifier) {
        OutlinedTextField(
            value         = selected.displayName.split(" ").first(),
            onValueChange = {},
            readOnly      = true,
            label         = { Text(label, color = Color(0xFF8E8E93), fontSize = 11.sp) },
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors        = OutlinedTextFieldDefaults.colors(
                focusedTextColor     = Cream,
                unfocusedTextColor   = Cream,
                focusedBorderColor   = Sienna,
                unfocusedBorderColor = Surface2,
            ),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = Surface2) {
            PaperSize.entries.forEach { size ->
                DropdownMenuItem(
                    text = { Text(size.displayName, color = Cream, fontSize = 13.sp) },
                    onClick = { onSelect(size) },
                )
            }
        }
    }
}

@Composable
private fun ScaleChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Surface2, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp),
        ) {
            Text(label, color = Color(0xFF8E8E93), fontSize = 11.sp)
            Text(value, color = Sienna, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Surface2, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp))
            content()
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFFAEAEB2), fontSize = 12.sp, modifier = Modifier.width(140.dp))
        Slider(
            value          = value,
            onValueChange  = onValueChange,
            colors         = SliderDefaults.colors(thumbColor = Sienna, activeTrackColor = Sienna),
            modifier       = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MeasurementRow(source: Float, scaled: Float, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("${source} mm  →  ${String.format("%.1f", scaled)} mm  (${String.format("%.2f", scaled / 10f)} cm)",
            color = Cream, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Close, null, tint = Sienna, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun GridOverlay(
    gridOpacity: Float,
    labelOpacity: Float,
    spacingCm: Float,
    widthMm: Float,
    heightMm: Float,
    modifier: Modifier = Modifier,
) {
    val lineColor  = Color(0xFFFFFFFF).copy(alpha = gridOpacity.coerceIn(0f, 1f))
    val spacingMm  = spacingCm * 10f

    Canvas(modifier = modifier) {
        if (widthMm == 0f || heightMm == 0f) return@Canvas
        val pxPerMm = size.width / widthMm

        var xMm = 0f
        while (xMm <= widthMm) {
            val x = xMm * pxPerMm
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            xMm += spacingMm
        }
        var yMm = 0f
        while (yMm <= heightMm) {
            val y = yMm * pxPerMm
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            yMm += spacingMm
        }
    }
}

