package com.adegard.pixelcam

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Wraps ML Kit barcode scanning. [processFrame] feeds a CameraX frame to the
 * on-device scanner and invokes [onResult] when a *new* barcode value is
 * detected (duplicate suppression). The ImageProxy is closed once ML Kit
 * finishes processing it.
 */
class QrScanner {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()
    )

    private var lastRawValue: String? = null

    /** Processes a camera frame; the proxy is closed when the task completes. */
    fun processFrame(imageProxy: ImageProxy, onResult: (String) -> Unit) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        scanner.process(image)
            .addOnCompleteListener { imageProxy.close() }
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue
                if (value != null && value != lastRawValue) {
                    lastRawValue = value
                    onResult(value)
                }
            }
    }

    /** Clears duplicate suppression so a code can be scanned again. */
    fun reset() {
        lastRawValue = null
    }

    /** Call when the camera binding is released. */
    fun close() {
        scanner.close()
    }
}