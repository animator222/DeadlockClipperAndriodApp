package com.deadlockclipper.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deadlockclipper.app.App
import com.deadlockclipper.app.media.Analyzer
import com.deadlockclipper.app.media.Exporter
import com.deadlockclipper.app.media.Previews
import com.deadlockclipper.app.media.Probe
import com.deadlockclipper.app.media.YouTube
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.Cancelled
import com.deadlockclipper.app.util.FriendlyError
import com.deadlockclipper.app.util.Task
import com.deadlockclipper.app.work.WorkService
import com.deadlockclipper.core.Analysis
import com.deadlockclipper.core.Clip
import com.deadlockclipper.core.Comp
import com.deadlockclipper.core.Composition
import com.deadlockclipper.core.Events
import com.deadlockclipper.core.Highlights
import com.deadlockclipper.core.Settings
import com.deadlockclipper.core.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Home, Settings, Progress, Review, Done, Editor, BadgeArea }

/** One suggested clip on the review screen. */
class ClipItem(val clip: Clip, val index: Int) {
    var checked by mutableStateOf(true)
    var thumb by mutableStateOf<Bitmap?>(null)
    var version by mutableIntStateOf(0) // bumped when the clip is edited
    val valid get() = clip.length in 3.0..60.0
}

class ErrorInfo(val message: String, val details: String)

/** What the editor is working on: a clip, or "My layout" (item == null). */
class EditorTarget(val item: ClipItem?, val comp: Comp, val start: Double, val end: Double)

