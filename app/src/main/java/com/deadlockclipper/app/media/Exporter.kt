package com.deadlockclipper.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES20
import com.deadlockclipper.app.gl.CompositionRenderer
import com.deadlockclipper.app.gl.EglCore
import com.deadlockclipper.app.gl.Fbo
import com.deadlockclipper.app.gl.FrameWaiter
import com.deadlockclipper.app.gl.GlUtil
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.Cancelled
import com.deadlockclipper.app.util.FriendlyError
import com.deadlockclipper.app.util.Paths
import com.deadlockclipper.app.util.Task
import com.deadlockclipper.core.Clip
import com.deadlockclipper.core.Comp
import com.deadlockclipper.core.Composition
import com.deadlockclipper.core.Naming
import com.deadlockclipper.core.Settings
import com.deadlockclipper.core.VideoInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders clips: decode -> draw the layout with OpenGL -> H.264 + AAC -> MP4 in the gallery. */
object Exporter {
    private const val TAG = "export"
    private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC

    class Saved(val name: String, val uri: Uri)

    class Result(val files: List<Saved>, val folder: String, val notes: List<String>)

    private class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private class AudioTrack(val format: MediaFormat, val samples: List<EncodedSample>)

    /** An encoder setup: which encoder, the size it encodes and the rotation stored in the file. */
    private class Plan(val encoder: MediaCodecInfo, val width: Int, val height: Int, val rotation: Int)

    private class EncoderFailed(msg: String) : Exception(msg)

