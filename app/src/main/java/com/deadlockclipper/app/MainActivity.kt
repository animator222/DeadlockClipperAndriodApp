package com.deadlockclipper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        org.opencv.android.OpenCVLoader.initLocal()
        org.schabi.newpipe.extractor.ServiceList.YouTube.serviceId
        androidx.media3.exoplayer.ExoPlayer.Builder(this)
        setContent { Text("Deadlock Clipper") }
    }
}
