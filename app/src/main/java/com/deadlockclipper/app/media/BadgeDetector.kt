package com.deadlockclipper.app.media

import com.deadlockclipper.core.VisionConst
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * On-screen detection with OpenCV - the same checks as the PC app (vision.py):
 *   * the red "KILL" / "KILL ASSIST" badge above the crosshair (only for YOUR kills/assists)
 *   * the pale buy-menu panel (badges are ignored while the shop is open)
 * All Mats are released as soon as possible: a scan looks at tens of thousands of frames.
 */
object BadgeDetector {
    class Found(val score: Double, val x: Double, val y: Double)

    private val NONE = Found(0.0, -1.0, -1.0)

    private class Pool {
        val mats = ArrayList<Mat>()
        fun mat(): Mat = Mat().also { mats.add(it) }
        fun <T : Mat> keep(m: T): T = m.also { mats.add(it) }
        fun release() {
            for (m in mats) m.release()
            mats.clear()
        }
    }

    private fun inRange(hsv: Mat, lo: Scalar, hi: Scalar, out: Mat): Mat {
        Core.inRange(hsv, lo, hi, out)
        return out
    }

    private fun meanOf(mask: Mat): Double = if (mask.total() == 0L) 0.0 else Core.countNonZero(mask).toDouble() / mask.total()

    /** Fraction of [values] (0/255) that is set where [where] (0/255) is set. */
    private fun meanInside(values: Mat, where: Mat, p: Pool): Double {
        val n = Core.countNonZero(where)
        if (n == 0) return 0.0
        val both = p.mat()
        Core.bitwise_and(values, where, both)
        return Core.countNonZero(both).toDouble() / n
    }

    /** How much of the image is pale, washed-out UI panel (the buy menu). */
    fun panelFraction(hsv: Mat): Double {
        val m = Mat()
        try {
            return meanOf(inRange(hsv, Scalar(0.0, 0.0, 121.0), Scalar(180.0, 69.0, 234.0), m))
        } finally {
            m.release()
        }
    }

    /** Mean saturation and value of an image (the death screen is grey). */
    fun meanSatVal(bgr: Mat): Pair<Double, Double> {
        val hsv = Mat()
        try {
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            val m = Core.mean(hsv)
            return m.`val`[1] to m.`val`[2]
        } finally {
            hsv.release()
        }
    }

