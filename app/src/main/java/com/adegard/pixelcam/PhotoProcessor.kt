package com.adegard.pixelcam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object PhotoProcessor {

    private const val MAX_DIMENSION = 4000
    private const val JPEG_QUALITY = 92

    /** Decode the captured JPEG, downscaling so the longest edge is at most [MAX_DIMENSION]. */
    fun loadScaled(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, options)
            ?: throw IllegalStateException("Could not decode captured image")
    }

    /** Apply a photographic style to a copy of the source bitmap. */
    fun applyStyle(source: Bitmap, style: PhotographicStyle): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(
            source,
            0f,
            0f,
            Paint().apply {
                colorFilter = ColorMatrixColorFilter(style.colorMatrix())
                isFilterBitmap = true
            }
        )
        return result
    }

    /** Insert the bitmap into the device gallery and return its content Uri. */
    fun saveToGallery(context: Context, bitmap: Bitmap, modeName: String, styleName: String): Uri {
        val resolver = context.contentResolver
        val fileName =
            "PixelCam_${modeName}_${styleName}_${System.currentTimeMillis()}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/PixelCam"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create gallery entry")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
