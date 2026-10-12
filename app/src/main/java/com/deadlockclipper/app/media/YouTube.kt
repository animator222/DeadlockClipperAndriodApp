package com.deadlockclipper.app.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import com.deadlockclipper.app.BuildConfig
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.Cancelled
import com.deadlockclipper.app.util.FriendlyError
import com.deadlockclipper.app.util.Task
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** YouTube downloads in the best quality the phone can use (H.264 video + AAC audio, joined into an MP4). */
object YouTube {
    private const val TAG = "youtube"
    private const val FAILED = "YouTube download failed — try the local file instead."
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val CHUNK = 10L * 1024 * 1024 // YouTube slows down big single requests

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private object Net : Downloader() {
        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder().url(request.url()).header("User-Agent", UA)
            for ((k, values) in request.headers()) {
                builder.removeHeader(k)
                for (v in values) builder.addHeader(k, v)
            }
            val body = request.dataToSend()?.toRequestBody()
            builder.method(request.httpMethod(), body ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null)
            http.newCall(builder.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    @Volatile
    private var initialised = false

    private fun init() {
        if (!initialised) {
            NewPipe.init(Net)
            initialised = true
        }
    }

    fun isYouTubeLink(text: String) = Regex("(youtube\\.com|youtu\\.be)/", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Downloads the video; returns the file. */
    fun download(url: String, folder: File, task: Task): File {
        init()
        AppLog.i(TAG, "Downloading $url (extractor ${BuildConfig.NEWPIPE_VERSION})")
        try {
            task.progress(0.0, "Looking up the video")
            val ex = ServiceList.YouTube.getStreamExtractor(url.trim())
            ex.fetchPage()
            val title = ex.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").replace(Regex("_+"), "_").take(80).trim()
            val base = "$title [${ex.id}]"
            folder.mkdirs()
            File(folder, "$base.mp4").takeIf { it.length() > 0 }?.let {
                AppLog.i(TAG, "Already downloaded: $it")
                return it
            }

            val video = pickVideo(ex.videoOnlyStreams)
            val audio = pickAudio(ex.audioStreams, video?.format)
            if (video != null && audio != null) {
                val webm = video.format == MediaFormat.WEBM
                AppLog.i(TAG, "Video ${video.resolution} ${video.codec}, audio ${audio.format} ${audio.averageBitrate} kb/s")
                val vFile = File(folder, "$base.video")
                val aFile = File(folder, "$base.audio")
                val out = File(folder, "$base." + if (webm) "webm" else "mp4")
                val vSize = sizeOf(video.content)
                val aSize = sizeOf(audio.content)
                val total = (vSize + aSize).coerceAtLeast(1).toDouble()
                fetch(video.content, vFile, vSize, task) { done -> task.progress(0.99 * done / total, "Downloading from YouTube") }
                fetch(audio.content, aFile, aSize, task) { done -> task.progress(0.99 * (vSize + done) / total, "Downloading from YouTube") }
                task.progress(0.99, "Joining video and audio")
                mux(vFile, aFile, out, webm)
                vFile.delete()
                aFile.delete()
                AppLog.i(TAG, "Downloaded to $out")
                return out
            }
            // Fallback: a stream with video and sound together (lower quality).
            val muxed = ex.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 }
                .maxByOrNull { it.height }
                ?: throw FriendlyError(FAILED, "No downloadable stream found")
            val out = File(folder, "$base.mp4")
            val size = sizeOf(muxed.content)
            fetch(muxed.content, out, size, task) { done -> task.progress(0.99 * done / size.coerceAtLeast(1), "Downloading from YouTube") }
            return out
        } catch (e: Cancelled) {
            throw e
        } catch (e: FriendlyError) {
            throw e
        } catch (e: org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException) {
            AppLog.e(TAG, "YouTube refused the request", e)
            throw FriendlyError(
                "YouTube refused the download (${e.message}). YouTube changes often; install the newest " +
                    "version of the app and try again. Private, members-only and age-restricted videos can't be downloaded.",
                AppLog.stackTrace(e),
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "YouTube download failed", e)
            throw FriendlyError(FAILED, AppLog.stackTrace(e))
        }
    }

    /** Highest resolution and frame rate the phone can decode; H.264 first (every phone decodes it). */
    private fun pickVideo(streams: List<VideoStream>): VideoStream? {
        val usable = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val avc = usable.filter { it.format == MediaFormat.MPEG_4 && (it.codec ?: "").startsWith("avc1") }
        val vp9 = usable.filter { it.format == MediaFormat.WEBM && (it.codec ?: "").startsWith("vp9") && Codecs.decoders("video/x-vnd.on2.vp9").isNotEmpty() }
        val best = { list: List<VideoStream> -> list.maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { it.fps }.thenBy { it.bitrate }) }
        val a = best(avc)
        val v = best(vp9)
        return if (v != null && (a == null || v.height > a.height)) v else a ?: v
    }

    private fun pickAudio(streams: List<AudioStream>, videoFormat: MediaFormat?): AudioStream? {
        val want = if (videoFormat == MediaFormat.WEBM) MediaFormat.WEBMA_OPUS else MediaFormat.M4A
        val usable = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == want }
        // Prefer the original language track when a video has several.
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType.toString() == "ORIGINAL" }
        return (original.ifEmpty { usable }).maxByOrNull { it.averageBitrate }
    }

    private fun sizeOf(url: String): Long {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").build()
        http.newCall(req).execute().use { r ->
            val range = r.header("Content-Range") ?: return r.header("Content-Length")?.toLongOrNull() ?: -1
            return range.substringAfter('/').toLongOrNull() ?: -1
        }
    }

    private fun fetch(url: String, out: File, size: Long, task: Task, progress: (Long) -> Unit) {
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            var pos = 0L
            val buf = ByteArray(256 * 1024)
            while (size < 0 || pos < size) {
                task.check()
                val endByte = if (size < 0) pos + CHUNK - 1 else minOf(size - 1, pos + CHUNK - 1)
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=$pos-$endByte").build()
                var got = 0L
                var attempt = 0
                while (true) {
                    try {
                        http.newCall(req).execute().use { r ->
                            if (r.code == 416) return // past the end (unknown size)
                            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                            val body = r.body ?: throw java.io.IOException("empty body")
                            body.byteStream().use { input ->
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    raf.write(buf, 0, n)
                                    got += n
                                    progress(pos + got)
                                    task.check()
                                }
                            }
                        }
                        break
                    } catch (e: java.io.IOException) {
                        if (++attempt > 4) throw e
                        AppLog.w(TAG, "Download hiccup at $pos (attempt $attempt): $e")
                        raf.seek(pos)
                        got = 0
                        Thread.sleep(1000L shl attempt)
                    }
                }
                if (got == 0L) break
                pos += got
                if (size < 0 && got < CHUNK) break
            }
        }
    }

    /** Joins the separate video and audio downloads into one file (no re-encoding). */
    private fun mux(videoFile: File, audioFile: File, out: File, webm: Boolean) {
        val muxer = MediaMuxer(out.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = listOf(videoFile, audioFile).map { f -> MediaExtractor().apply { setDataSource(f.path) } }
        try {
            val tracks = sources.map { ex ->
                ex.selectTrack(0)
                muxer.addTrack(ex.getTrackFormat(0))
            }
            muxer.start()
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            // Interleave by time so the file plays smoothly.
            val done = BooleanArray(2)
            while (!done.all { it }) {
                val k = (0..1).filter { !done[it] }.minByOrNull { sources[it].sampleTime }!!
                val ex = sources[k]
                val n = ex.readSampleData(buf, 0)
                if (n < 0) {
                    done[k] = true
                    continue
                }
                info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(tracks[k], buf, info)
                ex.advance()
            }
            muxer.stop()
        } catch (e: Exception) {
            out.delete()
            throw FriendlyError(FAILED, "Joining video and audio failed: ${AppLog.stackTrace(e)}")
        } finally {
            try {
                muxer.release()
            } catch (_: Exception) {
            }
            sources.forEach { it.release() }
        }
    }
}
