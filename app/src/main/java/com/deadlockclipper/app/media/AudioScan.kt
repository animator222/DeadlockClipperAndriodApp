package com.deadlockclipper.app.media

import android.content.Context
import android.net.Uri
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.Cancelled
import com.deadlockclipper.app.util.Task
import com.deadlockclipper.core.Loudness
import com.deadlockclipper.core.VideoInfo
import kotlin.math.log10
import kotlin.math.sqrt

/** Game-audio loudness scan. Fast: only the audio is decoded, never the video. */
object AudioScan {
    /** RMS loudness in dB, one value per HOP seconds. */
    fun loudnessCurve(context: Context, info: VideoInfo, task: Task): FloatArray {
        val flat = { FloatArray((info.duration / Loudness.HOP).toInt() + 1) { -90f } }
        if (!info.hasAudio) {
            AppLog.w("audio", "Video has no audio track; audio scoring disabled")
            return flat()
        }
        val values = ArrayList<Float>((info.duration / Loudness.HOP).toInt() + 16)
        var hp: Biquad? = null
        var lp: Biquad? = null
        var hop = 0
        var acc = 0.0
        var inHop = 0
        val totalHops = maxOf(1, (info.duration / Loudness.HOP).toInt())
        try {
            AudioDecode.decode(context, Uri.parse(info.uri), 0, Long.MAX_VALUE, task) { s, n, ch, rate, _ ->
                if (hp == null) {
                    // ffmpeg: highpass=f=150 to remove rumble/music, then 16 kHz (which drops
                    // everything above 8 kHz - the low-pass does the same here).
                    hp = Biquad("highpass", Loudness.HIGHPASS_HZ, rate)
                    lp = Biquad("lowpass", 7600.0, rate)
                    hop = (rate * Loudness.HOP).toInt()
                }
                val h = hp!!
                val l = lp!!
                var k = 0
                while (k + ch <= n) {
                    var mono = 0.0
                    for (c in 0 until ch) mono += s[k + c]
                    mono /= ch
                    val v = l.step(h.step(mono / 32768.0))
                    acc += v * v
                    k += ch
                    if (++inHop == hop) {
                        values.add((20 * log10(sqrt(acc / hop) + 1e-6)).toFloat())
                        acc = 0.0
                        inHop = 0
                        if (values.size % 600 == 0) task.progress(values.size.toDouble() / totalHops, "Listening to the game audio")
                    }
                }
            }
        } catch (e: Cancelled) {
            throw e
        } catch (e: Exception) {
            AppLog.e("audio", "Couldn't read the audio; fights won't be scored by loudness", e)
            if (values.isEmpty()) return flat()
        }
        if (values.isEmpty()) {
            AppLog.w("audio", "No audio decoded; audio scoring disabled")
            return flat()
        }
        val curve = values.toFloatArray()
        val sorted = curve.copyOf().also { it.sort() }
        AppLog.i("audio", "Audio scan done: %d values, median %.1f dB, max %.1f dB".format(curve.size, sorted[sorted.size / 2], sorted.last()))
        return curve
    }
}
