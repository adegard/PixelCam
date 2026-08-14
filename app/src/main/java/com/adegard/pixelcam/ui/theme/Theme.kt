package com.adegard.pixelcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PixelCamColors = darkColorScheme(
    primary = Color(0xFF9FC4FF),
    onPrimary = Color(0xFF0A0A14),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF1A1A22),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF2A2A35),
    onSurfaceVariant = Color(0xFFC7C7CE)
)

@Composable
fun PixelCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PixelCamColors,
        content = content
    )
}
