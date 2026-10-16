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
    private const val CHUNK = 8L * 1024 * 1024 // YouTube slows down big single requests

    private val http = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
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

    /** Downloads the video; returns the file. [maxHeight] 0 = best quality, else e.g. 720. */
    fun download(url: String, folder: File, maxHeight: Int, task: Task): File {
        init()
        AppLog.i(TAG, "Downloading $url (extractor ${BuildConfig.NEWPIPE_VERSION})")
        try {
            task.progress(0.0, "Looking up the video")
            val ex = ServiceList.YouTube.getStreamExtractor(url.trim())
            ex.fetchPage()
            val title = ex.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").replace(Regex("_+"), "_").take(80).trim()
            val base = "$title [${ex.id}]" + if (maxHeight > 0) " ${maxHeight}p" else ""
            folder.mkdirs()
            File(folder, "$base.mp4").takeIf { it.length() > 0 }?.let {
                AppLog.i(TAG, "Already downloaded: $it")
                return it
            }

            val video = pickVideo(ex.videoOnlyStreams, maxHeight)
            val audio = pickAudio(ex.audioStreams, video?.format)
            if (video != null && audio != null) {
                val webm = video.format == MediaFormat.WEBM
                AppLog.i(TAG, "Video ${video.resolution} ${video.codec}, audio ${audio.format} ${audio.averageBitrate} kb/s")
                val vFile = File(folder, "$base.video")
                val aFile = File(folder, "$base.audio")
                val out = File(folder, "$base." + if (webm) "webm" else "mp4")
                val vSrc = Link(video.content) { freshUrl(url, video.itag, true) }
                val aSrc = Link(audio.content) { freshUrl(url, audio.itag, false) }
                val vSize = sizeOf(vSrc)
                val aSize = sizeOf(aSrc)
                val total = (vSize + aSize).coerceAtLeast(1).toDouble()
                // Video and audio at the same time, each in several pieces at once.
                val vDone = java.util.concurrent.atomic.AtomicLong()
                val aDone = java.util.concurrent.atomic.AtomicLong()
                val started = System.currentTimeMillis()
                val report = {
                    val got = vDone.get() + aDone.get()
                    val secs = (System.currentTimeMillis() - started) / 1000.0
                    val speed = if (secs > 1) got / secs / 1_048_576 else 0.0
                    task.progress(0.99 * got / total, "Downloading from YouTube: %d of %d MB (%.1f MB/s)".format(got / 1_048_576, (vSize + aSize) / 1_048_576, speed))
                }
                val audioError = arrayOfNulls<Throwable>(1)
                val audioThread = Thread {
                    try {
                        fetch(aSrc, aFile, aSize, task, 2) { aDone.set(it); report() }
                    } catch (e: Throwable) {
                        audioError[0] = e
                        task.cancel()
                    }
                }.apply { start() }
                try {
                    fetch(vSrc, vFile, vSize, task, VIDEO_CONNECTIONS) { vDone.set(it); report() }
                } catch (e: Cancelled) {
                    audioThread.join()
                    audioError[0]?.let { throw it } // the audio failed and stopped the video download
                    throw e
                } catch (e: Throwable) {
                    audioThread.interrupt()
                    task.cancel()
                    audioThread.join()
                    throw e
                }
                audioThread.join()
                audioError[0]?.let { throw it }
                AppLog.i(TAG, "Downloaded %d MB in %.0fs".format((vSize + aSize) / 1_048_576, (System.currentTimeMillis() - started) / 1000.0))
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
            val src = Link(muxed.content) { freshUrl(url, muxed.itag, null) }
            val size = sizeOf(src)
            fetch(src, out, size, task, VIDEO_CONNECTIONS) { done -> task.progress(0.99 * done / size.coerceAtLeast(1), "Downloading from YouTube") }
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
    private fun pickVideo(streams: List<VideoStream>, maxHeight: Int): VideoStream? {
        val all = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        // Respect the chosen quality limit (by the short side, so vertical videos work too).
        val limited = if (maxHeight > 0) all.filter { minOf(it.height, if (it.width > 0) it.width else it.height) <= maxHeight } else all
        val usable = limited.ifEmpty { all }
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

    /** A stream link that can be replaced by a fresh one when YouTube stops accepting it. */
    private class Link(@Volatile var url: String, val refresh: () -> String?)

    /** Looks the video up again and returns a new link for the same stream (itag). */
    private fun freshUrl(pageUrl: String, itag: Int, videoOnly: Boolean?): String? = try {
        val ex = ServiceList.YouTube.getStreamExtractor(pageUrl.trim())
        ex.fetchPage()
        when (videoOnly) {
            true -> ex.videoOnlyStreams.firstOrNull { it.itag == itag && it.isUrl }?.content
            false -> ex.audioStreams.firstOrNull { it.itag == itag && it.isUrl }?.content
            null -> ex.videoStreams.firstOrNull { it.itag == itag && it.isUrl }?.content
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "Couldn't refresh the stream link: $e")
        null
    }

    /** Which YouTube app a stream link was made for ("c=ANDROID", "c=IOS", "c=WEB", ...). */
    private fun clientOf(url: String) = Regex("[?&]c=([A-Za-z0-9_]+)").find(url)?.groupValues?.get(1)?.uppercase() ?: "WEB"

    /** YouTube checks that the downloader looks like the app the link was made for. */
    private fun userAgentFor(client: String): String {
        val method = when {
            client.startsWith("ANDROID") -> "getAndroidUserAgent"
            client.startsWith("IOS") -> "getIosUserAgent"
            else -> null
        } ?: return UA
        return try {
            val helper = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper")
            helper.getMethod(method, org.schabi.newpipe.extractor.localization.Localization::class.java).invoke(null, null) as String
        } catch (e: Throwable) {
            if (client.startsWith("IOS")) "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"
            else "com.google.android.youtube/19.28.35 (Linux; U; Android 15) gzip"
        }
    }

    /** One piece of a stream, requested the way YouTube's own players do (range in the link). */
    private fun pieceRequest(url: String, from: Long, to: Long, n: Int): okhttp3.Request {
        val client = clientOf(url)
        val b = okhttp3.Request.Builder()
            .url("$url&range=$from-$to&rn=$n")
            .header("User-Agent", userAgentFor(client))
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
        if (client.startsWith("WEB") || client.startsWith("MWEB") || client.startsWith("TVHTML5")) {
            b.header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/")
            b.post(byteArrayOf(0x78, 0).toRequestBody()) // what the web player sends
        } else {
            b.get()
        }
        return b.build()
    }

    private fun sizeOf(link: Link): Long {
        Regex("[?&]clen=(\\d+)").find(link.url)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        val req = okhttp3.Request.Builder().url(link.url).header("User-Agent", userAgentFor(clientOf(link.url))).header("Range", "bytes=0-0").build()
        http.newCall(req).execute().use { r ->
            val range = r.header("Content-Range") ?: return r.header("Content-Length")?.toLongOrNull() ?: -1
            return range.substringAfter('/').toLongOrNull() ?: -1
        }
    }

    private const val VIDEO_CONNECTIONS = 4

    /**
     * Downloads a stream with [connections] requests at once (YouTube limits the speed of each
     * single connection). [progress] gets the number of bytes downloaded so far.
     */
    private fun fetch(link: Link, out: File, size: Long, task: Task, connections: Int, progress: (Long) -> Unit) {
        AppLog.i(TAG, "Fetching ${if (size > 0) "${size / 1_048_576} MB" else "stream"} with $connections connections (client ${clientOf(link.url)})")
        if (size <= 0) {
            fetchSequential(link, out, task, progress)
            return
        }
        RandomAccessFile(out, "rw").use { it.setLength(size) }
        val pieces = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        var p = 0L
        while (p < size) {
            pieces.add(p); p += CHUNK
        }
        val done = java.util.concurrent.atomic.AtomicLong()
        val requestNo = java.util.concurrent.atomic.AtomicInteger(1)
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val linkVersion = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(connections)
        repeat(connections) {
            pool.submit(Runnable {
                try {
                    RandomAccessFile(out, "rw").use { raf ->
                        val buf = ByteArray(256 * 1024)
                        while (failure.get() == null) {
                            val start = pieces.poll() ?: break
                            val end = minOf(size - 1, start + CHUNK - 1)
                            var attempt = 0
                            while (true) {
                                if (task.isCancelled) throw Cancelled()
                                val version = linkVersion.get()
                                var got = 0L
                                try {
                                    raf.seek(start)
                                    http.newCall(pieceRequest(link.url, start, end, requestNo.getAndIncrement())).execute().use { r ->
                                        if (!r.isSuccessful) throw HttpError(r.code)
                                        val body = r.body ?: throw java.io.IOException("empty body")
                                        body.byteStream().use { input ->
                                            while (true) {
                                                val n = input.read(buf)
                                                if (n < 0) break
                                                val take = minOf(n.toLong(), end + 1 - start - got).toInt()
                                                if (take <= 0) break
                                                raf.write(buf, 0, take)
                                                got += take
                                                progress(done.addAndGet(take.toLong()))
                                                if (task.isCancelled) throw Cancelled()
                                            }
                                        }
                                    }
                                    if (got < end + 1 - start) throw java.io.IOException("piece cut short ($got of ${end + 1 - start} bytes)")
                                    break
                                } catch (e: java.io.IOException) {
                                    done.addAndGet(-got)
                                    // 403/410: the link was cut off or expired. One connection gets a fresh
                                    // link for everyone; the others just retry with it.
                                    if (e is HttpError && (e.code == 403 || e.code == 410)) {
                                        synchronized(link) {
                                            if (linkVersion.get() == version) {
                                                if (refreshes.incrementAndGet() > 8) throw e
                                                AppLog.w(TAG, "YouTube refused the link at $start (HTTP ${e.code}); getting a fresh link")
                                                Thread.sleep(1500)
                                                link.refresh()?.let { link.url = it } ?: throw e
                                                linkVersion.incrementAndGet()
                                            }
                                        }
                                        continue
                                    }
                                    if (++attempt > 5) throw e
                                    AppLog.w(TAG, "Download hiccup at $start (attempt $attempt): $e")
                                    Thread.sleep(1000L shl minOf(attempt, 4))
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            })
        }
        pool.shutdown()
        while (!pool.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (task.isCancelled) failure.compareAndSet(null, Cancelled())
        }
        failure.get()?.let { throw it }
        if (done.get() < size) throw java.io.IOException("Download stopped at ${done.get()} of $size bytes")
    }

    /** For the rare stream whose size isn't known: one piece after another. */
    private fun fetchSequential(link: Link, out: File, task: Task, progress: (Long) -> Unit) {
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            var pos = 0L
            var requestNo = 1
            var refreshes = 0
            val buf = ByteArray(256 * 1024)
            while (true) {
                task.check()
                var got = 0L
                var attempt = 0
                while (true) {
                    try {
                        http.newCall(pieceRequest(link.url, pos, pos + CHUNK - 1, requestNo++)).execute().use { r ->
                            if (r.code == 416) return // past the end
                            if (!r.isSuccessful) throw HttpError(r.code)
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
                        raf.seek(pos)
                        got = 0
                        if (e is HttpError && (e.code == 403 || e.code == 410) && refreshes < 8) {
                            refreshes++
                            Thread.sleep(1500)
                            link.refresh()?.let { link.url = it } ?: throw e
                            continue
                        }
                        if (++attempt > 4) throw e
                        Thread.sleep(1000L shl attempt)
                    }
                }
                if (got == 0L) break
                pos += got
                if (got < CHUNK) break
            }
        }
    }

    private class HttpError(val code: Int) : java.io.IOException("HTTP $code")

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