    fun exportClips(context: Context, paths: Paths, info: VideoInfo, clips: List<Clip>, settings: Settings, task: Task): Result {
        val folder = settings.outputDir
        val date = Naming.recordingDate(info.name, info.lastModified)
        val names = Naming.outputNames(date, Gallery.existingNames(context, folder), clips.size)
        AppLog.i(TAG, "Exporting ${clips.size} clips to ${Gallery.describe(folder)}")
        val saved = ArrayList<Saved>()
        val notes = ArrayList<String>()
        val badEncoders = HashSet<String>()
        val badDecoders = HashSet<String>()
        val egl = EglCore()
        val pbuffer = egl.pbuffer()
        egl.makeCurrent(pbuffer)
        val renderer = CompositionRenderer()
        val waiter = FrameWaiter(GlUtil.oesTexture())
        try {
            for ((i, clip) in clips.withIndex()) {
                val clipTask = task.sub(i.toDouble() / clips.size, (i + 1.0) / clips.size)
                val label = "Exporting clip ${i + 1} of ${clips.size}"
                clipTask.progress(0.0, label)
                val comp = Composition.forClip(clip, settings, info)
                AppLog.i(TAG, "Clip ${i + 1}: %.2f-%.2f, layout $comp".format(clip.start, clip.end))
                val tmp = File(paths.tmp, "export_${i}.mp4")
                val audio = if (info.hasAudio) {
                    try {
                        encodeAudio(context, info, clip.start, clip.end, clipTask)
                    } catch (e: Cancelled) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Audio for clip ${i + 1} failed; exporting it without sound", e)
                        notes.add("Clip ${i + 1} has no sound (the audio couldn't be converted).")
                        null
                    }
                } else null
                var done = false
                var lastError = ""
                for (plan in plans(comp)) {
                    if (plan.encoder.name in badEncoders) continue
                    try {
                        encodeVideo(context, info, clip, comp, plan, audio, tmp, egl, pbuffer, renderer, waiter, badDecoders, clipTask, label)
                        AppLog.i(TAG, "Wrote clip ${i + 1} with ${plan.encoder.name} ${plan.width}x${plan.height} rotation ${plan.rotation}")
                        done = true
                        break
                    } catch (e: EncoderFailed) {
                        lastError = e.message ?: ""
                        AppLog.w(TAG, "Encoder ${plan.encoder.name} ${plan.width}x${plan.height} failed: $lastError")
                        badEncoders.add(plan.encoder.name)
                        tmp.delete()
                    }
                }
                if (!done) throw FriendlyError("Couldn't export clip ${i + 1}. See the log for details.", lastError)
                task.check()
                clipTask.progress(1.0, "Saving clip ${i + 1}")
                saved.add(Saved(names[i], Gallery.save(context, tmp, folder, names[i])))
                tmp.delete()
            }
        } finally {
            waiter.release()
            renderer.release()
            egl.releaseSurface(pbuffer)
            egl.release()
            paths.tmp.listFiles { f -> f.name.startsWith("export_") }?.forEach { it.delete() }
        }
        task.progress(1.0, "Done")
        return Result(saved, Gallery.describe(folder), notes)
    }

    /** Encoders and sizes to try, best first. */
    private fun plans(comp: Comp): List<Plan> {
        val (cw, ch) = comp.size
        val encoders = Codecs.encoders(VIDEO_MIME)
        if (encoders.isEmpty()) throw FriendlyError("This phone has no H.264 video encoder.")
        val sizes = ArrayList<Triple<Int, Int, Int>>()
        sizes.add(Triple(cw, ch, 0))
        if (ch > cw) sizes.add(Triple(ch, cw, 90)) // some encoders only take landscape sizes
        val sw = if (cw >= ch) 1280 else 720
        val sh = if (cw >= ch) 720 else 1280
        sizes.add(Triple(sw, sh, 0)) // older phones: 720p
        if (sh > sw) sizes.add(Triple(sh, sw, 90))
        val out = ArrayList<Plan>()
        for ((w, h, rot) in sizes) {
            for (e in encoders) {
                val caps = e.getCapabilitiesForType(VIDEO_MIME).videoCapabilities
                if (caps == null || caps.isSizeSupported(w, h)) out.add(Plan(e, w, h, rot))
            }
        }
        if (out.isEmpty()) out.add(Plan(encoders.first(), sw, sh, 0))
        return out
    }

    // ------------------------------------------------------------------ audio

    /** The clip's sound as AAC (stereo, 192 kb/s), kept in memory and mixed in while writing the video. */
    private fun encodeAudio(context: Context, info: VideoInfo, start: Double, end: Double, task: Task): AudioTrack? {
        val startUs = (start * 1e6).toLong()
        val endUs = (end * 1e6).toLong()
        var encoder: MediaCodec? = null
        val samples = ArrayList<EncodedSample>()
        var outFormat: MediaFormat? = null
        var outRate = 0
        var resample: Resampler? = null
        var written = 0L // stereo frames given to the encoder
        val bi = MediaCodec.BufferInfo()
        val pending = ShortArray(2 * 8192)
        var pendingCount = 0

        fun drain(wait: Boolean) {
            val enc = encoder ?: return
            val deadline = System.currentTimeMillis() + 10_000
            while (true) {
                val o = enc.dequeueOutputBuffer(bi, if (wait) 10_000 else 0)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (wait && System.currentTimeMillis() < deadline) continue else return
                }
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = enc.outputFormat; continue
                }
                if (o < 0) continue
                val buf = enc.getOutputBuffer(o)!!
                if (bi.size > 0 && bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val data = ByteArray(bi.size)
                    buf.position(bi.offset)
                    buf.get(data)
                    samples.add(EncodedSample(data, bi.presentationTimeUs, bi.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME))
                }
                enc.releaseOutputBuffer(o, false)
                if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }

        fun feed(eos: Boolean) {
            val enc = encoder ?: return
            var offset = 0
            while (offset < pendingCount || eos) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i < 0) {
                    drain(false); continue
                }
                val buf = enc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                buf.clear()
                val n = minOf(pendingCount - offset, buf.capacity() / 2) and 1.inv() // whole stereo frames
                buf.asShortBuffer().put(pending, offset, n)
                val pts = written * 1_000_000L / outRate
                written += n / 2
                offset += n
                val last = eos && offset >= pendingCount
                enc.queueInputBuffer(i, 0, n * 2, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                if (last) break
            }
            pendingCount = 0
        }

        fun push(l: Short, r: Short) {
            pending[pendingCount++] = l
            pending[pendingCount++] = r
            if (pendingCount == pending.size) feed(false)
        }

        try {
            AudioDecode.decode(context, Uri.parse(info.uri), startUs, endUs, task) { s, n, ch, rate, ptsUs ->
                if (encoder == null) {
                    outRate = if (rate in setOf(48000, 44100, 32000, 24000, 22050, 16000)) rate else 48000
                    if (outRate != rate) resample = Resampler(rate, outRate)
                    val f = MediaFormat.createAudioFormat(AUDIO_MIME, outRate, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                    }
                    encoder = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                        configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
                }
                val frames = n / ch
                for (k in 0 until frames) {
                    val tUs = ptsUs + k * 1_000_000L / rate
                    if (tUs < startUs || tUs >= endUs) continue
                    val l = s[k * ch]
                    val r = if (ch >= 2) s[k * ch + 1] else l
                    val rs = resample
                    if (rs == null) push(l, r) else rs.push(l, r) { a, b -> push(a, b) }
                }
                task.check()
            }
            if (encoder == null) return null
            feed(true)
            drain(true)
        } finally {
            Codecs.release(encoder)
        }
        val format = outFormat ?: return null
        AppLog.i(TAG, "Audio: ${samples.size} AAC frames at $outRate Hz")
        return AudioTrack(format, samples)
    }

    /** Linear resampling for unusual sample rates (stereo). */
    private class Resampler(private val from: Int, private val to: Int) {
        private var pos = 0.0
        private var prevL = 0
        private var prevR = 0
        private var have = false

        fun push(l: Short, r: Short, out: (Short, Short) -> Unit) {
            if (!have) {
                prevL = l.toInt(); prevR = r.toInt(); have = true
                return
            }
            val step = from.toDouble() / to
            while (pos < 1.0) {
                out((prevL + (l - prevL) * pos).toInt().toShort(), (prevR + (r - prevR) * pos).toInt().toShort())
                pos += step
            }
            pos -= 1.0
            prevL = l.toInt(); prevR = r.toInt()
        }
    }

    // ------------------------------------------------------------------ video

    private fun videoFormat(plan: Plan, fps: Double, withProfile: Boolean): MediaFormat {
        val f = MediaFormat.createVideoFormat(VIDEO_MIME, plan.width, plan.height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        val caps = plan.encoder.getCapabilitiesForType(VIDEO_MIME)
        var bitrate = (16_000_000.0 * plan.width * plan.height / (1080.0 * 1920.0) * (if (fps > 40) 1.0 else 0.75)).toInt()
        caps.videoCapabilities?.bitrateRange?.let { bitrate = bitrate.coerceIn(it.lower, it.upper) }
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, Math.round(fps).toInt().coerceIn(1, 60))
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (withProfile) {
            val high = caps.profileLevels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }.maxByOrNull { it.level }
            if (high != null) {
                f.setInteger(MediaFormat.KEY_PROFILE, high.profile)
                f.setInteger(MediaFormat.KEY_LEVEL, high.level)
            }
        }
        return f
    }

    private fun startEncoder(plan: Plan, fps: Double): MediaCodec {
        for (withProfile in listOf(true, false)) {
            var enc: MediaCodec? = null
            try {
                enc = MediaCodec.createByCodecName(plan.encoder.name)
                enc.configure(videoFormat(plan, fps, withProfile), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return enc
            } catch (e: Exception) {
                AppLog.w(TAG, "Encoder ${plan.encoder.name} profile=$withProfile: $e")
                Codecs.release(enc)
            }
        }
        throw EncoderFailed("could not configure ${plan.encoder.name} at ${plan.width}x${plan.height}")
    }

    private fun encodeVideo(
        context: Context, info: VideoInfo, clip: Clip, comp: Comp, plan: Plan, audio: AudioTrack?, out: File,
        egl: EglCore, pbuffer: EGLSurface, renderer: CompositionRenderer, waiter: FrameWaiter,
        badDecoders: MutableSet<String>, task: Task, label: String,
    ) {
        val start = clip.start
        val end = clip.end
        val duration = end - start
        val fps = info.fps
        val encoder = startEncoder(plan, fps)
        val inputSurface = try {
            encoder.createInputSurface()
        } catch (e: Exception) {
            Codecs.release(encoder)
            throw EncoderFailed("createInputSurface: $e")
        }
        var eglSurface: EGLSurface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrack = -1
        var audioTrackIdx = -1
        var audioNext = 0
        var rotateFbo: Fbo? = null
        var framesOut = 0
        val bi = MediaCodec.BufferInfo()
        try {
            try {
                encoder.start()
                eglSurface = egl.windowSurface(inputSurface)
            } catch (e: Exception) {
                throw EncoderFailed("start: $e")
            }
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (plan.rotation != 0) muxer.setOrientationHint(plan.rotation)

            fun writeAudioUpTo(ptsUs: Long) {
                if (audio == null || audioTrackIdx < 0) return
                val abi = MediaCodec.BufferInfo()
                while (audioNext < audio.samples.size && audio.samples[audioNext].ptsUs <= ptsUs) {
                    val s = audio.samples[audioNext++]
                    abi.set(0, s.data.size, s.ptsUs, s.flags)
                    muxer.writeSampleData(audioTrackIdx, ByteBuffer.wrap(s.data), abi)
                }
            }

            fun drain(endOfStream: Boolean) {
                val deadline = System.currentTimeMillis() + 10_000
                while (true) {
                    val o = encoder.dequeueOutputBuffer(bi, if (endOfStream) 10_000 else 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) return
                        if (System.currentTimeMillis() > deadline) {
                            AppLog.w(TAG, "Encoder didn't finish in time; closing the file")
                            return
                        }
                        continue
                    }
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audio != null && audio.samples.isNotEmpty()) audioTrackIdx = muxer.addTrack(audio.format)
                        muxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (o < 0) continue
                    val buf = encoder.getOutputBuffer(o)!!
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                    if (bi.size > 0 && muxerStarted) {
                        buf.position(bi.offset)
                        buf.limit(bi.offset + bi.size)
                        muxer.writeSampleData(videoTrack, buf, bi)
                        writeAudioUpTo(bi.presentationTimeUs)
                    }
                    encoder.releaseOutputBuffer(o, false)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }

            val (outW, outH) = if (plan.rotation == 0) plan.width to plan.height else plan.height to plan.width
            if (plan.rotation != 0) rotateFbo = Fbo(outW, outH)
            egl.makeCurrent(eglSurface!!)

            fun drawFrame() {
                val src = waiter.source()
                val target = rotateFbo
                val restore = {
                    if (target != null) target.bind() else {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        GLES20.glViewport(0, 0, outW, outH)
                    }
                }
                restore()
                renderer.drawComp(src, comp, info, outW, outH, restore)
                if (target != null) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, plan.width, plan.height)
                    renderer.space(plan.width, plan.height)
                    renderer.drawRotated90(target.texture, 0f, 0f, plan.width.toFloat(), plan.height.toFloat())
                }
            }

            // Decode the source from the key frame before the start; draw each frame of the clip.
            while (true) {
                try {
                    framesOut = decodeInto(context, info, start, end, fps, waiter, badDecoders, task) { ptsSec ->
                        drawFrame()
                        egl.presentationTime(eglSurface, ((ptsSec - start).coerceAtLeast(0.0) * 1e9).toLong())
                        egl.swap(eglSurface)
                        drain(false)
                        task.progress((ptsSec - start) / duration, label)
                    }
                    break
                } catch (e: DecodeRetry) {
                    if (framesOut > 0 || e.frames > 0) throw FriendlyError("The video decoder stopped in the middle of clip. Try exporting again.", e.message ?: "")
                    AppLog.w(TAG, "Decoder failed (${e.message}); trying another")
                }
            }
            if (framesOut == 0) throw FriendlyError("No video frames were found in this part of the video.", "%.2f-%.2f".format(start, end))
            encoder.signalEndOfInputStream()
            drain(true)
            if (!muxerStarted) throw EncoderFailed("encoder produced no output")
            writeAudioUpTo(Long.MAX_VALUE)
        } catch (e: IllegalStateException) {
            // MediaCodec.CodecException and friends: this encoder doesn't work here.
            if (e is MediaCodec.CodecException || e.message?.contains("codec", true) == true) throw EncoderFailed(e.toString())
            throw e
        } finally {
            egl.makeCurrent(pbuffer)
            rotateFbo?.release()
            eglSurface?.let { egl.releaseSurface(it) }
            Codecs.release(encoder)
            inputSurface.release()
            try {
                if (muxerStarted) muxer?.stop()
            } catch (e: Exception) {
                AppLog.w(TAG, "muxer.stop: $e")
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
        AppLog.i(TAG, "$framesOut frames written")
    }

    private class DecodeRetry(msg: String, val frames: Int) : Exception(msg)

    /** Decodes [start, end) at up to [fps] and calls [onFrame] (with the frame latched in the waiter). */
    private fun decodeInto(
        context: Context, info: VideoInfo, start: Double, end: Double, fps: Double, waiter: FrameWaiter,
        bad: MutableSet<String>, task: Task, onFrame: (Double) -> Unit,
    ): Int {
        val ex = Codecs.openExtractor(context, Uri.parse(info.uri))
        var codec: MediaCodec? = null
        var frames = 0
        try {
            val track = Codecs.findTrack(ex, "video/")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            codec = Codecs.startDecoder(format, waiter.surface, bad)
                ?: throw FriendlyError("This phone couldn't decode the video for exporting.", "decoders failed: $bad")
            val name = codec.name
            ex.seekTo((start * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = (end * 1e6).toLong()
            val tol = 0.3 / fps
            var nextOut = start
            var inputDone = false
            val bi = MediaCodec.BufferInfo()
            val began = System.currentTimeMillis()
            while (true) {
                task.check()
                if (frames == 0 && System.currentTimeMillis() - began > 60_000) {
                    bad.add(name); throw DecodeRetry("no frames after 60s from $name", 0)
                }
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(2000)
                    if (i >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0 || ex.sampleTime > endUs + 2_000_000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = try {
                    codec.dequeueOutputBuffer(bi, 5000)
                } catch (e: IllegalStateException) {
                    bad.add(name); throw DecodeRetry(e.toString(), frames)
                }
                if (o < 0) continue
                val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = bi.presentationTimeUs / 1e6
                // The clip's frames, at most [fps] per second (sources above 60 fps are thinned out).
                val take = bi.size > 0 && pts >= start - 1e-4 && pts < end && pts >= nextOut - tol
                codec.releaseOutputBuffer(o, take)
                if (take && waiter.await()) {
                    onFrame(pts)
                    frames++
                    nextOut = pts + 1.0 / fps
                }
                if (eos || pts >= end) break
            }
            return frames
        } finally {
            Codecs.release(codec)
            ex.release()
        }
    }
}
