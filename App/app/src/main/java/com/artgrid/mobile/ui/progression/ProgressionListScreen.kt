/**
 * ProgressionListScreen.kt
 * Responsibility : F-32 — list of all artwork progressions with create/delete controls.
 * Pattern used   : Stateless composable + HiltViewModel
 * Dependencies   : ProgressionViewModel
 */
package com.artgrid.mobile.ui.progression

import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.domain.progression.model.Progression
import java.text.SimpleDateFormat
import java.util.*

private val Charcoal  = Color(0xFF1C1C1E)
private val Cream     = Color(0xFFF5F0E8)
private val Sienna    = Color(0xFFC85C3A)
private val Sage      = Color(0xFF6B8F71)
private val Surface2  = Color(0xFF2C2C2E)
private val Surface3  = Color(0xFF3A3A3C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionListScreen(
    onNavigateBack: () -> Unit,
    onOpenProgression: (Long) -> Unit,
    viewModel: ProgressionViewModel = hiltViewModel(),
) {
    val state        by viewModel.listState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { msg ->
            snackbarHost.showSnackbar(msg)
            viewModel.clearListSnackbar()
        }
    }

    Scaffold(
        containerColor = Charcoal,
        snackbarHost   = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text("Progression Comparator", color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
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
                onClick        = { viewModel.showCreateDialog() },
                containerColor = Sienna,
                contentColor   = Color.White,
                icon           = { Icon(Icons.Outlined.Add, null) },
                text           = { Text("New Progression") },
            )
        },
    ) { padding ->
        if (state.progressions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Layers, null, tint = Color(0xFF3A3A3C), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No progressions yet", color = Color(0xFF8E8E93), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Create one to compare sketch → ink → color stages", color = Color(0xFF636366), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding        = PaddingValues(16.dp),
                verticalArrangement   = Arrangement.spacedBy(12.dp),
                modifier              = Modifier.fillMaxSize().padding(padding),
            ) {
                items(state.progressions, key = { it.id }) { prog ->
                    ProgressionCard(
                        progression = prog,
                        onOpen      = { onOpenProgression(prog.id) },
                        onDelete    = { viewModel.deleteProgression(prog.id) },
                    )
                }
            }
        }
    }

    if (state.showCreateDialog) {
        CreateProgressionDialog(
            onConfirm = { title, desc -> viewModel.createProgression(title, desc) },
            onDismiss = { viewModel.dismissCreateDialog() },
        )
    }
}

@Composable
private fun ProgressionCard(
    progression: Progression,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color    = Surface2,
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Stage count badge
            Surface(
                color  = Sienna.copy(alpha = 0.15f),
                shape  = RoundedCornerShape(8.dp),
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            progression.stageCount.toString(),
                            color = Sienna, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                        Text("stages", color = Sienna.copy(alpha = 0.8f), fontSize = 9.sp)
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    progression.title,
                    color     = Cream,
                    fontSize  = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                )
                if (progression.description.isNotEmpty()) {
                    Text(
                        progression.description,
                        color    = Color(0xFF8E8E93),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Updated ${fmt.format(Date(progression.updatedAtMs))}",
                    color    = Color(0xFF636366),
                    fontSize = 11.sp,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, null, tint = Color(0xFF8E8E93))
                }
                DropdownMenu(
                    expanded        = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor  = Surface3,
                ) {
                    DropdownMenuItem(
                        text    = { Text("Open", color = Cream) },
                        onClick = { menuExpanded = false; onOpen() },
                        leadingIcon = { Icon(Icons.Outlined.OpenInFull, null, tint = Cream) },
                    )
                    DropdownMenuItem(
                        text    = { Text("Delete", color = Sienna) },
                        onClick = { menuExpanded = false; onDelete() },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Sienna) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateProgressionDialog(
    onConfirm: (title: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var desc  by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Surface2,
        title            = { Text("New Progression", color = Cream, fontWeight = FontWeight.SemiBold) },
        text             = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = title,
                    onValueChange = { title = it },
                    label         = { Text("Title *", color = Color(0xFF8E8E93)) },
                    placeholder   = { Text("e.g. Mountain Landscape — April 2026", color = Color(0xFF636366), fontSize = 12.sp) },
                    colors        = dialogFieldColors(),
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                )
                OutlinedTextField(
                    value         = desc,
                    onValueChange = { desc = it },
                    label         = { Text("Description (optional)", color = Color(0xFF8E8E93)) },
                    colors        = dialogFieldColors(),
                    modifier      = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onConfirm(title, desc) },
                enabled = title.isNotBlank(),
            ) { Text("Create", color = Sage, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF8E8E93)) }
        },
    )
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor     = Cream,
    unfocusedTextColor   = Cream,
    focusedBorderColor   = Sienna,
    unfocusedBorderColor = Surface3,
)