    fun toHsv(bgr: Mat): Mat = Mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2HSV) }

    /** Score (0..1) how much the crop looks like the red kill badge, plus its centre (crop px / scale). */
    fun detect(hsv: Mat, scale: Double, shortSide: Int): Found {
        val p = Pool()
        try {
            val red = p.mat()
            val red2 = p.mat()
            inRange(hsv, Scalar(0.0, 110.0, 80.0), Scalar(10.0, 255.0, 255.0), red)
            inRange(hsv, Scalar(170.0, 110.0, 80.0), Scalar(180.0, 255.0, 255.0), red2)
            Core.bitwise_or(red, red2, red)
            val k5 = p.keep(Mat.ones(5, 5, CvType.CV_8U))
            Imgproc.morphologyEx(red, red, Imgproc.MORPH_CLOSE, k5)
            val contours = ArrayList<MatOfPoint>()
            val hier = p.mat()
            Imgproc.findContours(red.clone().also { p.keep(it) }, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.forEach { p.keep(it) }

            val d0 = VisionConst.BADGE_DIAMETER_1080P * (shortSide / 1080.0) * scale
            val minArea = PI / 4 * (0.45 * d0) * (0.45 * d0)
            val maxArea = PI / 4 * (1.8 * d0) * (1.8 * d0)
            val light = inRange(hsv, Scalar(0.0, 0.0, 170.0), Scalar(180.0, 90.0, 255.0), p.mat()) // white text

            var best = NONE
            for ((idx, c) in contours.withIndex()) {
                val area = Imgproc.contourArea(c)
                if (area < minArea || area > maxArea) continue
                val c2f = p.keep(MatOfPoint2f(*c.toArray()))
                val center = Point()
                val radius = FloatArray(1)
                Imgproc.minEnclosingCircle(c2f, center, radius)
                val r = radius[0].toDouble()
                val circularity = area / (PI * r * r + 1e-6)
                val br = Imgproc.boundingRect(c)
                val aspect = br.width.toDouble() / max(1, br.height)
                if (circularity < 0.55 || aspect < 0.65 || aspect > 1.5) continue
                val mask = p.keep(Mat.zeros(red.size(), CvType.CV_8U))
                Imgproc.drawContours(mask, contours, idx, Scalar(255.0), -1)
                val textFrac = meanInside(light, mask, p)
                if (textFrac < 0.01 || textFrac > 0.6) continue
                val score = min(1.0, circularity) * min(1.0, textFrac / 0.04)
                if (score > best.score) best = Found(score, center.x / scale, center.y / scale)
            }
            // In vertical layouts the badge can be about half its normal size: try smaller sizes too.
            var souls = NONE
            for (k in doubleArrayOf(1.0, 0.75, 0.55)) {
                val f = soulsBadge(hsv, d0 * k)
                if (f.score > souls.score) souls = f
            }
            val touching = if (best.score < 0.5) touchingBadge(red, light, d0, minArea) else NONE
            for (f in listOf(souls, touching)) {
                if (f.score > best.score) best = Found(f.score, f.x / scale, f.y / scale)
            }
            return best
        } finally {
            p.release()
        }
    }

    /**
     * The current (2026) badge: a red splash with KILL/ASSIST and, below the centre, the souls
     * you earned in mint-green ("$186"). Mint-green text of that size, surrounded by red on all
     * sides, is very particular to this badge.
     */
    private fun soulsBadge(hsv: Mat, d: Double): Found {
        val p = Pool()
        try {
            val mint = inRange(hsv, Scalar(35.0, 40.0, 140.0), Scalar(100.0, 255.0, 255.0), p.mat())
            // Heavy compression (e.g. YouTube downloads) washes the mint out.
            val white = inRange(hsv, Scalar(0.0, 0.0, 170.0), Scalar(180.0, 70.0, 255.0), p.mat())
            val green = p.mat()
            Core.bitwise_or(mint, white, green)
            val red = inRange(hsv, Scalar(0.0, 100.0, 60.0), Scalar(10.0, 255.0, 255.0), p.mat())
            val red2 = inRange(hsv, Scalar(170.0, 100.0, 60.0), Scalar(180.0, 255.0, 255.0), p.mat())
            Core.bitwise_or(red, red2, red)
            val kernel = p.keep(Mat.ones(3, max(3, (d * 0.08).toInt()), CvType.CV_8U))
            val merged = p.mat()
            Imgproc.dilate(green, merged, kernel) // join the digits
            val labels = p.mat()
            val stats = p.mat()
            val centroids = p.mat()
            val n = Imgproc.connectedComponentsWithStats(merged, labels, stats, centroids)
            val rows = red.rows()
            val cols = red.cols()
            var best = NONE
            for (i in 1 until n) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                // Real souls text: about 0.42 x 0.13 of the badge size, about a third filled.
                if (w < 0.30 * d || w > 0.62 * d || h < 0.08 * d || h > 0.22 * d) continue
                val roi = green.submat(Rect(x, y, w, h))
                val density = meanOf(roi)
                roi.release()
                if (density < 0.22 || density > 0.55) continue
                val cx = x + w / 2.0
                val cy = y + h / 2.0
                fun redShare(ax0: Double, ax1: Double, ay0: Double, ay1: Double): Double {
                    val x0 = max(0.0, cx + ax0 * d).toInt()
                    val x1 = min(cols.toDouble(), cx + ax1 * d).toInt()
                    val y0 = max(0.0, cy + ay0 * d).toInt()
                    val y1 = min(rows.toDouble(), cy + ay1 * d).toInt()
                    if (x1 <= x0 || y1 <= y0) return 0.0
                    val sub = red.submat(Rect(x0, y0, x1 - x0, y1 - y0))
                    return meanOf(sub).also { sub.release() }
                }
                val around = listOf(
                    redShare(-0.6, -0.3, -0.4, 0.15), redShare(0.3, 0.6, -0.4, 0.15),
                    redShare(-0.3, 0.3, -0.62, -0.38), redShare(-0.3, 0.3, 0.12, 0.35),
                ).sorted()
                if (around[0] < 0.08 || around[1] < 0.25) continue
                val score = min(1.0, 0.55 + around[1])
                if (score > best.score) best = Found(score, cx, cy - 0.12 * d) // the badge's centre is just above the souls
            }
            return best
        } finally {
            p.release()
        }
    }

    /**
     * A badge whose red merges with something red next to it (e.g. a red hat just below it):
     * the blob isn't round, but it contains a solid red disk of badge size with text in the middle.
     */
    private fun touchingBadge(red: Mat, light: Mat, d0: Double, minArea: Double): Found {
        val p = Pool()
        try {
            val nearRed = p.mat()
            Imgproc.dilate(red, nearRed, p.keep(Mat.ones(9, 9, CvType.CV_8U)))
            val lightNear = p.mat()
            Core.bitwise_and(light, nearRed, lightNear)
            val solid = p.mat()
            Core.bitwise_or(red, lightNear, solid) // fill the text holes so the badge reads as one disk
            Imgproc.morphologyEx(solid, solid, Imgproc.MORPH_CLOSE, p.keep(Mat.ones(5, 5, CvType.CV_8U)))
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(solid.clone().also { p.keep(it) }, contours, p.mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.forEach { p.keep(it) }
            var best = NONE
            for ((idx, c) in contours.withIndex()) {
                val area = Imgproc.contourArea(c)
                if (area < minArea) continue
                val mask = p.keep(Mat.zeros(solid.size(), CvType.CV_8U))
                Imgproc.drawContours(mask, contours, idx, Scalar(255.0), -1)
                val dist = p.mat()
                Imgproc.distanceTransform(mask, dist, Imgproc.DIST_L2, 5)
                val mm = Core.minMaxLoc(dist)
                val r = mm.maxVal
                val x = mm.maxLoc.x
                val y = mm.maxLoc.y
                if (r < 0.45 * d0 / 2 || r > 1.8 * d0 / 2) continue
                if (area > 3.0 * PI * r * r) continue // a big red area (wall, explosion), not a badge
                val inner = p.keep(Mat.zeros(solid.size(), CvType.CV_8U))
                Imgproc.circle(inner, Point(x.toInt().toDouble(), y.toInt().toDouble()), max(2, (r * 0.75).toInt()), Scalar(255.0), -1)
                val textFrac = meanInside(light, inner, p)
                val redFrac = meanInside(red, inner, p)
                if (textFrac < 0.03 || textFrac > 0.5 || redFrac < 0.4) continue
                val score = 0.9 * min(1.0, textFrac / 0.06)
                if (score > best.score) best = Found(score, x, y)
            }
            return best
        } finally {
            p.release()
        }
    }

    /** Badge check of a whole frame (the "Kill badge area" screen). */
    fun checkFrame(bgr: Mat, region: List<Double>): Double {
        val w = bgr.cols()
        val h = bgr.rows()
        val (x, y, rw, rh) = region
        val x0 = (x * w).toInt().coerceIn(0, w - 1)
        val y0 = (y * h).toInt().coerceIn(0, h - 1)
        val x1 = ((x + rw) * w).toInt().coerceIn(x0 + 1, w)
        val y1 = ((y + rh) * h).toInt().coerceIn(y0 + 1, h)
        val crop = bgr.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        val scale = VisionConst.CROP_W.toDouble() / crop.cols()
        val resized = Mat()
        Imgproc.resize(crop, resized, Size(VisionConst.CROP_W.toDouble(), max(2, (crop.rows() * scale).toInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val hsv = toHsv(resized)
        try {
            return detect(hsv, scale, min(w, h)).score
        } finally {
            crop.release(); resized.release(); hsv.release()
        }
    }

    /** One picture showing everything that looked like a kill badge, for tuning. */
    fun detectionsSheet(crops: List<Triple<Double, Double, Mat>>, out: File, limit: Int = 120): Boolean {
        if (crops.isEmpty()) return false
        var list = crops.sortedBy { it.first }
        if (list.size > limit) list = list.sortedByDescending { it.second }.take(limit).sortedBy { it.first }
        val th = list[0].third.rows()
        val tw = list[0].third.cols()
        val cols = 6
        val rows = (list.size + cols - 1) / cols
        val sheet = Mat(rows * (th + 22), cols * tw, CvType.CV_8UC3, Scalar(24.0, 24.0, 24.0))
        try {
            for ((i, item) in list.withIndex()) {
                val (t, score, img) = item
                val r = i / cols
                val c = i % cols
                val y = r * (th + 22)
                val x = c * tw
                val hh = min(th, img.rows())
                val ww = min(tw, img.cols())
                val dst = sheet.submat(Rect(x, y, ww, hh))
                img.submat(Rect(0, 0, ww, hh)).copyTo(dst)
                dst.release()
                val s = t.toInt()
                val stamp = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
                val color = if (score >= 0.5) Scalar(80.0, 220.0, 80.0) else Scalar(60.0, 160.0, 240.0)
                Imgproc.putText(sheet, "$stamp  ${"%.2f".format(score)}", Point(x + 4.0, y + th + 16.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.45, color, 1, Imgproc.LINE_AA)
            }
            out.parentFile?.mkdirs()
            return Imgcodecs.imwrite(out.path, sheet, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85))
        } finally {
            sheet.release()
        }
    }
}
