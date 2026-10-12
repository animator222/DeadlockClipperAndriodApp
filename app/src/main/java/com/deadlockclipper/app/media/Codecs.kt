package com.deadlockclipper.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.view.Surface
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.FriendlyError

object Codecs {
    private val infos: List<MediaCodecInfo> by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }

    fun isSoftware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly
        val n = info.name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.startsWith("omx.ffmpeg")
    }

    private fun usable(info: MediaCodecInfo) =
        !info.name.endsWith(".secure") && (Build.VERSION.SDK_INT < 29 || !info.isAlias)

    /** Decoders for this format, graphics hardware first (like the PC app's GPU decoding). */
    fun decoders(mime: String): List<MediaCodecInfo> =
        infos.filter { !it.isEncoder && usable(it) && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .sortedBy { if (isSoftware(it)) 1 else 0 }

    fun encoders(mime: String): List<MediaCodecInfo> =
        infos.filter { it.isEncoder && usable(it) && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .sortedBy { if (isSoftware(it)) 1 else 0 }

    /**
     * Starts the first decoder that accepts this format. [skip] holds decoders that already failed
     * on this video. Returns null if none works.
     */
    fun startDecoder(format: MediaFormat, surface: Surface?, skip: Set<String> = emptySet(), fast: Boolean = true): MediaCodec? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        for (info in decoders(mime)) {
            if (info.name in skip) continue
            for (tuned in if (fast) listOf(true, false) else listOf(false)) {
                var codec: MediaCodec? = null
                try {
                    codec = MediaCodec.createByCodecName(info.name)
                    val f = MediaFormat(format)
                    if (tuned && Build.VERSION.SDK_INT >= 23) {
                        // Not real-time: decode as fast as possible.
                        f.setInteger(MediaFormat.KEY_PRIORITY, 1)
                    }
                    codec.configure(f, surface, null, 0)
                    codec.start()
                    return codec
                } catch (e: Exception) {
                    AppLog.w("codec", "Decoder ${info.name} (tuned=$tuned) failed to start: $e")
                    try {
                        codec?.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return null
    }

    fun openExtractor(context: Context, uri: Uri): MediaExtractor {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            ex.release()
            throw FriendlyError(
                "This file couldn't be opened as a video. It may be damaged, still being recorded, " +
                    "or in a format Android can't read (MP4 and MKV work best).",
                e.toString(),
            )
        }
        return ex
    }

    fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    fun release(codec: MediaCodec?) {
        if (codec == null) return
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
    }
}
