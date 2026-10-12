package com.deadlockclipper.app.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.deadlockclipper.app.util.Task
import java.nio.ByteOrder

/** Decodes a video's first audio track to 16-bit PCM. */
object AudioDecode {
    /**
     * Calls [onPcm] with interleaved samples (count = number of shorts used), the channel count,
     * the sample rate and the time (µs) of the first sample. Decoding starts at the key frame
     * before [startUs] (so the first buffers can be earlier) and stops after [endUs].
     */
    fun decode(
        context: Context, uri: Uri, startUs: Long, endUs: Long, task: Task,
        onPcm: (samples: ShortArray, count: Int, channels: Int, rate: Int, ptsUs: Long) -> Unit,
    ) {
        val ex = Codecs.openExtractor(context, uri)
        var codec: MediaCodec? = null
        try {
            val track = Codecs.findTrack(ex, "audio/")
            require(track >= 0) { "no audio track" }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = Codecs.startDecoder(format, null, fast = false) ?: throw IllegalStateException("no audio decoder for ${format.getString(MediaFormat.KEY_MIME)}")
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var buf = ShortArray(8192)
            var idle = 0
            while (true) {
                task.check()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val input = codec.getInputBuffer(i)!!
                        val size = ex.readSampleData(input, 0)
                        if (size < 0 || ex.sampleTime > endUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idle > 600) break // decoder never signalled the end
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val out = codec.getOutputBuffer(o)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val ordered = out.order(ByteOrder.nativeOrder())
                            val n: Int
                            if (floatPcm) {
                                val fb = ordered.asFloatBuffer()
                                n = fb.remaining()
                                if (buf.size < n) buf = ShortArray(n)
                                for (k in 0 until n) buf[k] = (fb.get(k).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            } else {
                                val sb = ordered.asShortBuffer()
                                n = sb.remaining()
                                if (buf.size < n) buf = ShortArray(n)
                                sb.get(buf, 0, n)
                            }
                            onPcm(buf, n, channels, rate, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        if (info.presentationTimeUs > endUs + 500_000) break
                    }
                }
            }
        } finally {
            Codecs.release(codec)
            ex.release()
        }
    }
}

/** A 2nd-order (biquad) filter, like ffmpeg's highpass/lowpass with the default Q. */
class Biquad(type: String, freq: Double, rate: Int, q: Double = 0.7071) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val w0 = 2 * Math.PI * freq.coerceAtMost(rate * 0.45) / rate
        val alpha = Math.sin(w0) / (2 * q)
        val cos = Math.cos(w0)
        val a0 = 1 + alpha
        if (type == "highpass") {
            b0 = (1 + cos) / 2 / a0; b1 = -(1 + cos) / a0; b2 = (1 + cos) / 2 / a0
        } else {
            b0 = (1 - cos) / 2 / a0; b1 = (1 - cos) / a0; b2 = (1 - cos) / 2 / a0
        }
        a1 = -2 * cos / a0
        a2 = (1 - alpha) / a0
    }

    fun step(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}
