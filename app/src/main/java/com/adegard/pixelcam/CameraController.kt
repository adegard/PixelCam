package com.adegard.pixelcam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    /** The currently bound camera, used for zoom control. */
    val currentCamera: Camera?
        get() = camera

    private val preview = Preview.Builder().build()
    private val analysisExecutor: Executor = Executors.newSingleThreadExecutor()

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
    /**
     * Whether a given vendor extension mode is available for the given lens.
     * Returns true when the status is unknown, so modes are never stuck disabled:
     * binding will fall back to a plain capture if the extension isn't supported.
     */
    fun isExtensionAvailable(lensFacing: Int, mode: CameraViewModel.CaptureMode): Boolean {
        val extensionMode = mode.extensionMode ?: return false
        val manager = extensionsManager ?: return true
        return try {
            manager.isExtensionAvailable(selectorFor(lensFacing), extensionMode)
        } catch (e: Exception) {
            Log.w(TAG, "Extension availability check failed for $mode", e)
            true
        }
    }

    /** (Re)binds preview + image capture for the given lens and capture mode. */
    suspend fun bind(previewView: PreviewView, lensFacing: Int, mode: CameraViewModel.CaptureMode): Camera? {
        initialize()
        val provider = cameraProvider ?: return null
        val baseSelector = selectorFor(lensFacing)
        provider.unbindAll()

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build()
        imageCapture = capture

        // A live filter preview needs ImageAnalysis; vendor extensions occupy the
        // camera stream, so the analysis feed is only bound for plain Photo mode.
        imageAnalysis = if (mode.extensionMode == null) {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .build()
        } else {
            null
        }

        preview.setSurfaceProvider(previewView.surfaceProvider)

        val selector = extensionSelector(baseSelector, mode) ?: baseSelector
        camera = try {
            bindUseCases(provider, lifecycleOwner, selector, preview, capture, imageAnalysis)
        } catch (e: Exception) {
            Log.w(TAG, "Extension binding failed, falling back to plain camera: $mode", e)
            provider.unbindAll()
            bindUseCases(provider, lifecycleOwner, baseSelector, preview, capture, imageAnalysis)
        }
        return camera
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        preview: Preview,
        capture: ImageCapture,
        analysis: ImageAnalysis?
    ): Camera {
        return if (analysis != null) {
            provider.bindToLifecycle(owner, selector, preview, capture, analysis)
        } else {
            provider.bindToLifecycle(owner, selector, preview, capture)
        }
    }

    /**
     * Feeds preview frames to [onFrame] as a sensor-oriented [Bitmap] plus its
     * rotation in degrees. Only active in Photo mode (vendor extensions disable it).
     */
    fun setFrameListener(onFrame: (Bitmap, Int) -> Unit) {
        val analysis = imageAnalysis ?: return
        @OptIn(ExperimentalGetImage::class)
        analysis.setAnalyzer(analysisExecutor) { imageProxy: ImageProxy ->
            try {
                val bitmap = imageProxy.toBitmap()
                onFrame(bitmap, imageProxy.imageInfo.rotationDegrees)
            } catch (e: Exception) {
                Log.w(TAG, "Frame conversion failed", e)
            } finally {
                imageProxy.close()
            }
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
