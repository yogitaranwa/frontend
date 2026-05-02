/**
 * ProgressionDetailScreen.kt
 * Responsibility : F-32 — side-by-side stage comparator with grid overlay toggle,
 *                  stage management (add/delete), and color-picker integration.
 * Pattern used   : Stateless composable + shared ProgressionViewModel
 * Dependencies   : ProgressionViewModel, Coil
 */
package com.artgrid.mobile.ui.progression

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.artgrid.mobile.domain.progression.model.ProgressionStage

private val Charcoal  = Color(0xFF1C1C1E)
private val Cream     = Color(0xFFF5F0E8)
private val Sienna    = Color(0xFFC85C3A)
private val Sage      = Color(0xFF6B8F71)
private val Surface2  = Color(0xFF2C2C2E)
private val Surface3  = Color(0xFF3A3A3C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionDetailScreen(
    progressionId: Long,
    onNavigateBack: () -> Unit,
    onOpenColorPicker: (Uri) -> Unit,
    viewModel: ProgressionViewModel = hiltViewModel(),
) {
    val state        by viewModel.detailState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.promptAddStage(it) } }

    LaunchedEffect(progressionId) { viewModel.loadProgression(progressionId) }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { msg ->
            snackbarHost.showSnackbar(msg)
            viewModel.clearDetailSnackbar()
        }
    }

    Scaffold(
        containerColor = Charcoal,
        snackbarHost   = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.progression?.title ?: "Progression",
                        color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Cream)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleGridOverlay() }) {
                        Icon(
                            Icons.Outlined.GridOn, null,
                            tint = if (state.gridOverlayEnabled) Sage else Cream,
                        )
                    }
                    IconButton(onClick = { imagePicker.launch("image/*") }) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, tint = Cream)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111113)),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            if (state.stages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.PhotoLibrary, null, tint = Color(0xFF3A3A3C), modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No stages yet", color = Color(0xFF8E8E93), fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { imagePicker.launch("image/*") },
                            colors  = ButtonDefaults.filledTonalButtonColors(containerColor = Sienna),
                        ) {
                            Icon(Icons.Outlined.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add first stage", color = Color.White)
                        }
                    }
                }
            } else {
                // ── Side-by-side comparator ───────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val stageA = state.stages.getOrNull(state.compareIndexA)
                    val stageB = state.stages.getOrNull(state.compareIndexB)

                    StagePanel(
                        stage            = stageA,
                        label            = "A",
                        gridEnabled      = state.gridOverlayEnabled,
                        gridOpacity      = state.gridOpacity,
                        onColorPicker    = { stageA?.let { onOpenColorPicker(Uri.parse("file://${it.localPath}")) } },
                        onDelete         = { stageA?.let { viewModel.deleteStage(it.id) } },
                        modifier         = Modifier.weight(1f).fillMaxHeight(),
                    )

                    HorizontalDivider(
                        color = Color(0xFF3A3A3C),
                        modifier = Modifier.fillMaxHeight().width(1.dp),
                    )

                    StagePanel(
                        stage            = stageB,
                        label            = "B",
                        gridEnabled      = state.gridOverlayEnabled,
                        gridOpacity      = state.gridOpacity,
                        onColorPicker    = { stageB?.let { onOpenColorPicker(Uri.parse("file://${it.localPath}")) } },
                        onDelete         = { stageB?.let { viewModel.deleteStage(it.id) } },
                        modifier         = Modifier.weight(1f).fillMaxHeight(),
                    )
                }

                // Grid opacity slider (shown when grid is on)
                if (state.gridOverlayEnabled) {
                    Surface(color = Color(0xFF111113), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Grid opacity", color = Color(0xFFAEAEB2), fontSize = 12.sp, modifier = Modifier.width(100.dp))
                            Slider(
                                value         = state.gridOpacity,
                                onValueChange = { viewModel.setGridOpacity(it) },
                                colors        = SliderDefaults.colors(thumbColor = Sage, activeTrackColor = Sage),
                                modifier      = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // ── Stage selector strip ──────────────────────────────────────
                Surface(color = Color(0xFF111113), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${state.stages.size} stage${if (state.stages.size != 1) "s" else ""}",
                                color = Color(0xFF8E8E93), fontSize = 12.sp)
                            Text("Tap to select A / B", color = Color(0xFF636366), fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(state.stages) { index, stage ->
                                StageThumbnail(
                                    stage        = stage,
                                    isSelectedA  = index == state.compareIndexA,
                                    isSelectedB  = index == state.compareIndexB,
                                    onSelectA    = { viewModel.setCompareA(index) },
                                    onSelectB    = { viewModel.setCompareB(index) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Add stage dialog ──────────────────────────────────────────────────────
    if (state.showAddStageDialog) {
        AddStageDialog(
            onConfirm = { label, note -> viewModel.confirmAddStage(label, note) },
            onDismiss = { viewModel.dismissAddStage() },
        )
    }
}

@Composable
private fun StagePanel(
    stage: ProgressionStage?,
    label: String,
    gridEnabled: Boolean,
    gridOpacity: Float,
    onColorPicker: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color(0xFF0A0A0C))) {
        if (stage != null) {
            AsyncImage(
                model              = "file://${stage.localPath}",
                contentDescription = stage.label,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier.fillMaxSize(),
            )
            if (gridEnabled) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val lineColor = Color.White.copy(alpha = gridOpacity)
                    val step = size.width / 10f
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1f)
                        x += step
                    }
                    var y = 0f
                    while (y <= size.height) {
                        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), 1f)
                        y += step
                    }
                }
            }
            // Panel label badge
            Surface(
                color  = Color(0xCC000000),
                shape  = RoundedCornerShape(bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    "Panel $label — ${stage.label}",
                    color    = Cream,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            // Action buttons overlay
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SmallOverlayButton(Icons.Outlined.Palette, "Color picker", onColorPicker)
                SmallOverlayButton(Icons.Outlined.Delete, "Delete stage", onDelete, tint = Sienna)
            }
        } else {
            Text(
                "Panel $label\n(select a stage below)",
                color    = Color(0xFF636366),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun SmallOverlayButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Cream,
) {
    Surface(
        color  = Color(0xAA111113),
        shape  = RoundedCornerShape(6.dp),
        modifier = Modifier.size(28.dp),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StageThumbnail(
    stage: ProgressionStage,
    isSelectedA: Boolean,
    isSelectedB: Boolean,
    onSelectA: () -> Unit,
    onSelectB: () -> Unit,
) {
    val borderColor = when {
        isSelectedA && isSelectedB -> Sienna
        isSelectedA -> Sienna
        isSelectedB -> Sage
        else -> Color.Transparent
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick      = { if (!isSelectedA) onSelectA() else onSelectB() },
                    onLongClick  = { onSelectB() },
                ),
        ) {
            AsyncImage(
                model              = "file://${stage.localPath}",
                contentDescription = stage.label,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
            // A/B badges
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isSelectedA) StageBadge("A", Sienna)
                if (isSelectedB) StageBadge("B", Sage)
            }
        }
        Text(
            stage.label,
            color    = Color(0xFF8E8E93),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(64.dp),
        )
    }
}

@Composable
private fun StageBadge(label: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(3.dp)) {
        Text(label, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
    }
}

@Composable
private fun AddStageDialog(onConfirm: (label: String, note: String) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var note  by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title            = { Text("Add Stage", color = Cream, fontWeight = FontWeight.SemiBold) },
        text             = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = label,
                    onValueChange = { label = it },
                    label         = { Text("Stage label *", color = Color(0xFF8E8E93)) },
                    placeholder   = { Text("e.g. Sketch / Inking / Base color", color = Color(0xFF636366), fontSize = 12.sp) },
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedTextColor     = Cream, unfocusedTextColor = Cream,
                        focusedBorderColor   = Sienna, unfocusedBorderColor = Surface3,
                    ),
                    modifier   = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value         = note,
                    onValueChange = { note = it },
                    label         = { Text("Note (optional)", color = Color(0xFF8E8E93)) },
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedTextColor     = Cream, unfocusedTextColor = Cream,
                        focusedBorderColor   = Sienna, unfocusedBorderColor = Surface3,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (label.isNotBlank()) onConfirm(label.trim(), note.trim()) },
                enabled = label.isNotBlank(),
            ) { Text("Add Stage", color = Sage, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF8E8E93)) }
        },
    )
}

