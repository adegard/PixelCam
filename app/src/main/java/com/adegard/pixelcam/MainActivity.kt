package com.adegard.pixelcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.adegard.pixelcam.ui.CameraScreen
import com.adegard.pixelcam.ui.theme.PixelCamTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelCamTheme {
                CameraScreen(viewModel)
            }
        }
    }
}
