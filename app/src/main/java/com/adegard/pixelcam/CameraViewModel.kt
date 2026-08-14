package com.adegard.pixelcam

import android.app.Application
import android.graphics.Bitmap
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

    data class PendingCapture(
        val raw: Bitmap,
        val mode: CaptureMode,
        val style: PhotographicStyle
    )

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

    private val _pendingCapture = MutableStateFlow<PendingCapture?>(null)
    val pendingCapture: StateFlow<PendingCapture?> = _pendingCapture.asStateFlow()

    private val _zoomState = MutableStateFlow<ZoomState?>(null)
    val zoomState: StateFlow<ZoomState?> = _zoomState.asStateFlow()

    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    private var controller: CameraController? = null
    private var zoomObserver: Observer<ZoomState>? = null

    val isFlashSupported: Boolean
        get() = _mode.value == CaptureMode.PHOTO

    fun initialize(lifecycleOwner: LifecycleOwner) {
        if (controller != null) return
        val c = CameraController(getApplication<Application>(), lifecycleOwner)
        controller = c
        viewModelScope.launch {
            c.initialize()
            refreshAvailability()
        }
    }

    private suspend fun refreshAvailability() {
        val c = controller ?: return
        val back = mutableMapOf<CaptureMode, Boolean>()
        for (mode in CaptureMode.entries) {
            back[mode] = mode.extensionMode == null ||
                c.isExtensionAvailable(CameraSelector.LENS_FACING_BACK, mode)
        }
        _availableModes.value = back
    }

    fun bindPreview(view: PreviewView) {
        viewModelScope.launch {
            val camera = controller?.bind(view, _lens.value, _mode.value)
            observeZoom(camera)
        }
    }

    private fun observeZoom(camera: androidx.camera.core.Camera?) {
        zoomObserver?.let { old ->
            _lastZoomLiveData?.removeObserver(old)
        }
        _lastZoomLiveData = camera?.cameraInfo?.zoomState
        if (_lastZoomLiveData != null) {
            zoomObserver = Observer<ZoomState> { state -> _zoomState.value = state }
            _lastZoomLiveData?.observeForever(zoomObserver)
        } else {
            _zoomState.value = null
        }
    }

    private var _lastZoomLiveData: androidx.lifecycle.LiveData<ZoomState>? = null

    /** Applies a zoom scale factor (e.g. from a pinch gesture) clamped to the camera range. */
    fun zoomBy(scale: Float) {
        val state = _zoomState.value ?: return
        val target = state.zoomRatio * scale
        setZoom(target)
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
        val supported = _availableModes.value[mode] ?: (mode.extensionMode == null)
        if (!supported) {
            emitEvent("${mode.label} mode is not supported on this device")
            return
        }
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
                        _pendingCapture.value = PendingCapture(raw, mode, style)
                    } catch (e: Exception) {
                        emitEvent("Capture failed: ${e.message}")
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

    fun dismissPendingCapture() {
        _pendingCapture.value = null
    }

    /** Saves the currently displayed processed image to the gallery. */
    fun save(bitmap: Bitmap, mode: CaptureMode, style: PhotographicStyle): Uri? {
        return try {
            PhotoProcessor.saveToGallery(getApplication<Application>(), bitmap, mode.label, style.displayName)
        } catch (e: Exception) {
            emitEvent("Could not save: ${e.message}")
            null
        }
    }

    fun toast(message: String) {
        Toast.makeText(getApplication<Application>(), message, Toast.LENGTH_SHORT).show()
    }

    private fun emitEvent(message: String) {
        viewModelScope.launch { _events.emit(message) }
    }
}
