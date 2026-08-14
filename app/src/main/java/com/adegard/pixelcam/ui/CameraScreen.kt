package com.adegard.pixelcam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adegard.pixelcam.CameraViewModel
import com.adegard.pixelcam.PhotographicStyle
import com.adegard.pixelcam.R
import kotlin.math.roundToInt

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
    val zoomState by viewModel.zoomState.collectAsStateWithLifecycle()
    val lastPhotoUri by viewModel.lastPhotoUri.collectAsStateWithLifecycle()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(lens, mode, previewView) {
        previewView?.let { viewModel.bindPreview(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            viewModel.toast(message)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    viewModel.zoomBy(zoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    viewModel.setZoom(1f)
                })
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { pv ->
                    pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = pv
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        val filterActive = mode == CameraViewModel.CaptureMode.PHOTO &&
            style != PhotographicStyle.STANDARD
        FilterPreviewOverlay(viewModel = viewModel, active = filterActive)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.45f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f)
                        )
                    )
                )
        ) {
            TopBar(
                showFlash = viewModel.isFlashSupported,
                flashOn = flash == ImageCapture.FLASH_MODE_ON,
                onToggleFlash = viewModel::toggleFlash,
                onFlipCamera = viewModel::flipCamera
            )

            val zoomRatio = zoomState?.zoomRatio ?: 1f
            if (zoomRatio > 1.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        String.format(java.util.Locale.US, "%.1fx", zoomRatio),
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            StyleSelector(selected = style, onSelect = viewModel::setStyle)

            Spacer(Modifier.height(12.dp))

            ModeSelector(
                modes = CameraViewModel.CaptureMode.entries,
                selected = mode,
                onSelect = viewModel::setMode
            )

            Spacer(Modifier.height(16.dp))

            BottomControls(
                isBusy = isBusy,
                thumbnailUri = lastPhotoUri,
                onCapture = viewModel::capture,
                onOpenThumbnail = { uri -> openPhoto(context, uri) }
            )

            Spacer(Modifier.height(16.dp))
        }
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(m) }
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
    thumbnailUri: Uri?,
    onCapture: () -> Unit,
    onOpenThumbnail: (Uri) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            LastPhotoThumbnail(uri = thumbnailUri, onClick = onOpenThumbnail)
        }

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
private fun LastPhotoThumbnail(uri: Uri?, onClick: (Uri) -> Unit) {
    if (uri == null) {
        Spacer(Modifier.size(52.dp))
        return
    }
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, uri) {
        value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(128, 128), null)
            }.getOrNull()
        } else {
            null
        }
    }
    val bitmap = thumbnail
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Last photo",
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                .clickable { onClick(uri) }
        )
    } else {
        Spacer(Modifier.size(52.dp))
    }
}

@Composable
private fun FilterPreviewOverlay(viewModel: CameraViewModel, active: Boolean) {
    if (!active) return
    val bitmap by viewModel.filterBitmap.collectAsStateWithLifecycle()
    val tick by viewModel.frameTick.collectAsStateWithLifecycle()
    val bmp = bitmap ?: return
    Canvas(modifier = Modifier.fillMaxSize()) {
        tick
        val imgW = bmp.width.toFloat()
        val imgH = bmp.height.toFloat()
        val scale = maxOf(size.width / imgW, size.height / imgH)
        val dstW = imgW * scale
        val dstH = imgH * scale
        val left = (size.width - dstW) / 2f
        val top = (size.height - dstH) / 2f
        drawImage(
            image = bmp.asImageBitmap(),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt())
        )
    }
}

private fun openPhoto(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}
