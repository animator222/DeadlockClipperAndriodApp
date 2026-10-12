package com.deadlockclipper.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.net.Uri
import android.opengl.GLES20
import com.deadlockclipper.app.gl.CompositionRenderer
import com.deadlockclipper.app.gl.EglCore
import com.deadlockclipper.app.gl.Fbo
import com.deadlockclipper.app.gl.FrameWaiter
import com.deadlockclipper.app.gl.GlUtil
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.Cancelled
import com.deadlockclipper.app.util.FriendlyError
import com.deadlockclipper.app.util.Task
import com.deadlockclipper.core.VideoInfo
import com.deadlockclipper.core.VisionConst
import com.deadlockclipper.core.VisionConst.CROP_W
import com.deadlockclipper.core.VisionConst.THUMB_H
import com.deadlockclipper.core.VisionConst.THUMB_W
import com.deadlockclipper.core.VisionData
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Range
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Looks at the video SAMPLE_FPS times per second. The phone's video decoder decodes every frame;
 * only the sampled ones are drawn (by the graphics chip) into a small image: the badge area,
 * scaled to CROP_W wide, on top of a 160x90 thumbnail of the whole screen. OpenCV then checks it.
 */
object VisionScan {
    private const val TAG = "vision"
    private const val FIRST_FRAME_TIMEOUT_MS = 60_000L

    class Result(val data: VisionData, val crops: List<Triple<Double, Double, Mat>>)

    private class ChunkResult(val rows: List<FloatArray>, val crops: List<Triple<Double, Double, Mat>>)

    private class DecoderFailed(msg: String, val decoder: String?) : Exception(msg)
    private class NoDecoder : Exception("no decoder available")

    fun workers(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return if (cores >= 6) 2 else 1
    }