/** The app's state and the work behind every screen (the PC app's MainWindow). */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<App>()
    val paths = (app as App).paths
    private val main = Handler(Looper.getMainLooper())

    var screen by mutableStateOf(Screen.Home)
    var settings by mutableStateOf(loadSettings())
    var info by mutableStateOf<VideoInfo?>(null)
        private set
    var analysis: Analysis? = null
        private set
    var events by mutableStateOf<Events?>(null)
        private set
    val clips = mutableStateListOf<ClipItem>()
    val previews = mutableStateMapOf<String, Bitmap>()
    var exportResult by mutableStateOf<Exporter.Result?>(null)
        private set
    var editor by mutableStateOf<EditorTarget?>(null)
        private set
    private var editorReturn = Screen.Review
    var error by mutableStateOf<ErrorInfo?>(null)
    var message by mutableStateOf<String?>(null)

    // progress screen
    var progressTitle by mutableStateOf("")
        private set
    var progressFraction by mutableStateOf(0.0)
        private set
    var progressMessage by mutableStateOf("")
        private set
    var cancelling by mutableStateOf(false)
        private set
    private var task: Task? = null
    private var previewJob = 0
    private var thumbJob = 0

    init {
        Highlights.debugLog = { AppLog.d("highlights", it) }
    }

    // ------------------------------------------------------------ settings

    private fun loadSettings(): Settings = try {
        val f = (getApplication<App>()).paths.settings
        if (f.exists()) Settings.fromJson(f.readText()) else Settings()
    } catch (e: Exception) {
        AppLog.e("settings", "Could not read saved settings, using defaults", e)
        Settings()
    }

    fun saveSettings() {
        try {
            paths.settings.writeText(settings.toJson())
        } catch (e: Exception) {
            AppLog.e("settings", "Could not save settings", e)
        }
    }

    fun updateSettings(s: Settings) {
        settings = s
    }

    // ------------------------------------------------------------ background work

    private fun <T> runJob(title: String, backTo: Screen, service: Boolean = true, work: (Task) -> T, done: (T) -> Unit) {
        val t = Task({ f, m ->
            main.post {
                progressFraction = f
                if (m.isNotEmpty()) progressMessage = m
            }
            if (service) WorkService.update(ctx, title, f)
        })
        task = t
        progressTitle = title
        progressFraction = 0.0
        progressMessage = "Starting..."
        cancelling = false
        screen = Screen.Progress
        if (service) WorkService.start(ctx, title)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { work(t) }
                done(result)
            } catch (e: Cancelled) {
                AppLog.i("task", "Task cancelled")
                screen = backTo
            } catch (e: FriendlyError) {
                AppLog.e("task", "${e.message} | ${e.details}")
                screen = backTo
                error = ErrorInfo(e.message, e.details)
            } catch (e: Throwable) {
                AppLog.e("task", "Unexpected error in background task", e)
                screen = backTo
                error = ErrorInfo(
                    if (e is OutOfMemoryError) "The phone ran out of memory. Close other apps and try again."
                    else "Something unexpected went wrong: $e",
                    AppLog.stackTrace(e),
                )
            } finally {
                if (service) WorkService.stop(ctx)
                if (task === t) task = null
            }
        }
    }

    private fun background(what: String, work: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                work()
            } catch (e: Throwable) {
                AppLog.w("background", "$what failed", e)
            }
        }
    }

    private fun onMain(block: () -> Unit) = main.post(block)

    fun cancel() {
        cancelling = true
        task?.cancel()
    }

    // ------------------------------------------------------------ input

    fun openUri(uri: Uri, fromOtherApp: Boolean = false) {
        if (screen == Screen.Progress) {
            message = "Please wait until the current job is finished (or cancel it)."
            return
        }
        AppLog.i("input", "Opening $uri")
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Not every source offers lasting access; it's only needed while the app is open.
        }
        runJob("Opening the video", Screen.Home, service = false, work = { Probe.probe(ctx, uri) }) { v -> setVideo(v) }
    }

    fun openUrl(url: String) {
        if (!YouTube.isYouTubeLink(url)) {
            error = ErrorInfo("That doesn't look like a YouTube link.", url)
            return
        }
        val quality = settings.youtubeMaxHeight
        saveSettings()
        runJob("Downloading from YouTube", Screen.Home, work = { t ->
            val file = YouTube.download(url, paths.downloads, quality, t)
            Probe.probe(ctx, Uri.fromFile(file))
        }) { v -> setVideo(v) }
    }

    private fun setVideo(v: VideoInfo) {
        if (info?.uri != v.uri) {
            clips.clear()
            analysis = null
            events = null
            Previews.releaseRetriever()
        }
        info = v
        showSettings()
    }

    fun goHome() {
        screen = Screen.Home
    }

    fun showSettings() {
        screen = Screen.Settings
        makePreviews(listOf("blur", "zoom", "custom"))
    }

    /** Finished-frame previews for the settings screen, for both export shapes. */
    fun makePreviews(formats: List<String>) {
        val v = info ?: return
        val job = ++previewJob
        val base = settings
        background("Preview") {
            val t = minOf(v.duration * 0.4, maxOf(0.0, v.duration - 1))
            val frame = Previews.frameAt(ctx, v.uri, t)
            for (orientation in listOf(base.outputOrientation, if (base.outputOrientation == "vertical") "horizontal" else "vertical")) {
                for (fmt in formats) {
                    if (job != previewJob) return@background
                    val comp = Composition.forClip(null, base.copy(verticalFormat = fmt, outputOrientation = orientation), v)
                    val (w, h) = Previews.previewSize(comp, 480)
                    val bmp = Previews.render(frame, comp, v, w, h)
                    onMain { if (job == previewJob) previews["$orientation|$fmt"] = bmp }
                }
            }
            frame?.recycle()
        }
    }

    // ------------------------------------------------------------ analysis

    fun startAnalysis() {
        val v = info ?: return
        val s = settings.validate()
        if (!(s.wantKills || s.wantMultikills || s.wantLoudOnly)) {
            error = ErrorInfo("Tick at least one kind of highlight.", "")
            return
        }
        settings = s
        saveSettings()
        runJob("Finding highlights", Screen.Settings, work = { t ->
            val a = Analyzer.analyze(ctx, paths, v, s.quickScan, s.badgeRegion(v), t)
            t.progress(1.0, "Picking the best moments")
            val ev = Highlights.findEvents(a, s.sensitivity)
            val picked = Highlights.pickClips(ev, s, v.duration)
            Analyzer.writeReport(paths, v, ev, picked, s, a.segments)
            Triple(a, ev, picked)
        }) { (a, ev, picked) ->
            analysis = a
            events = ev
            showReview(picked)
        }
    }

    fun repick(sensitivity: Int, numClips: Int) {
        val v = info ?: return
        val a = analysis ?: return
        val s = settings.copy(sensitivity = sensitivity, numClips = numClips).validate()
        settings = s
        saveSettings()
        runJob("Picking clips", Screen.Review, service = false, work = {
            val ev = Highlights.findEvents(a, s.sensitivity)
            val picked = Highlights.pickClips(ev, s, v.duration)
            Analyzer.writeReport(paths, v, ev, picked, s, a.segments)
            ev to picked
        }) { (ev, picked) ->
            events = ev
            showReview(picked)
        }
    }

    private fun showReview(picked: List<Clip>) {
        clips.clear()
        picked.forEachIndexed { i, c -> clips.add(ClipItem(c, i + 1)) }
        screen = Screen.Review
        refreshThumbs(clips.toList())
    }

    fun backToClips() {
        if (clips.isNotEmpty() || events != null) screen = Screen.Review
    }

    /** Thumbnails show the finished look (layout, text) of each clip. */
    fun refreshThumbs(items: List<ClipItem>) {
        val v = info ?: return
        val s = settings
        val jobs = items.map { Triple(it, Composition.forClip(it.clip, s, v), it.clip.focus + 0.5) }
        val job = ++thumbJob
        background("Thumbnails") {
            for ((item, comp, t) in jobs) {
                if (job != thumbJob && items.size > 1) return@background
                val frame = Previews.frameAt(ctx, v.uri, t)
                val (w, h) = Previews.previewSize(comp, 360)
                val bmp = Previews.render(frame, comp, v, w, h)
                frame?.recycle()
                onMain { item.thumb = bmp }
            }
        }
    }

    fun deleteClip(item: ClipItem) {
        clips.remove(item)
    }

    fun selectAll(on: Boolean) {
        clips.forEach { it.checked = on }
    }

    // ------------------------------------------------------------ editor

    fun editClip(item: ClipItem) {
        val v = info ?: return
        editor = EditorTarget(item, Composition.forClip(item.clip, settings, v), item.clip.start, item.clip.end)
        editorReturn = Screen.Review
        screen = Screen.Editor
    }

    /** Open the editor on "My layout" for this video shape and the chosen export shape. */
    fun editMyLayout() {
        val v = info ?: return
        val s = settings
        val key = Composition.templateKey(v, s.outputOrientation)
        val comp = Composition.clean(s.myLayouts[key]) ?: Composition.simple(v, s.outputOrientation, s.zoomLevel)
        val at = v.duration * 0.4
        editor = EditorTarget(null, comp, at, minOf(v.duration, at + 20))
        editorReturn = Screen.Settings
        screen = Screen.Editor
    }

    fun closeEditor() {
        editor = null
        screen = editorReturn
    }

    fun saveClipEdit(item: ClipItem, comp: Comp, start: Double, end: Double) {
        val c = item.clip
        c.start = Math.round(start * 100) / 100.0
        c.end = Math.round(end * 100) / 100.0
        c.comp = comp
        if (c.focus < c.start || c.focus > c.end) c.focus = (c.start + c.end) / 2
        AppLog.i("editor", "Clip edited: %.2f-%.2f, %d layers".format(c.start, c.end, comp.layers.size))
        item.version++
        refreshThumbs(listOf(item))
        closeEditor()
    }

    fun saveMyLayout(comp: Comp, fromTemplate: Boolean) {
        val v = info ?: return
        val clean = Composition.clean(comp) ?: return
        val key = Composition.templateKey(v, clean.canvas)
        var s = settings.copy(myLayouts = settings.myLayouts + (key to clean))
        if (fromTemplate) s = s.copy(verticalFormat = "custom")
        settings = s
        saveSettings()
        AppLog.i("editor", "Saved my layout $key with ${clean.layers.size} layers")
        makePreviews(listOf("custom"))
    }

    /** Every other clip gets this layout's video layers and keeps its own text. */
    fun applyToAll(comp: Comp, editing: ClipItem?) {
        val videos = comp.layers.filter { it.isVideo }
        val changed = ArrayList<ClipItem>()
        for (item in clips) {
            if (item === editing) continue
            val ownText = item.clip.comp?.layers?.filter { it.isText } ?: emptyList()
            item.clip.comp = Comp(comp.canvas, videos + ownText)
            item.version++
            changed.add(item)
        }
        refreshThumbs(changed)
    }

    // ------------------------------------------------------------ kill badge area

    fun openBadgeArea() {
        screen = Screen.BadgeArea
    }

    fun saveBadgeRegion(region: List<Double>?) {
        val v = info ?: return
        settings = settings.withBadgeRegion(v, region?.map { Math.round(it * 10000) / 10000.0 }).validate()
        saveSettings()
        AppLog.i("settings", "Kill badge area set to $region")
        screen = Screen.Settings
    }

    // ------------------------------------------------------------ export

    fun export() {
        val v = info ?: return
        val chosen = clips.filter { it.checked }
        val bad = chosen.filter { !it.valid }.map { it.index }
        if (bad.isNotEmpty()) {
            error = ErrorInfo("Clip ${bad.joinToString(", ")} must be between 3 and 60 seconds long.", "")
            return
        }
        val s = settings.validate()
        settings = s
        saveSettings()
        runJob("Exporting clips", Screen.Review, work = { t ->
            Exporter.exportClips(ctx, paths, v, chosen.map { it.clip }, s, t)
        }) { r ->
            exportResult = r
            screen = Screen.Done
        }
    }

    fun detectionsImage(): File? = paths.detections.takeIf { it.exists() }

    override fun onCleared() {
        task?.cancel()
        super.onCleared()
    }
}
