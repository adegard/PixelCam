package com.adegard.pixelcam

import android.graphics.ColorMatrix

/**
 * iOS-style "photographic styles": subtle, natural-looking color grading
 * applied as a color matrix during post-processing.
 */
enum class PhotographicStyle(val displayName: String) {
    STANDARD("Standard"),
    VIBRANT("Vibrant"),
    WARM("Warm"),
    COOL("Cool"),
    RICH("Rich Contrast"),
    MONO("Mono");

    fun colorMatrix(): ColorMatrix {
        val saturation = ColorMatrix().apply { setSaturation(saturationFactor) }
        val contrast = contrastMatrix(contrastFactor, brightnessShift)
        val tint = tintMatrix()
        val combined = ColorMatrix()
        combined.postConcat(saturation)
        combined.postConcat(contrast)
        combined.postConcat(tint)
        return combined
    }

    private val saturationFactor: Float
        get() = when (this) {
            STANDARD -> 1.10f
            VIBRANT -> 1.38f
            WARM -> 1.08f
            COOL -> 1.08f
            RICH -> 1.16f
            MONO -> 0f
        }

    private val contrastFactor: Float
        get() = when (this) {
            STANDARD -> 1.08f
            VIBRANT -> 1.14f
            WARM -> 1.06f
            COOL -> 1.06f
            RICH -> 1.34f
            MONO -> 1.18f
        }

    // +1 = brighter, -1 = darker, 0 = unchanged
    private val brightnessShift: Float
        get() = when (this) {
            STANDARD -> 0.00f
            VIBRANT -> 0.00f
            WARM -> 0.00f
            COOL -> 0.00f
            RICH -> -0.02f
            MONO -> 0.00f
        }

    private fun tintMatrix(): ColorMatrix = when (this) {
        STANDARD -> ColorMatrix(floatArrayOf(
            1.02f, 0f, 0f, 0f, 3f,
            0f, 1.0f, 0f, 0f, 1f,
            0f, 0f, 0.97f, 0f, -3f,
            0f, 0f, 0f, 1f, 0f
        ))
        WARM -> ColorMatrix(floatArrayOf(
            1.12f, 0f, 0f, 0f, 7f,
            0f, 1.04f, 0f, 0f, 2f,
            0f, 0f, 0.90f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f
        ))
        COOL -> ColorMatrix(floatArrayOf(
            0.90f, 0f, 0f, 0f, -6f,
            0f, 1.02f, 0f, 0f, 0f,
            0f, 0f, 1.12f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f
        ))
        else -> ColorMatrix()
    }

    private fun contrastMatrix(contrast: Float, brightness: Float): ColorMatrix {
        val scale = contrast
        val translate = (0.5f * (1f - scale) + brightness) * 255f
        return ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
    }
}
