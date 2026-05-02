/**
 * CanvasCalibrationSheet.kt
 * Reusable bottom-sheet for canvas size selection.
 *
 * New in this version:
 *  - Presets shown in expandable category sections
 *  - "Original Image Size" chip — computes physical size from image pixel dims at a user-chosen DPI
 *  - DPI slider shown when Original Image Size is selected
 *  - Custom dimension inputs remain available
 */
package com.artgrid.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasCalibrationSheet(
    canvasState: CanvasUiState,
    onSelectPreset: (CanvasPreset) -> Unit,
    onSetCustom: (Float, Float) -> Unit,
    onSelectOriginalImage: (dpi: Float) -> Unit = {},
    onConfirm: () -> Unit,
) {
    var customW         by remember { mutableStateOf(canvasState.customWidthCm.toString()) }
    var customH         by remember { mutableStateOf(canvasState.customHeightCm.toString()) }
    var showCustom      by remember { mutableStateOf(canvasState.isCustom) }
    var showOriginal    by remember { mutableStateOf(canvasState.isOriginalImageSize) }
    var dpiValue        by remember { mutableFloatStateOf(canvasState.printDpi) }
    // Track which category section is expanded
    val expandedCats    = remember { mutableStateMapOf<String, Boolean>().apply {
        PRESET_CATEGORIES.forEachIndexed { idx, c -> put(c.label, idx < 2) } // first two open
    } }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Canvas Size",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
        }
        item {
            Text("Select your physical canvas so measurements display in real cm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ── Original Image Size chip ──────────────────────────────────────────
        item {
            CanvasChip(
                label    = "Original Image",
                sub      = if (canvasState.imagePxW > 0) "${canvasState.imagePxW}×${canvasState.imagePxH} px" else "Load image first",
                selected = canvasState.isOriginalImageSize,
                onClick  = {
                    showCustom   = false
                    showOriginal = true
                    onSelectOriginalImage(dpiValue)
                },
            )
        }

        // DPI slider (only when Original Image selected)
        if (showOriginal && canvasState.imagePxW > 0) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Print resolution: ${dpiValue.toInt()} DPI",
                            style = MaterialTheme.typography.labelMedium)
                        Text(
                            "%.1f × %.1f cm".format(
                                canvasState.imagePxW / dpiValue * 2.54f,
                                canvasState.imagePxH / dpiValue * 2.54f,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value         = dpiValue,
                        onValueChange = {
                            dpiValue = it
                            onSelectOriginalImage(it)
                        },
                        valueRange    = 72f..600f,
                        steps         = 0,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    Text("Common: 72 dpi (screen) · 150 dpi (laser) · 300 dpi (photo print) · 600 dpi (fine art)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── Custom size ───────────────────────────────────────────────────────
        item {
            CanvasChip(
                label    = "Custom",
                sub      = if (canvasState.isCustom) "${canvasState.customWidthCm}×${canvasState.customHeightCm} cm" else "Enter your own",
                selected = canvasState.isCustom,
                onClick  = { showCustom = true; showOriginal = false },
            )
        }
        if (showCustom) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = customW, onValueChange = { customW = it },
                        label = { Text("Width (cm)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(
                        value = customH, onValueChange = { customH = it },
                        label = { Text("Height (cm)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        }

        // ── Preset categories ─────────────────────────────────────────────────
        PRESET_CATEGORIES.forEach { cat ->
            item {
                val expanded = expandedCats[cat.label] == true
                // Category header / toggle
                Surface(
                    onClick       = { expandedCats[cat.label] = !expanded },
                    shape         = MaterialTheme.shapes.small,
                    color         = MaterialTheme.colorScheme.surfaceVariant,
                    modifier      = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(cat.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (expandedCats[cat.label] == true) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cat.presets) { preset ->
                            val selected = !canvasState.isCustom
                                && !canvasState.isOriginalImageSize
                                && canvasState.selectedPreset == preset
                            PresetChip(preset = preset, selected = selected, onClick = {
                                showCustom   = false
                                showOriginal = false
                                onSelectPreset(preset)
                            })
                        }
                    }
                }
            }
        }

        // ── Mapping preview ───────────────────────────────────────────────────
        if (canvasState.imagePxW > 0) {
            item {
                val wCm = canvasState.canvasWidthCm
                val hCm = canvasState.canvasHeightCm
                if (wCm > 0f && hCm > 0f) {
                    Surface(
                        color  = MaterialTheme.colorScheme.secondaryContainer,
                        shape  = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Canvas mapping", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(
                                "Image ${canvasState.imagePxW}×${canvasState.imagePxH} px → %.1f×%.1f cm\n1 px = %.4f cm (X) · %.4f cm (Y)".format(
                                    wCm, hCm,
                                    wCm / canvasState.imagePxW,
                                    hCm / canvasState.imagePxH,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    when {
                        showCustom -> {
                            val w = customW.toFloatOrNull() ?: 21f
                            val h = customH.toFloatOrNull() ?: 29.7f
                            onSetCustom(w, h)
                        }
                        showOriginal -> onSelectOriginalImage(dpiValue)
                    }
                    onConfirm()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text("Confirm Canvas Mapping")
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun CanvasChip(label: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color    = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape    = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            ),
        onClick  = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sub,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PresetChip(preset: CanvasPreset, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick  = onClick,
        shape    = RoundedCornerShape(8.dp),
        color    = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.border(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            shape = RoundedCornerShape(8.dp),
        ),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(preset.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${preset.widthCm}×${preset.heightCm}cm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
