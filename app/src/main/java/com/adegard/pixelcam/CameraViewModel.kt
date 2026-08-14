package com.adegard.pixelcam

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ZoomState
import androidx.camera.extensions.ExtensionMode
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
    }

    enum class CaptureMode(val label: String, val extensionMode: Int?) {
        PHOTO("Photo", null),
        HDR("HDR", ExtensionMode.HDR),
        NIGHT("Night", ExtensionMode.NIGHT),
        PORTRAIT("Portrait", ExtensionMode.BOKEH)
    }

    private val _mode = MutableStateFlow(CaptureMode.PHOTO)
    val mode: StateFlow<CaptureMode> = _mode.asStateFlow()

    private val _style = MutableStateFlow(PhotographicStyle.STANDARD)
    val style: StateFlow<PhotographicStyle> = _style.asStateFlow()

    private val _lens = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lens: StateFlow<Int> = _lens.asStateFlow()

    private val _flash = MutableStateFlow(ImageCapture.FLASH_MODE_OFF)
    val flash: StateFlow<Int> = _flash.asStateFlow()

    private val _availableModes = MutableStateFlow<Map<CaptureMode, Boolean>>(emptyMap())
    val availableModes: StateFlow<Map<CaptureMode, Boolean>> = _availableModes.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _lastPhotoUri = MutableStateFlow<Uri?>(null)
    val lastPhotoUri: StateFlow<Uri?> = _lastPhotoUri.asStateFlow()

    /** Reused destination bitmap for the live filtered preview. */
    private val _filterBitmap = MutableStateFlow<Bitmap?>(null)
    val filterBitmap: StateFlow<Bitmap?> = _filterBitmap.asStateFlow()

    private val _frameTick = MutableStateFlow(0L)
    val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    private val _zoomState = MutableStateFlow<ZoomState?>(null)
    val zoomState: StateFlow<ZoomState?> = _zoomState.asStateFlow()

    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    private var controller: CameraController? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var lastZoomLiveData: androidx.lifecycle.LiveData<ZoomState>? = null

    val isFlashSupported: Boolean
        get() = _mode.value == CaptureMode.PHOTO

    fun initialize(lifecycleOwner: LifecycleOwner) {
        if (controller != null) return
        val c = CameraController(getApplication<Application>(), lifecycleOwner)
        controller = c
        viewModelScope.launch {
            runCatching { c.initialize() }
                .onFailure { emitEvent("Camera init failed: ${it.message}") }
            refreshAvailability()
        }
    }

    private suspend fun refreshAvailability() {
        val c = controller ?: return
        val back = mutableMapOf<CaptureMode, Boolean>()
        for (mode in CaptureMode.entries) {
            if (mode.extensionMode == null) {
                back[mode] = true
            } else {
                back[mode] = c.isExtensionAvailable(CameraSelector.LENS_FACING_BACK, mode)
            }
        }
        _availableModes.value = back
        Log.d(TAG, "Mode availability: $back")
    }

    fun bindPreview(view: PreviewView) {
        viewModelScope.launch {
            runCatching { controller?.bind(view, _lens.value, _mode.value) }
                .onFailure { emitEvent("Camera error: ${it.message}") }
                .onSuccess {
                    observeZoom(it)
                    controller?.setFrameListener { bitmap, rotation ->
                        renderFilterFrame(bitmap, rotation)
                    }
                }
        }
    }

    /**
     * Applies the selected photographic style to a preview frame and publishes it
     * for the live-filter overlay. Frames are skipped for the default style so the
     * native high-resolution preview stays untouched when no filter is active.
     */
    private fun renderFilterFrame(source: Bitmap, rotationDegrees: Int) {
        try {
            val style = _style.value
            if (style == PhotographicStyle.STANDARD || _mode.value != CaptureMode.PHOTO) {
                source.recycle()
                return
            }
            val swapped = rotationDegrees == 90 || rotationDegrees == 270
            val destW = if (swapped) source.height else source.width
            val destH = if (swapped) source.width else source.height
            val current = _filterBitmap.value
            val target = if (current != null && current.width == destW && current.height == destH) {
                current
            } else {
                Bitmap.createBitmap(destW, destH, Bitmap.Config.ARGB_8888)
            }
            val canvas = Canvas(target)
            canvas.save()
            canvas.translate(destW / 2f, destH / 2f)
            canvas.rotate(rotationDegrees.toFloat())
            canvas.drawBitmap(
                source,
                -source.width / 2f,
                -source.height / 2f,
                Paint().apply {
                    colorFilter = ColorMatrixColorFilter(style.colorMatrix())
                    isFilterBitmap = true
                }
            )
            canvas.restore()
            _filterBitmap.value = target
            _frameTick.value += 1
            source.recycle()
        } catch (e: Exception) {
            runCatching { source.recycle() }
            Log.w(TAG, "renderFilterFrame failed", e)
        }
    }

    private fun observeZoom(camera: androidx.camera.core.Camera?) {
        zoomObserver?.let { old ->
            lastZoomLiveData?.removeObserver(old)
        }
        lastZoomLiveData = camera?.cameraInfo?.zoomState
        val liveData = lastZoomLiveData
        if (liveData != null) {
            val observer = Observer<ZoomState> { state -> _zoomState.value = state }
            zoomObserver = observer
            liveData.observeForever(observer)
        } else {
            zoomObserver = null
            _zoomState.value = null
        }
    }

    /** Applies a zoom scale factor (e.g. from a pinch gesture) clamped to the camera range. */
    fun zoomBy(scale: Float) {
        val state = _zoomState.value ?: return
        setZoom(state.zoomRatio * scale)
    }

    /** Sets an absolute zoom ratio, clamped to the camera range. */
    fun setZoom(ratio: Float) {
        val state = _zoomState.value ?: return
        val target = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        viewModelScope.launch {
            runCatching {
                controller?.currentCamera?.cameraControl?.setZoomRatio(target)
            }.onFailure { Log.w(TAG, "setZoomRatio failed", it) }
        }
    }

    fun setMode(mode: CaptureMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        if (!isFlashSupported) _flash.value = ImageCapture.FLASH_MODE_OFF
    }

    fun setStyle(style: PhotographicStyle) {
        _style.value = style
    }

    fun toggleFlash() {
        _flash.value =
            if (_flash.value == ImageCapture.FLASH_MODE_ON) ImageCapture.FLASH_MODE_OFF
            else ImageCapture.FLASH_MODE_ON
    }

    fun flipCamera() {
        _lens.value = if (_lens.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        _flash.value = ImageCapture.FLASH_MODE_OFF
    }

    /** Captures and automatically saves the photo to the gallery with the current style applied. */
    fun capture() {
        val c = controller ?: return
        if (_isBusy.value) return
        val mode = _mode.value
        val style = _style.value
        val flash = if (isFlashSupported) _flash.value else ImageCapture.FLASH_MODE_OFF
        val file = File(getApplication<Application>().cacheDir, "pixelcam_${System.currentTimeMillis()}.jpg")
        _isBusy.value = true

        c.capture(
            file = file,
            flashMode = flash,
            onSaved = {
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        val raw = PhotoProcessor.loadScaled(file)
                        val processed = PhotoProcessor.applyStyle(raw, style)
                        raw.recycle()
                        val uri = PhotoProcessor.saveToGallery(
                            getApplication<Application>(),
                            processed,
                            mode.label,
                            style.displayName
                        )
                        processed.recycle()
                        _lastPhotoUri.value = uri
                        emitEvent("Saved to Photos")
                    } catch (e: Exception) {
                        Log.w(TAG, "Save failed", e)
                        emitEvent("Save failed: ${e.message}")
                    } finally {
                        file.delete()
                        _isBusy.value = false
                    }
                }
            },
            onError = { e ->
                file.delete()
                _isBusy.value = false
                emitEvent("Capture failed: ${e.message}")
            }
        )
    }

    fun toast(message: String) {
        Toast.makeText(getApplication<Application>(), message, Toast.LENGTH_SHORT).show()
    }

    fun emitEvent(message: String) {
        viewModelScope.launch { _events.emit(message) }
    }
}
