/**
 * ReferenceHistoryScreen.kt
 * Responsibility : Reference history — browse, tag-filter, and manage saved reference images.
 *                  Users can save new images from gallery, edit labels, and delete entries.
 * Pattern used   : Stateless composable + HiltViewModel
 * Dependencies   : ReferenceHistoryViewModel, Coil
 */
package com.artgrid.mobile.ui.history

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.artgrid.mobile.domain.reference.model.ReferenceAsset
import java.text.SimpleDateFormat
import java.util.*

private val Charcoal  = Color(0xFF1C1C1E)
private val Cream     = Color(0xFFF5F0E8)
private val Sienna    = Color(0xFFC85C3A)
private val Surface2  = Color(0xFF2C2C2E)
private val Surface3  = Color(0xFF3A3A3C)
private val Sage      = Color(0xFF6B8F71)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceHistoryScreen(
    onNavigateBack: () -> Unit,
    onOpenReference: (Uri) -> Unit,
    viewModel: ReferenceHistoryViewModel = hiltViewModel(),
) {
    val state          by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost   = remember { SnackbarHostState() }
    var showSaveDialog by remember { mutableStateOf(false) }
    var pendingUri     by remember { mutableStateOf<Uri?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { pendingUri = it; showSaveDialog = true } }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { msg ->
            snackbarHost.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        containerColor = Charcoal,
        snackbarHost   = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text("Reference History", color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Cream)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111113)),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick           = { imagePicker.launch("image/*") },
                containerColor    = Sienna,
                contentColor      = Color.White,
                icon              = { Icon(Icons.Outlined.Add, null) },
                text              = { Text("Add Reference") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Tag filter row ────────────────────────────────────────────────
            if (state.tags.isNotEmpty()) {
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(1),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Color(0xFF111113)),
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedTag == null,
                            onClick  = { viewModel.filterByTag(null) },
                            label    = { Text("All", fontSize = 12.sp) },
                            colors   = tagChipColors(),
                        )
                    }
                    items(state.tags) { tag ->
                        FilterChip(
                            selected = state.selectedTag == tag,
                            onClick  = { viewModel.filterByTag(if (state.selectedTag == tag) null else tag) },
                            label    = { Text(tag, fontSize = 12.sp) },
                            colors   = tagChipColors(),
                        )
                    }
                }
            }

            if (state.assets.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.PhotoLibrary, null, tint = Color(0xFF3A3A3C), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("No references saved yet", color = Color(0xFF8E8E93), fontSize = 14.sp)
                        Text("Tap + to add your first reference image", color = Color(0xFF636366), fontSize = 12.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns              = GridCells.Fixed(2),
                    contentPadding       = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement  = Arrangement.spacedBy(12.dp),
                    modifier             = Modifier.fillMaxSize(),
                ) {
                    items(state.assets, key = { it.id }) { asset ->
                        ReferenceCard(
                            asset    = asset,
                            onOpen   = { onOpenReference(Uri.parse("file://${asset.localPath}")) },
                            onEdit   = { viewModel.startEdit(asset) },
                            onDelete = { viewModel.deleteAsset(asset.id) },
                        )
                    }
                }
            }
        }
    }

    // ── Save dialog ───────────────────────────────────────────────────────────
    if (showSaveDialog && pendingUri != null) {
        SaveReferenceDialog(
            onConfirm = { label, tag, note ->
                viewModel.saveImage(pendingUri!!, label, tag, note)
                showSaveDialog = false; pendingUri = null
            },
            onDismiss = { showSaveDialog = false; pendingUri = null },
        )
    }

    // ── Edit dialog ───────────────────────────────────────────────────────────
    state.editingAsset?.let { asset ->
        EditReferenceDialog(
            asset     = asset,
            onConfirm = { label, note, tag -> viewModel.commitEdit(label, note, tag) },
            onDismiss = { viewModel.cancelEdit() },
        )
    }
}

@Composable
private fun ReferenceCard(
    asset: ReferenceAsset,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("dd MMM yy", Locale.getDefault()) }
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color  = Surface2,
        shape  = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
    ) {
        Column {
            Box {
                AsyncImage(
                    model              = "file://${asset.localPath}",
                    contentDescription = asset.label,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                )
                // Gradient overlay at bottom of thumbnail
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
                        )
                )
                // Tag chip overlay
                if (asset.tag.isNotEmpty()) {
                    Surface(
                        color  = Sienna.copy(alpha = 0.85f),
                        shape  = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .padding(6.dp)
                            .align(Alignment.TopStart),
                    ) {
                        Text(
                            asset.tag,
                            color    = Color.White,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
                // Menu button
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.MoreVert, null, tint = Cream, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded        = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor  = Surface3,
                    ) {
                        DropdownMenuItem(
                            text    = { Text("Edit", color = Cream) },
                            onClick = { menuExpanded = false; onEdit() },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = Cream) },
                        )
                        DropdownMenuItem(
                            text    = { Text("Delete", color = Sienna) },
                            onClick = { menuExpanded = false; onDelete() },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Sienna) },
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    asset.label,
                    color     = Cream,
                    fontSize  = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                )
                Text(
                    fmt.format(Date(asset.createdAtMs)),
                    color    = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun SaveReferenceDialog(
    onConfirm: (label: String, tag: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var tag   by remember { mutableStateOf("") }
    var note  by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title            = { Text("Save Reference", color = Cream, fontWeight = FontWeight.SemiBold) },
        text             = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogField("Label *", label, { label = it }, "e.g. Outer lines - pass 1")
                DialogField("Tag (optional)", tag, { tag = it }, "e.g. Mountain Project")
                DialogField("Note (optional)", note, { note = it }, "Any freeform note")
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { if (label.isNotBlank()) onConfirm(label.trim(), tag.trim(), note.trim()) },
                enabled  = label.isNotBlank(),
            ) { Text("Save", color = Sage, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF8E8E93)) }
        },
    )
}

@Composable
private fun EditReferenceDialog(
    asset: ReferenceAsset,
    onConfirm: (label: String, note: String, tag: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(asset.id) { mutableStateOf(asset.label) }
    var note  by remember(asset.id) { mutableStateOf(asset.note) }
    var tag   by remember(asset.id) { mutableStateOf(asset.tag) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title            = { Text("Edit Reference", color = Cream, fontWeight = FontWeight.SemiBold) },
        text             = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogField("Label", label, { label = it }, "")
                DialogField("Tag", tag, { tag = it }, "")
                DialogField("Note", note, { note = it }, "")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.trim(), note.trim(), tag.trim()) }) {
                Text("Update", color = Sage, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF8E8E93)) }
        },
    )
}

@Composable
private fun DialogField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, color = Color(0xFF8E8E93), fontSize = 12.sp) },
        placeholder   = { Text(placeholder, color = Color(0xFF636366), fontSize = 12.sp) },
        colors        = OutlinedTextFieldDefaults.colors(
            focusedTextColor     = Cream,
            unfocusedTextColor   = Cream,
            focusedBorderColor   = Sienna,
            unfocusedBorderColor = Surface3,
        ),
        modifier  = Modifier.fillMaxWidth(),
        singleLine = label != "Note (optional)",
    )
}

@Composable
private fun tagChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Sienna,
    selectedLabelColor     = Color.White,
    containerColor         = Color(0xFF2C2C2E),
    labelColor             = Cream,
)

