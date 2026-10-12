package com.deadlockclipper.core

import kotlinx.serialization.Serializable

@Serializable
data class VideoInfo(
    val uri: String,
    val name: String,
    val duration: Double,
    val width: Int, // as displayed (rotation applied)
    val height: Int,
    val fps: Double, // capped at 60 for exports
    val hasAudio: Boolean,
    val vcodec: String,
    val sizeBytes: Long = 0,
    val lastModified: Long = 0,
    val rotation: Int = 0,
) {
    val portrait get() = height > width
}
