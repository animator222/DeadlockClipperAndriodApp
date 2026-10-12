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

    // "Redness" maps (red minus the larger of green/blue) of the real KILL and ASSIST badges,
    // 64 px across. Measured on 120 hand-labelled frames of the user's footage: 0 false hits.
    private var templates: List<Mat> = emptyList()
    private const val TEMPLATE_DIAM = 64.0
    private val TEMPLATE_SCALES = doubleArrayOf(0.6, 0.75, 0.9, 1.05, 1.2) // badge size relative to the expected size
    private const val MATCH_MIN = 0.65
    private const val RING_MIN = 0.6
    private const val TEXT_MIN = 0.05
    private const val TEXT_MAX = 0.45

    fun init(assets: android.content.res.AssetManager) {
        templates = listOf("assist", "kill").map { n ->
            val bytes = assets.open("badge_templates/$n.png").use { it.readBytes() }
            val gray = Imgcodecs.imdecode(org.opencv.core.MatOfByte(*bytes), Imgcodecs.IMREAD_GRAYSCALE)
            Mat().also { gray.convertTo(it, CvType.CV_32F, 1.0 / 255.0); gray.release() }
        }
    }

    /** red minus the larger of green/blue, 0..1 (float). */
    private fun redness(bgr: Mat, p: Pool): Mat {
        val ch = ArrayList<Mat>()
        Core.split(bgr, ch)
        ch.forEach { p.keep(it) }
        val b = p.mat(); val g = p.mat(); val r = p.mat()
        ch[0].convertTo(b, CvType.CV_32F); ch[1].convertTo(g, CvType.CV_32F); ch[2].convertTo(r, CvType.CV_32F)
        val gb = p.mat()
        Core.max(g, b, gb)
        val out = p.mat()
        Core.subtract(r, gb, out)
        Imgproc.threshold(out, out, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        Core.multiply(out, Scalar(1.0 / 255.0), out)
        return out
    }

    /**
     * The current (2026) badge: a red splash ring with KILL/ASSIST and the souls amount inside.
     * Template matching on "redness" finds the shape; the red ring and the text inside confirm it.
     * Returns the score and the centre in crop pixels.
     */
    private fun templateBadge(crop: Mat, d0: Double): Found {
        if (templates.isEmpty()) return NONE
        val p = Pool()
        try {
            // Search at half resolution (4x faster); the badge is still ~30+ px there.
            val small = p.mat()
            Imgproc.resize(crop, small, Size((crop.cols() / 2).toDouble(), (crop.rows() / 2).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val redMap = redness(small, p)
            var bestMatch = -1.0
            var bx = 0.0
            var by = 0.0
            var bd = 0.0
            for (k in TEMPLATE_SCALES) {
                val diam = d0 * k / 2
                val sized = templates.map { t ->
                    p.mat().also { Imgproc.resize(t, it, Size(), diam / TEMPLATE_DIAM, diam / TEMPLATE_DIAM, Imgproc.INTER_AREA) }
                }
                val th = sized[0].rows()
                val tw = sized[0].cols()
                if (th < 8 || th >= redMap.rows() || tw >= redMap.cols()) continue
                // Flat areas give meaningless scores: ignore places with almost no variation.
                val mean = p.mat()
                Imgproc.blur(redMap, mean, Size(tw.toDouble(), th.toDouble()))
                val sq = p.mat()
                Core.multiply(redMap, redMap, sq)
                val meanSq = p.mat()
                Imgproc.blur(sq, meanSq, Size(tw.toDouble(), th.toDouble()))
                val var2 = p.mat()
                Core.multiply(mean, mean, var2)
                Core.subtract(meanSq, var2, var2)
                Imgproc.threshold(var2, var2, 0.0, 0.0, Imgproc.THRESH_TOZERO)
                val std = p.mat()
                Core.sqrt(var2, std)
                for (tt in sized) {
                    val th2 = tt.rows()
                    val tw2 = tt.cols()
                    val res = p.mat()
                    Imgproc.matchTemplate(redMap, tt, res, Imgproc.TM_CCOEFF_NORMED)
                    val rows = minOf(res.rows(), std.rows() - th2 / 2)
                    val cols = minOf(res.cols(), std.cols() - tw2 / 2)
                    if (rows <= 0 || cols <= 0) continue
                    val sd = p.keep(std.submat(th2 / 2, th2 / 2 + rows, tw2 / 2, tw2 / 2 + cols))
                    val r = p.keep(res.submat(0, rows, 0, cols).clone())
                    Core.patchNaNs(r, -1.0)
                    val flat = p.mat()
                    Core.compare(sd, Scalar(0.06), flat, Core.CMP_LE)
                    r.setTo(Scalar(-1.0), flat)
                    val mm = Core.minMaxLoc(r)
                    if (mm.maxVal > bestMatch) {
                        bestMatch = mm.maxVal
                        bx = (mm.maxLoc.x + tw2 / 2.0) * 2
                        by = (mm.maxLoc.y + th2 / 2.0) * 2
                        bd = diam * 2
                    }
                }
            }
            if (bestMatch < 0.3) return Found(maxOf(0.0, bestMatch) * 0.5, -1.0, -1.0)

            // Confirm: a red ring around the centre, and text (bright, not red) inside it.
            val x0 = maxOf(0.0, bx - bd).toInt()
            val x1 = minOf(crop.cols().toDouble(), bx + bd).toInt()
            val y0 = maxOf(0.0, by - bd).toInt()
            val y1 = minOf(crop.rows().toDouble(), by + bd).toInt()
            if (x1 <= x0 || y1 <= y0) return Found(minOf(0.45, bestMatch * 0.5), bx, by)
            val roi = p.keep(crop.submat(y0, y1, x0, x1))
            val hsv = p.mat()
            Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
            val w = x1 - x0
            val h = y1 - y0
            val px = ByteArray(w * h * 3)
            hsv.get(0, 0, px)
            val red = BooleanArray(w * h)
            val text = BooleanArray(w * h)
            for (i in 0 until w * h) {
                val hh = px[i * 3].toInt() and 0xFF
                val ss = px[i * 3 + 1].toInt() and 0xFF
                val vv = px[i * 3 + 2].toInt() and 0xFF
                val redHue = hh <= 10 || hh >= 170
                red[i] = redHue && ss >= 100 && vv >= 60
                text[i] = vv >= 150 && !(redHue && ss >= 90)
            }
            var ringRed = 0.0
            var innerText = 0.0
            for (k in TEMPLATE_SCALES) { // the exact size is unknown: use the one with the clearest ring
                val dd = d0 * k
                var ringN = 0; var ringR = 0; var innN = 0; var innT = 0
                for (yy in 0 until h) for (xx in 0 until w) {
                    val r = Math.hypot(xx + x0 - bx, yy + y0 - by)
                    val i = yy * w + xx
                    if (r > 0.32 * dd && r <= 0.5 * dd) {
                        ringN++; if (red[i]) ringR++
                    } else if (r <= 0.32 * dd) {
                        innN++; if (text[i]) innT++
                    }
                }
                if (ringN > 0 && innN > 0 && ringR.toDouble() / ringN > ringRed) {
                    ringRed = ringR.toDouble() / ringN
                    innerText = innT.toDouble() / innN
                }
            }
            val ok = bestMatch >= MATCH_MIN && ringRed >= RING_MIN && innerText in TEXT_MIN..TEXT_MAX
            val score = if (ok) 0.5 + 0.5 * min(1.0, (bestMatch - MATCH_MIN) / 0.3) else min(0.45, bestMatch * 0.5)
            return Found(score, bx, by)
        } finally {
            p.release()
        }
    }

    /**
     * Score (0..1, >= 0.5 counts) how much the crop shows the red kill/assist badge, plus its
     * centre (crop px / scale). Two checks: the current badge (template match), and the older
     * plain red circle with white KILL / KILL ASSIST text (for older recordings).
     */
    fun detect(bgr: Mat, hsv: Mat, scale: Double, shortSide: Int): Found {
        val d0 = VisionConst.BADGE_DIAMETER_1080P * (shortSide / 1080.0) * scale
        val t = templateBadge(bgr, d0)
        var best = if (t.x >= 0) Found(t.score, t.x / scale, t.y / scale) else t
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
            val minArea = PI / 4 * (0.45 * d0) * (0.45 * d0)
            val maxArea = PI / 4 * (1.8 * d0) * (1.8 * d0)
            val light = inRange(hsv, Scalar(0.0, 0.0, 170.0), Scalar(180.0, 90.0, 255.0), p.mat()) // white text
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
            return best
        } finally {
            p.release()
        }
    }

    /**
     * Badge check of a whole frame (the "Kill badge area" screen). Returns the score and the
     * badge centre as fractions of the frame (-1 if none).
     */
    fun checkFrame(bgr: Mat, region: List<Double>): Found {
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
            val f = detect(resized, hsv, scale, min(w, h))
            if (f.x < 0) return Found(f.score, -1.0, -1.0)
            return Found(f.score, (x0 + f.x) / w, (y0 + f.y) / h)
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
