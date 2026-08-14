package com.adegard.pixelcam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adegard.pixelcam.CameraViewModel
import com.adegard.pixelcam.PhotoProcessor
import com.adegard.pixelcam.PhotographicStyle
import com.adegard.pixelcam.R

@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        viewModel.initialize(lifecycleOwner)
    }

    if (!hasPermission) {
        PermissionPrompt(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        return
    }

    CameraContent(viewModel)
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "PixelCam needs camera access",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) {
                Text("Allow camera access")
            }
        }
    }
}

@Composable
private fun CameraContent(viewModel: CameraViewModel) {
    val context = LocalContext.current

    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val style by viewModel.style.collectAsStateWithLifecycle()
    val lens by viewModel.lens.collectAsStateWithLifecycle()
    val flash by viewModel.flash.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCapture.collectAsStateWithLifecycle()
    val availableModes by viewModel.availableModes.collectAsStateWithLifecycle()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(lens, mode, previewView) {
        previewView?.let { viewModel.bindPreview(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            viewModel.toast(message)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { pv ->
                    pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = pv
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.6f))
                    )
                )
        ) {
            TopBar(
                showFlash = viewModel.isFlashSupported,
                flashOn = flash == ImageCapture.FLASH_MODE_ON,
                onToggleFlash = viewModel::toggleFlash,
                onFlipCamera = viewModel::flipCamera
            )

            Spacer(Modifier.weight(1f))

            StyleSelector(selected = style, onSelect = viewModel::setStyle)

            Spacer(Modifier.height(12.dp))

            ModeSelector(
                modes = CameraViewModel.CaptureMode.entries,
                selected = mode,
                availability = availableModes,
                onSelect = viewModel::setMode
            )

            Spacer(Modifier.height(16.dp))

            BottomControls(
                isBusy = isBusy,
                onCapture = viewModel::capture
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    pending?.let { capture ->
        ResultOverlay(
            capture = capture,
            viewModel = viewModel,
            onDismiss = viewModel::dismissPendingCapture
        )
    }
}

@Composable
private fun TopBar(
    showFlash: Boolean,
    flashOn: Boolean,
    onToggleFlash: () -> Unit,
    onFlipCamera: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "PixelCam",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showFlash) {
                IconButton(onClick = onToggleFlash) {
                    Image(
                        painter = painterResource(
                            if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
                        ),
                        contentDescription = "Flash",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            IconButton(onClick = onFlipCamera) {
                Image(
                    painter = painterResource(R.drawable.ic_flip_camera),
                    contentDescription = "Switch camera",
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun StyleSelector(
    selected: PhotographicStyle,
    onSelect: (PhotographicStyle) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(12.dp))
        PhotographicStyle.entries.forEach { style ->
            val isSelected = style == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onSelect(style) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(
                        if (isSelected) Color.White.copy(alpha = 0.28f)
                        else Color.Black.copy(alpha = 0.32f),
                        RoundedCornerShape(20.dp)
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 0.dp,
                        color = Color.White.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                Text(
                    style.displayName,
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun ModeSelector(
    modes: List<CameraViewModel.CaptureMode>,
    selected: CameraViewModel.CaptureMode,
    availability: Map<CameraViewModel.CaptureMode, Boolean>,
    onSelect: (CameraViewModel.CaptureMode) -> Unit
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(12.dp))
        modes.forEach { m ->
            val supported = availability[m] ?: (m.extensionMode == null)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = supported) { onSelect(m) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    m.label,
                    color = if (m == selected) Color.White else Color.White.copy(alpha = 0.55f),
                    fontSize = 15.sp,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(width = if (m == selected) 18.dp else 12.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(
                            if (m == selected) Color.White else Color.Transparent
                        )
                )
            }
        }
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun BottomControls(
    isBusy: Boolean,
    onCapture: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .clickable(enabled = !isBusy, onClick = onCapture)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (isBusy) 0.5f else 1f))
            )
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ResultOverlay(
    capture: CameraViewModel.PendingCapture,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var style by remember(capture) { mutableStateOf(capture.style) }
    val processed = remember(capture.raw, style) {
        PhotoProcessor.applyStyle(capture.raw, style)
    }

    Surface(
        color = Color.Black,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Retake",
                        tint = Color.White
                    )
                }
                Text(
                    "${capture.mode.label} · ${style.displayName}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
                IconButton(onClick = {
                    val uri = viewModel.save(processed, capture.mode, style)
                    if (uri != null) viewModel.toast("Saved to Photos")
                }) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Save",
                        tint = Color.White
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = processed.asImageBitmap(),
                    contentDescription = "Captured photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp)
                )
            }

            StyleSelector(selected = style, onSelect = { style = it })

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val uri = viewModel.save(processed, capture.mode, style)
                        if (uri != null) sharePhoto(context, uri)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A35))
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Share", color = Color.White)
                }
            }
        }
    }
}

private fun sharePhoto(context: android.content.Context, uri: Uri) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share photo"))
}
