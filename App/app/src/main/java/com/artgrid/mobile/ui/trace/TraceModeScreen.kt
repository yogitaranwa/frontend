/**
 * TraceModeScreen.kt
 * Responsibility : F-27-MVP — live camera underlay with reference image overlay.
 *                  CameraX provides the live preview; reference image is drawn on top
 *                  at configurable opacity. Pinch/pan/rotate gestures adjust the overlay.
 *                  No ARCore — pure CameraX + Skia/Canvas compositing.
 * Pattern used   : Stateless composable + HiltViewModel
 * Dependencies   : CameraX, TraceModeViewModel, Coil
 */
package com.artgrid.mobile.ui.trace

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

private val Charcoal   = Color(0xFF1C1C1E)
private val Cream      = Color(0xFFF5F0E8)
private val Sienna     = Color(0xFFC85C3A)
private val Sage       = Color(0xFF6B8F71)
private val Overlay    = Color(0xCC000000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceModeScreen(
    referenceUri: Uri?,
    onNavigateBack: () -> Unit,
    viewModel: TraceModeViewModel = hiltViewModel(),
) {
    val state          by viewModel.state.collectAsStateWithLifecycle()
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var controlsVisible by remember { mutableStateOf(true) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(referenceUri) {
        referenceUri?.let { viewModel.setReferenceImage(it) }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Camera preview layer ──────────────────────────────────────────────
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update   = { previewView ->
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                        )
                    }, ContextCompat.getMainExecutor(context))
                }
            )
        } else {
            // Permission denied state
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.NoPhotography, null, tint = Cream, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Camera permission required", color = Cream, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors  = ButtonDefaults.buttonColors(containerColor = Sienna),
                    ) { Text("Grant Permission") }
                }
            }
        }

        // ── Reference image overlay ───────────────────────────────────────────
        state.referenceUri?.let { uri ->
            AsyncImage(
                model              = uri,
                contentDescription = "Trace reference",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        translationX  = state.translateX,
                        translationY  = state.translateY,
                        scaleX        = state.scale * (if (state.isMirroredH) -1f else 1f),
                        scaleY        = state.scale,
                        rotationZ     = state.rotationDeg,
                        alpha         = state.overlayAlpha,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            viewModel.onPan(pan.x, pan.y)
                            viewModel.onScale(zoom)
                            viewModel.onRotate(rotation)
                        }
                    },
            )
        }

        // ── Top controls toggle ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { controlsVisible = !controlsVisible }
                },
        )

        // ── Top bar ───────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible,
            enter   = fadeIn() + slideInVertically(),
            exit    = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Cream)
                }
                Text(
                    "Trace Mode",
                    color    = Cream,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
                Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { viewModel.toggleMirror() }) {
                        Icon(Icons.Outlined.Flip, null, tint = if (state.isMirroredH) Sienna else Cream)
                    }
                    IconButton(onClick = { viewModel.toggleGrid() }) {
                        Icon(Icons.Outlined.GridOn, null, tint = if (state.gridEnabled) Sage else Cream)
                    }
                    IconButton(onClick = { viewModel.resetTransform() }) {
                        Icon(Icons.Outlined.CenterFocusWeak, null, tint = Cream)
                    }
                }
            }
        }

        // ── Bottom control panel ──────────────────────────────────────────────
        AnimatedVisibility(
            visible  = controlsVisible,
            enter    = fadeIn() + slideInVertically { it },
            exit     = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color    = Color(0xDD111113),
                shape    = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TraceSlider(
                        label    = "Overlay opacity  ${(state.overlayAlpha * 100).toInt()}%",
                        value    = state.overlayAlpha,
                        onChange = viewModel::setOverlayAlpha,
                        color    = Sienna,
                    )
                    if (state.gridEnabled) {
                        TraceSlider(
                            label    = "Grid opacity  ${(state.gridOpacity * 100).toInt()}%",
                            value    = state.gridOpacity,
                            onChange = viewModel::setGridOpacity,
                            color    = Sage,
                        )
                    }

                    // Quick action chips
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { alpha ->
                            FilterChip(
                                selected = kotlin.math.abs(state.overlayAlpha - alpha) < 0.05f,
                                onClick  = { viewModel.setOverlayAlpha(alpha) },
                                label    = { Text("${(alpha * 100).toInt()}%", fontSize = 11.sp) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Sienna,
                                    selectedLabelColor     = Color.White,
                                    containerColor         = Color(0xFF2C2C2E),
                                    labelColor             = Cream,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceSlider(label: String, value: Float, onChange: (Float) -> Unit, color: Color) {
    Column {
        Text(label, color = Cream, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        Slider(
            value         = value,
            onValueChange = onChange,
            colors        = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
        )
    }
}

