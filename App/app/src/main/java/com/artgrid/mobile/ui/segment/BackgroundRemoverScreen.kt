/**
 * BackgroundRemoverScreen.kt
 * Responsibility : F-22 Background Removal — displays the segmentation result in three view
 *                  modes: Original, Isolated Subject (masked), Alpha Mask.
 *                  Allows exporting both the isolated subject and the alpha mask as PNG.
 *                  This is a DISTINCT screen from FaceStudioScreen and ObjectLocatorScreen.
 * API calls      : none (consumes SegmentResult mask bytes passed via nav)
 * Injects        : NativePipelineViewModel
 */
package com.artgrid.mobile.ui.segment

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artgrid.mobile.ui.home.NativePipelineViewModel

enum class SegmentViewMode(val label: String) {
    ORIGINAL("Original"),
    ISOLATED("Isolated Subject"),
    MASK("Alpha Mask"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundRemoverScreen(
    imageUri: Uri,
    maskPngBytes: ByteArray,
    onNavigateBack: () -> Unit,
    nativeVm: NativePipelineViewModel = hiltViewModel(),
) {
    val uiState = nativeVm.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    LaunchedEffect(imageUri) { nativeVm.loadImage(imageUri) }

    var viewMode by remember { mutableStateOf(SegmentViewMode.ISOLATED) }

    // Decode mask PNG bytes to bitmap
    var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(maskPngBytes) {
        maskBitmap = runCatching {
            BitmapFactory.decodeByteArray(maskPngBytes, 0, maskPngBytes.size)
        }.getOrNull()
    }

    // Composite: apply mask to original bitmap
    var isolatedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uiState.sourceBitmap, maskBitmap) {
        val src  = uiState.sourceBitmap
        val mask = maskBitmap
        isolatedBitmap = if (src != null && mask != null) compositeWithMask(src, mask) else null
    }

    // α-mask rendered as greyscale
    var alphaMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(maskBitmap) {
        alphaMaskBitmap = maskBitmap?.let { toGreyscaleAlpha(it) }
    }

    val displayBitmap: Bitmap? = when (viewMode) {
        SegmentViewMode.ORIGINAL  -> uiState.sourceBitmap
        SegmentViewMode.ISOLATED  -> isolatedBitmap
        SegmentViewMode.MASK      -> alphaMaskBitmap
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Background Remover", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    // Export isolated
                    isolatedBitmap?.let { bmp ->
                        IconButton(onClick = { exportBitmapToPng(context, bmp, "bg_removed") }) {
                            Icon(Icons.Outlined.FileDownload, "Export Isolated", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // View mode tabs
            TabRow(selectedTabIndex = viewMode.ordinal) {
                SegmentViewMode.entries.forEach { mode ->
                    Tab(
                        selected  = viewMode == mode,
                        onClick   = { viewMode = mode },
                        text      = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            // Image area (checkerboard bg for isolated view)
            Box(
                modifier          = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        if (viewMode == SegmentViewMode.ISOLATED) Color(0xFF2D2D2D)
                        else Color.Black
                    ),
                contentAlignment  = Alignment.Center,
            ) {
                when {
                    uiState.isProcessing && isolatedBitmap == null ->
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    displayBitmap != null ->
                        Image(
                            bitmap             = displayBitmap!!.asImageBitmap(),
                            contentDescription = viewMode.label,
                            contentScale       = ContentScale.Fit,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    else ->
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            // Info + actions card
            Surface(
                tonalElevation = 4.dp,
                modifier       = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Segmentation Result", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text  = "Subject segmented via U²-Net salient object detection. " +
                                "White areas = foreground, black = background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Export isolated
                        FilledTonalButton(
                            onClick  = { isolatedBitmap?.let { exportBitmapToPng(context, it, "bg_removed") } },
                            enabled  = isolatedBitmap != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.PersonRemove, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save Subject")
                        }
                        // Export original
                        FilledTonalButton(
                            onClick  = { uiState.sourceBitmap?.let { exportBitmapToPng(context, it, "original") } },
                            enabled  = uiState.sourceBitmap != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Image, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save Original")
                        }
                    }
                    // Export mask
                    OutlinedButton(
                        onClick  = { alphaMaskBitmap?.let { exportBitmapToPng(context, it, "alpha_mask") } },
                        enabled  = alphaMaskBitmap != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Layers, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Alpha Mask")
                    }
                }
            }
        }
    }
}

// ── Bitmap helpers ────────────────────────────────────────────────────────────

/** Applies a greyscale single-channel mask to the source ARGB bitmap. */
private fun compositeWithMask(src: Bitmap, mask: Bitmap): Bitmap {
    val scaled = if (mask.width != src.width || mask.height != src.height)
        Bitmap.createScaledBitmap(mask, src.width, src.height, true)
    else mask

    val result  = src.copy(Bitmap.Config.ARGB_8888, true)
    val maskArr = IntArray(scaled.width * scaled.height)
    scaled.getPixels(maskArr, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    val srcArr  = IntArray(result.width * result.height)
    result.getPixels(srcArr, 0, result.width, 0, 0, result.width, result.height)

    for (i in srcArr.indices) {
        val alpha = AndroidColor.red(maskArr[i])  // mask is greyscale — red channel = alpha value
        val origPixel = srcArr[i]
        srcArr[i] = AndroidColor.argb(
            alpha,
            AndroidColor.red(origPixel),
            AndroidColor.green(origPixel),
            AndroidColor.blue(origPixel),
        )
    }
    result.setPixels(srcArr, 0, result.width, 0, 0, result.width, result.height)
    return result
}

/** Converts segmentation mask (greyscale) to a visible greyscale bitmap. */
private fun toGreyscaleAlpha(mask: Bitmap): Bitmap =
    mask.copy(Bitmap.Config.ARGB_8888, false)

private fun exportBitmapToPng(context: Context, bitmap: Bitmap, prefix: String) {
    val name = "${prefix}_${System.currentTimeMillis()}.png"
    val cv   = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArtGrid")
    }
    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        Toast.makeText(context, "Saved to Pictures/ArtGrid", Toast.LENGTH_SHORT).show()
    } ?: Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
}
