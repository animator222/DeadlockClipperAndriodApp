package com.deadlockclipper.core

/** Constants and results of the on-screen (kill badge / death screen) scan. */
object VisionConst {
    const val SAMPLE_FPS = 4
    // Default badge search areas as fractions of the frame (x, y, width, height). The user can
    // change them on the "Kill badge area" screen.
    // Horizontal 1920x1080 game capture: the badge sits just above the crosshair.
    val DEFAULT_REGION_LANDSCAPE = listOf(0.35, 0.12, 0.30, 0.33)
    // Vertical 1080x1920 layouts (e.g. OBS vertical canvas): the gameplay is in the middle band
    // and the badge sits above-right of centre.
    val DEFAULT_REGION_PORTRAIT = listOf(0.28, 0.25, 0.67, 0.25)
    const val BADGE_DIAMETER_1080P = 115.0 // rough badge size in pixels when the short side is 1080
    const val CROP_W = 384
    // The buy menu covers the badge area with a pale panel; frames where at least this much of
    // the badge area is pale panel are treated as "menu open".
    const val MENU_PANEL = 0.18
    const val THUMB_W = 160
    const val THUMB_H = 90
    const val SAVE_SCORE = 0.25 // frames at least this badge-like are saved for the detections picture
    const val CHUNK_SECONDS = 300.0 // the video is split into 5-minute pieces

    fun defaultRegion(info: VideoInfo): List<Double> =
        if (info.height > info.width) DEFAULT_REGION_PORTRAIT else DEFAULT_REGION_LANDSCAPE

    fun even(x: Double): Int = maxOf(2, Math.round(x / 2).toInt() * 2)

    /** Crop geometry for the badge area: (cw, ch, cx, cy, cropH, scale). */
    data class Geometry(val cw: Int, val ch: Int, val cx: Int, val cy: Int, val cropH: Int, val scale: Double)

    fun geometry(info: VideoInfo, region: List<Double>): Geometry {
        val (rx, ry, rw, rh) = region
        val cw = even(info.width * rw)
        val ch = even(info.height * rh)
        val cx = even(info.width * rx)
        val cy = even(info.height * ry)
        val cropH = even(ch * CROP_W.toDouble() / cw)
        return Geometry(cw, ch, cx, cy, cropH, CROP_W.toDouble() / cw)
    }
}

/** One row per sampled frame (SAMPLE_FPS per second), sorted by time. */
class VisionData(
    val t: FloatArray,
    val badge: FloatArray,
    val bx: FloatArray,
    val by: FloatArray,
    val sat: FloatArray,
    val value: FloatArray,
    val panel: FloatArray,
) {
    val size get() = t.size

    companion object {
        fun fromRows(rows: List<FloatArray>): VisionData {
            val sorted = rows.sortedBy { it[0] }
            fun col(i: Int) = FloatArray(sorted.size) { sorted[it][i] }
            return VisionData(col(0), col(1), col(2), col(3), col(4), col(5), col(6))
        }
    }
}

class Analysis(val audioDb: FloatArray, val vision: VisionData, val segments: List<DoubleArray>)
