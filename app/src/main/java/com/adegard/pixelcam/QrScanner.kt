package com.adegard.pixelcam

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Wraps ML Kit barcode scanning. Each call to [process] sends a frame to the
 * on-device scanner and invokes [onResult] with the first detected barcode
 * (if any). Duplicate consecutive results are suppressed.
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

    /**
     * Process a [bitmap] for barcodes. The result callback is only invoked
     * when a *new* barcode value is detected (duplicates are skipped).
     */
    fun process(bitmap: Bitmap, onResult: (String) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue
                if (value != null && value != lastRawValue) {
                    lastRawValue = value
                    onResult(value)
                }
            }
    }

    /** Call when the camera is closed / binding released. */
    fun close() {
        scanner.close()
    }
}