    fun scanSegments(context: Context, info: VideoInfo, segments: List<DoubleArray>, region: List<Double>, task: Task): Result {
        val chunks = ArrayList<DoubleArray>()
        for (seg in segments) {
            var t = seg[0]
            while (t < seg[1] - 0.01) {
                chunks.add(doubleArrayOf(t, minOf(seg[1], t + VisionConst.CHUNK_SECONDS)))
                t += VisionConst.CHUNK_SECONDS
            }
        }
        val totalFrames = maxOf(1.0, chunks.sumOf { it[1] - it[0] } * VisionConst.SAMPLE_FPS)
        val frames = AtomicInteger()
        val onFrame = {
            val n = frames.incrementAndGet()
            if (n % 40 == 0) task.progress(n / totalFrames, "Watching for your kills")
        }
        val queue = ConcurrentLinkedQueue(chunks)
        val bad: MutableSet<String> = Collections.synchronizedSet(HashSet())
        val rows = Collections.synchronizedList(ArrayList<FloatArray>())
        val crops = Collections.synchronizedList(ArrayList<Triple<Double, Double, Mat>>())
        val active = AtomicInteger()
        val nWorkers = minOf(workers(), chunks.size.coerceAtLeast(1))
        AppLog.i(TAG, "Scanning ${chunks.size} pieces with $nWorkers parallel decoders")
        val pool = Executors.newFixedThreadPool(nWorkers)
        val futures = ArrayList<Future<*>>()
        repeat(nWorkers) {
            active.incrementAndGet()
            futures.add(pool.submit(Runnable {
                val worker = Worker(context, info, region)
                try {
                    while (true) {
                        val chunk = queue.poll() ?: break
                        try {
                            val r = worker.scanChunk(chunk[0], chunk[1], bad, task, onFrame)
                            rows.addAll(r.rows)
                            crops.addAll(r.crops)
                        } catch (e: NoDecoder) {
                            queue.add(chunk)
                            // Probably too many decoders running at once on this phone: let the others go on.
                            if (active.get() > 1) {
                                AppLog.w(TAG, "No free decoder; continuing with fewer parallel decoders")
                                return@Runnable
                            }
                            throw FriendlyError(
                                "This phone couldn't decode the video. Try a different recording (H.264 MP4 works best).",
                                "No working decoder for ${info.vcodec}; failed: $bad",
                            )
                        }
                    }
                } finally {
                    active.decrementAndGet()
                    worker.release()
                }
            }))
        }
        pool.shutdown()
        try {
            for (f in futures) {
                try {
                    f.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } catch (e: Throwable) {
            task.cancel() // stop the other decoders too
            pool.shutdownNow()
            for (c in crops) c.third.release()
            throw e
        }
        if (queue.isNotEmpty()) throw FriendlyError("Couldn't read all of the video.", "${queue.size} pieces left")
        val data = VisionData.fromRows(rows.toList())
        AppLog.i(TAG, "Vision scan: ${data.size} frames sampled, ${data.badge.count { it >= 0.5f }} with a badge-like shape")
        return Result(data, crops.toList())
    }

    /** One decoding thread with its own OpenGL context. */
    private class Worker(val context: Context, val info: VideoInfo, val region: List<Double>) {
        private val geo = VisionConst.geometry(info, region)
        private val frameH = geo.cropH + THUMB_H
        private val egl = EglCore()
        private val pbuffer = egl.pbuffer()
        private val renderer: CompositionRenderer
        private val fbo: Fbo
        private val waiter: FrameWaiter
        private val pixels = ByteBuffer.allocateDirect(CROP_W * frameH * 4).order(ByteOrder.nativeOrder())
        private val bytes = ByteArray(CROP_W * frameH * 4)
        private val rgba = Mat(frameH, CROP_W, CvType.CV_8UC4)
        private val bgr = Mat()

        init {
            egl.makeCurrent(pbuffer)
            renderer = CompositionRenderer()
            fbo = Fbo(CROP_W, frameH)
            waiter = FrameWaiter(GlUtil.oesTexture())
        }

        fun scanChunk(start: Double, end: Double, bad: MutableSet<String>, task: Task, onFrame: () -> Unit): ChunkResult {
            while (true) {
                task.check()
                try {
                    return decodeChunk(start, end, bad, task, onFrame)
                } catch (e: DecoderFailed) {
                    AppLog.w(TAG, "Decoder ${e.decoder} failed at %.0fs: ${e.message}; trying another".format(start))
                    e.decoder?.let { bad.add(it) } ?: throw NoDecoder()
                }
            }
        }

        private fun decodeChunk(start: Double, end: Double, bad: Set<String>, task: Task, onFrame: () -> Unit): ChunkResult {
            val ex = Codecs.openExtractor(context, Uri.parse(info.uri))
            var codec: MediaCodec? = null
            val rows = ArrayList<FloatArray>()
            val crops = ArrayList<Triple<Double, Double, Mat>>()
            var name: String? = null
            try {
                val track = Codecs.findTrack(ex, "video/")
                ex.selectTrack(track)
                val format = ex.getTrackFormat(track)
                codec = Codecs.startDecoder(format, waiter.surface, bad) ?: throw NoDecoder()
                name = codec.name
                ex.seekTo((start * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val half = 0.5 / info.fps
                val step = 1.0 / VisionConst.SAMPLE_FPS
                var nextT = start
                val endUs = (end * 1e6).toLong()
                val bi = MediaCodec.BufferInfo()
                var inputDone = false
                val began = System.currentTimeMillis()
                var missed = 0
                loop@ while (true) {
                    if (task.isCancelled) throw Cancelled()
                    if (rows.isEmpty() && System.currentTimeMillis() - began > FIRST_FRAME_TIMEOUT_MS) {
                        throw DecoderFailed("no frames after ${FIRST_FRAME_TIMEOUT_MS / 1000}s", name)
                    }
                    // Feed everything the decoder will take, then handle every frame it has ready.
                    while (!inputDone) {
                        val i = codec.dequeueInputBuffer(0)
                        if (i < 0) break
                        val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0 || ex.sampleTime > endUs + 2_000_000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                    var o = codec.dequeueOutputBuffer(bi, 3000)
                    while (o != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (o >= 0) {
                            val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val pts = bi.presentationTimeUs / 1e6
                            val take = bi.size > 0 && pts >= nextT - half && nextT < end && pts < end + 1.0
                            codec.releaseOutputBuffer(o, take)
                            if (take) {
                                if (waiter.await()) {
                                    val row = analyseFrame()
                                    // ffmpeg's fps filter repeats a frame if the video skipped (variable frame rate).
                                    while (nextT <= pts + half && nextT < end) {
                                        rows.add(floatArrayOf(nextT.toFloat(), row[0], row[1], row[2], row[3], row[4], row[5]))
                                        keepCrop(crops, nextT, row)
                                        nextT += step
                                        onFrame()
                                    }
                                } else {
                                    missed++
                                    nextT += step
                                }
                            }
                            if (eos || pts >= end || nextT >= end) break@loop
                        }
                        o = codec.dequeueOutputBuffer(bi, 0)
                    }
                }
                if (missed > 0) AppLog.w(TAG, "$missed sampled frames were not delivered by the decoder at %.0fs".format(start))
                if (rows.isEmpty() && end - start > 1) throw DecoderFailed("no frames decoded for %.1f-%.1f".format(start, end), name)
                return ChunkResult(rows, crops)
            } catch (e: Cancelled) {
                for (c in crops) c.third.release()
                throw e
            } catch (e: DecoderFailed) {
                for (c in crops) c.third.release()
                throw e
            } catch (e: NoDecoder) {
                throw e
            } catch (e: IllegalStateException) {
                for (c in crops) c.third.release()
                throw DecoderFailed(e.toString(), name)
            } finally {
                Codecs.release(codec)
                ex.release()
                pendingCrop?.release()
                pendingCrop = null
            }
        }

        private var pendingCrop: Mat? = null

        /** Draws the latched frame into the small image and checks it. Returns score, bx, by, sat, val, panel. */
        private fun analyseFrame(): FloatArray {
            fbo.bind()
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            renderer.space(CROP_W, frameH)
            val src = waiter.source()
            val w = info.width.toFloat()
            val h = info.height.toFloat()
            renderer.drawImage(
                src, geo.cx / w, geo.cy / h, (geo.cx + geo.cw) / w, (geo.cy + geo.ch) / h,
                0f, 0f, CROP_W.toFloat(), geo.cropH.toFloat(),
            )
            renderer.drawImage(src, 0f, 0f, 1f, 1f, 0f, geo.cropH.toFloat(), THUMB_W.toFloat(), THUMB_H.toFloat())
            pixels.position(0)
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
            GLES20.glReadPixels(0, 0, CROP_W, frameH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            pixels.position(0)
            pixels.get(bytes)
            rgba.put(0, 0, bytes)
            Core.flip(rgba, rgba, 0) // GL rows are bottom-up
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            val crop = bgr.rowRange(0, geo.cropH)
            val hsv = BadgeDetector.toHsv(crop)
            val found = BadgeDetector.detect(hsv, geo.scale, minOf(info.width, info.height))
            val panel = BadgeDetector.panelFraction(hsv)
            hsv.release()
            val thumb = bgr.submat(Range(geo.cropH, frameH), Range(0, THUMB_W))
            val (sat, value) = BadgeDetector.meanSatVal(thumb)
            thumb.release()
            pendingCrop?.release()
            pendingCrop = null
            if (found.score >= VisionConst.SAVE_SCORE && panel < VisionConst.MENU_PANEL) {
                pendingCrop = Mat().also { Imgproc.resize(crop, it, Size(CROP_W / 2.0, geo.cropH / 2.0), 0.0, 0.0, Imgproc.INTER_NEAREST) }
            }
            crop.release()
            return floatArrayOf(
                found.score.toFloat(),
                if (found.x >= 0) (geo.cx + found.x).toFloat() else -1f,
                if (found.y >= 0) (geo.cy + found.y).toFloat() else -1f,
                sat.toFloat(), value.toFloat(), panel.toFloat(),
            )
        }

        /** Keeps the clearest frame of each badge pop-up for the detections picture. */
        private fun keepCrop(crops: MutableList<Triple<Double, Double, Mat>>, t: Double, row: FloatArray) {
            val small = pendingCrop ?: return
            val score = row[0].toDouble()
            val last = crops.lastOrNull()
            if (last != null && t - last.first <= 2.5) {
                if (score > last.second) {
                    last.third.release()
                    crops[crops.size - 1] = Triple(last.first, score, small.clone())
                }
            } else if (crops.size < 80) {
                crops.add(Triple(t, score, small.clone()))
            }
        }

        fun release() {
            try {
                waiter.release()
                fbo.release()
                renderer.release()
                rgba.release()
                bgr.release()
                egl.releaseSurface(pbuffer)
                egl.release()
            } catch (e: Exception) {
                AppLog.w(TAG, "Cleanup: $e")
            }
        }
    }
}
