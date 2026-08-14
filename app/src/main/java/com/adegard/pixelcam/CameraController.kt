package com.adegard.pixelcam

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor

/**
 * Wraps CameraX: handles camera binding and capture. When the device (e.g. a Pixel)
 * supports vendor extensions, Photo/HDR/Night/Portrait modes are enabled through them.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val executor: Executor = ContextCompat.getMainExecutor(context)
) {

    companion object {
        private const val TAG = "CameraController"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var imageCapture: ImageCapture? = null

    private val preview = Preview.Builder().build()

    /** Idempotent CameraX initialization. */
    suspend fun initialize() {
        if (cameraProvider != null) return
        try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            cameraProvider = provider
            extensionsManager = ExtensionsManager.getInstanceAsync(context, provider).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CameraX", e)
        }
    }

    /** Whether a given vendor extension mode is available for the given lens. */
    fun isExtensionAvailable(lensFacing: Int, mode: CameraViewModel.CaptureMode): Boolean {
        val extensionMode = mode.extensionMode ?: return false
        val manager = extensionsManager ?: return false
        return try {
            manager.isExtensionAvailable(selectorFor(lensFacing), extensionMode)
        } catch (e: Exception) {
            Log.w(TAG, "Extension availability check failed for $mode", e)
            false
        }
    }

    /** (Re)binds preview + image capture for the given lens and capture mode. */
    suspend fun bind(previewView: PreviewView, lensFacing: Int, mode: CameraViewModel.CaptureMode) {
        initialize()
        val provider = cameraProvider ?: return
        val baseSelector = selectorFor(lensFacing)
        provider.unbindAll()

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build()
        imageCapture = capture

        preview.setSurfaceProvider(previewView.surfaceProvider)

        val selector = extensionSelector(baseSelector, mode) ?: baseSelector
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        } catch (e: Exception) {
            Log.w(TAG, "Extension binding failed, falling back to plain camera: $mode", e)
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, baseSelector, preview, capture)
        }
    }

    private fun extensionSelector(
        baseSelector: CameraSelector,
        mode: CameraViewModel.CaptureMode
    ): CameraSelector? {
        val extensionMode = mode.extensionMode ?: return null
        val manager = extensionsManager ?: return null
        return try {
            if (manager.isExtensionAvailable(baseSelector, extensionMode)) {
                manager.getExtensionEnabledCameraSelector(baseSelector, extensionMode)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enable extension $mode", e)
            null
        }
    }

    /** Captures a photo into [file]. */
    fun capture(
        file: File,
        flashMode: Int,
        onSaved: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null) {
            onError(IllegalStateException("Camera is not ready"))
            return
        }
        capture.flashMode = flashMode
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSaved()
                }

                override fun onError(exc: ImageCaptureException) {
                    onError(exc)
                }
            }
        )
    }

    private fun selectorFor(lensFacing: Int): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }
}
