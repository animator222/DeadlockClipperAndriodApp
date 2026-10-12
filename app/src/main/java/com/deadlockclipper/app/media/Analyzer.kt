package com.deadlockclipper.app.media

import android.content.Context
import com.deadlockclipper.app.BuildConfig
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.Paths
import com.deadlockclipper.app.util.Task
import com.deadlockclipper.core.Analysis
import com.deadlockclipper.core.Clip
import com.deadlockclipper.core.Events
import com.deadlockclipper.core.Loudness
import com.deadlockclipper.core.Report
import com.deadlockclipper.core.Settings
import com.deadlockclipper.core.VideoInfo
import com.deadlockclipper.core.VisionData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Runs the scans in order (audio first, then the video) and saves the results. */
object Analyzer {
    private const val TAG = "analysis"
    const val ANALYSIS_VERSION = 7 // same detection as the PC app's version 7 (template-based badge)
    private const val MAGIC = 0x444C4331 // "DLC1"

    private fun cacheKey(info: VideoInfo, quick: Boolean, region: List<Double>): String {
        val r = region.joinToString(",") { "%.3f".format(java.util.Locale.US, it) }
        val raw = "${info.uri}|${info.name}|${info.sizeBytes}|${info.lastModified / 1000}|$ANALYSIS_VERSION|$quick|$r"
        val sha = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return sha.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun load(file: File): Analysis? {
        if (!file.exists()) return null
        return try {
            DataInputStream(GZIPInputStream(file.inputStream().buffered())).use { inp ->
                if (inp.readInt() != MAGIC) return null
                fun floats() = FloatArray(inp.readInt()) { inp.readFloat() }
                val audio = floats()
                val segments = List(inp.readInt()) { doubleArrayOf(inp.readDouble(), inp.readDouble()) }
                val cols = List(7) { floats() }
                Analysis(audio, VisionData(cols[0], cols[1], cols[2], cols[3], cols[4], cols[5], cols[6]), segments)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Ignoring unreadable cache file $file: $e")
            null
        }
    }

    private fun save(file: File, a: Analysis) {
        try {
            DataOutputStream(GZIPOutputStream(file.outputStream().buffered())).use { out ->
                out.writeInt(MAGIC)
                fun floats(v: FloatArray) {
                    out.writeInt(v.size); for (x in v) out.writeFloat(x)
                }
                floats(a.audioDb)
                out.writeInt(a.segments.size)
                for (s in a.segments) {
                    out.writeDouble(s[0]); out.writeDouble(s[1])
                }
                val v = a.vision
                for (c in listOf(v.t, v.badge, v.bx, v.by, v.sat, v.value, v.panel)) floats(c)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Could not write cache", e)
        }
    }

    fun analyze(context: Context, paths: Paths, info: VideoInfo, quick: Boolean, region: List<Double>, task: Task): Analysis {
        val keys = listOf(cacheKey(info, false, region)) + (if (quick) listOf(cacheKey(info, true, region)) else emptyList())
        for (key in keys) {
            val cached = load(File(paths.cache, "$key.bin")) ?: continue
            AppLog.i(TAG, "Using cached analysis $key")
            val sheet = File(paths.cache, "${key}_detections.jpg")
            paths.detections.delete()
            if (sheet.exists()) sheet.copyTo(paths.detections, overwrite = true)
            task.progress(1.0, "Using saved analysis")
            return cached
        }

        var t0 = System.currentTimeMillis()
        val audioDb = AudioScan.loudnessCurve(context, info, task.sub(0.0, 0.25))
        AppLog.i(TAG, "Audio pass took %.1fs".format((System.currentTimeMillis() - t0) / 1000.0))

        val segments = Loudness.scanPlan(audioDb, info.duration, quick)
        AppLog.i(TAG, "Scanning %d parts of the video (%.0f of %.0f seconds), codec %s".format(
            segments.size, segments.sumOf { it[1] - it[0] }, info.duration, info.vcodec))
        t0 = System.currentTimeMillis()
        val vis = VisionScan.scanSegments(context, info, segments, region, task.sub(0.25, 1.0))
        AppLog.i(TAG, "Video pass took %.1fs".format((System.currentTimeMillis() - t0) / 1000.0))

        val key = cacheKey(info, quick, region)
        val result = Analysis(audioDb, vis.data, segments)
        save(File(paths.cache, "$key.bin"), result)
        paths.detections.delete()
        try {
            val sheet = File(paths.cache, "${key}_detections.jpg")
            if (BadgeDetector.detectionsSheet(vis.crops, sheet)) sheet.copyTo(paths.detections, overwrite = true)
        } catch (e: Exception) {
            AppLog.e(TAG, "Could not save detections picture", e)
        } finally {
            for (c in vis.crops) c.third.release()
        }
        trimCache(paths.cache)
        return result
    }

    /** Keeps the saved scans from growing forever (the 40 most recent are kept). */
    private fun trimCache(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedByDescending { it.lastModified() } ?: return
        for (f in files.drop(40)) {
            f.delete()
            File(dir, f.name.removeSuffix(".bin") + "_detections.jpg").delete()
        }
    }

    fun writeReport(paths: Paths, info: VideoInfo, events: Events, clips: List<Clip>, settings: Settings, segments: List<DoubleArray>) {
        try {
            paths.analysisReport.writeText(Report.text(info, events, clips, settings, segments, BuildConfig.VERSION_NAME))
        } catch (e: Exception) {
            AppLog.e(TAG, "Could not write analysis report", e)
        }
    }
}
