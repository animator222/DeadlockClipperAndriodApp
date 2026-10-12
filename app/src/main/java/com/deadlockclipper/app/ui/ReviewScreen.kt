package com.deadlockclipper.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deadlockclipper.core.Clip
import com.deadlockclipper.core.Events
import com.deadlockclipper.core.Num
import com.deadlockclipper.core.fmtTime
import kotlin.math.roundToInt

/** The whole match at a glance: loudness, the fight threshold, kills, deaths and chosen clips. */
@Composable
fun LoudnessGraph(events: Events, clips: List<Clip>, duration: Double, modifier: Modifier = Modifier) {
    val curve = events.sustained
    val stats = remember(curve) {
        if (curve.isEmpty()) null else Num.percentile(curve, 1.0) to (curve.maxOrNull() ?: 0f).toDouble()
    }
    Canvas(modifier.fillMaxWidth().height(90.dp).background(C.card)) {
        if (stats == null || duration <= 0) return@Canvas
        val w = size.width
        val h = size.height
        val lo = stats.first
        val hi = maxOf(stats.second, events.threshold + 1, lo + 1)
        fun y(v: Double) = (h - 8 - (v - lo) / (hi - lo) * (h - 20)).toFloat()
        fun x(t: Double) = (t / duration * w).toFloat()
        for (c in clips) drawRect(Color(217, 69, 43, 70), Offset(x(c.start), 0f), Size(maxOf(2f, x(c.end) - x(c.start)), h))
        val buckets = maxOf(1, minOf(w.toInt(), curve.size))
        var prev: Offset? = null
        for (i in 0 until buckets) {
            val a = (i.toLong() * curve.size / buckets).toInt()
            val b = maxOf(a + 1, ((i + 1).toLong() * curve.size / buckets).toInt())
            var m = Float.NEGATIVE_INFINITY
            for (k in a until minOf(b, curve.size)) m = maxOf(m, curve[k])
            val pt = Offset(i.toFloat() / buckets * w, y(m.toDouble()))
            prev?.let { drawLine(Color(0xFF7AA2F7), it, pt, 1.5f) }
            prev = pt
        }
        drawLine(C.reason, Offset(0f, y(events.threshold)), Offset(w, y(events.threshold)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        for (t in events.takedowns) drawLine(Color(0xFFFF4D4D), Offset(x(t), 0f), Offset(x(t), 12f), 3f)
        for (t in events.deaths) drawLine(Color(0xFFBBBBBB), Offset(x(t), h - 12f), Offset(x(t), h), 3f)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(vm: AppViewModel) {
    val info = vm.info ?: return
    val events = vm.events ?: return
    var sens by remember { mutableFloatStateOf(vm.settings.sensitivity.toFloat()) }
    var num by remember { mutableIntStateOf(if (vm.settings.numClips == 0) 5 else vm.settings.numClips) }
    var all by remember { mutableStateOf(vm.settings.numClips == 0) }
    var showDetections by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val checked = vm.clips.count { it.checked }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Title("Review your clips")
                    fun n(c: Int, w: String) = "$c $w${if (c == 1) "" else "s"}"
                    Subtle(
                        "Found ${n(events.takedowns.size, "kill/assist badge")}, ${n(events.deaths.size, "death")} and " +
                            "${n(events.fights.size, "loud fight")} in ${fmtTime(info.duration)} of gameplay." +
                            if (vm.clips.isEmpty()) "  No clips matched." else "",
                        size = 14,
                    )
                    LoudnessGraph(events, vm.clips.map { it.clip }, info.duration)
                    Subtle("Blue = game loudness, dashed = fight threshold, red ticks = your kills/assists, grey ticks = deaths, shaded = chosen clips", size = 12)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Fight sensitivity")
                        Slider(
                            value = sens, onValueChange = { sens = it.roundToInt().toFloat() }, valueRange = 1f..10f, steps = 8,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(thumbColor = C.primary, activeTrackColor = C.primary),
                        )
                        Subtle("${sens.roundToInt()}")
                    }
                    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("Clips", Modifier.align(Alignment.CenterVertically))
                        NumberStepper(num, 1..200, enabled = !all) { num = it }
                        Box(Modifier.width(170.dp)) { CheckRow("All highlights", all) { all = it } }
                        SecondaryButton("Re-pick clips") { vm.repick(sens.roundToInt(), if (all) 0 else num) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SecondaryButton("Select all") { vm.selectAll(true) }
                        SecondaryButton("Select none") { vm.selectAll(false) }
                        SecondaryButton("Why these clips?") {
                            if (vm.paths.analysisReport.exists()) showReport = true
                        }
                        SecondaryButton("See what was detected") {
                            if (vm.detectionsImage() != null) showDetections = true
                            else vm.error = ErrorInfo("Nothing that looked like a kill badge was found in this video.", "")
                        }
                    }
                    Subtle("Only ticked clips are exported. Tap a thumbnail to play and edit.")
                }
            }
            items(vm.clips, key = { it.clip.id }) { item -> ClipRow(vm, item) }
            item { Spacer(Modifier.height(8.dp)) }
        }
        FlowRow(
            Modifier.fillMaxWidth().background(C.card).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SecondaryButton("← Settings") { vm.showSettings() }
            SecondaryButton("Start over") { vm.goHome() }
            Spacer(Modifier.weight(1f))
            PrimaryButton("Export $checked of ${vm.clips.size} clip${if (vm.clips.size != 1) "s" else ""}", enabled = checked > 0) { vm.export() }
        }
    }
    if (showReport) {
        TextViewer(
            vm.paths.analysisReport,
            "Every kill/assist badge the app saw, whether it counted (and if not, why), and which kills didn't become clips.",
        ) { showReport = false }
    }
    if (showDetections) {
        vm.detectionsImage()?.let { file ->
            ImageViewer(file.path, "Everything that looked like your kill badge. Green = counted as a kill, orange = close but not counted.",
                onShare = { shareFile(context, file, "image/jpeg") }) { showDetections = false }
        }
    }
}

@Composable
private fun ClipRow(vm: AppViewModel, item: ClipItem) {
    val c = item.clip
    @Suppress("UNUSED_VARIABLE") val v = item.version // re-read after edits
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(item.checked, { item.checked = it }, colors = CheckboxDefaults.colors(checkedColor = C.primary, uncheckedColor = Color(0xFF6B7080)))
            val thumb = item.thumb
            val (tw, th) = if (thumb != null && thumb.height > thumb.width) 72.dp to 128.dp else 160.dp to 90.dp
            Box(
                Modifier.size(tw, th).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0D0E11)).clickable { vm.editClip(item) },
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) Image(thumb.asImageBitmap(), "Clip ${item.index}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Subtle("...")
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Clip ${item.index}   ${fmtTime(c.start)} → ${fmtTime(c.end)}", color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Subtle("%.1fs  •  score %s".format(c.length, if (c.score % 1.0 == 0.0) c.score.toInt().toString() else c.score.toString()))
                Text(c.reasons.joinToString(" • "), color = C.reason, fontSize = 13.sp)
                c.comp?.let { comp ->
                    val texts = comp.layers.count { it.isText }
                    val videos = comp.layers.count { it.isVideo }
                    Subtle("✎ Edited • $videos video layer${if (videos != 1) "s" else ""}" + if (texts > 0) " • $texts text" else "")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("▶ Play / Edit") { vm.editClip(item) }
            SecondaryButton("✕ Delete") { vm.deleteClip(item) }
        }
    }
}

/** A full-screen picture you can pinch to zoom. */
@Composable
fun ImageViewer(path: String, caption: String, onShare: () -> Unit, onClose: () -> Unit) {
    val bmp = remember(path) { BitmapFactory.decodeFile(path) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(10.dp)) {
            Subtle(caption)
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), null,
                        Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                        contentScale = ContentScale.Fit,
                    )
                } else Subtle("Couldn't open the picture")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                SecondaryButton("Share") { onShare() }
                Spacer(Modifier.weight(1f))
                PrimaryButton("Close") { onClose() }
            }
        }
    }
}

/** A full-screen text file (the analysis report) with Share and Copy. */
@Composable
fun TextViewer(file: java.io.File, caption: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val text = remember(file) { runCatching { file.readText() }.getOrDefault("") }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(C.bg).padding(12.dp)) {
            Title("Why these clips?")
            Subtle(caption)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth().background(C.card, RoundedCornerShape(6.dp))) {
                Text(
                    text, color = C.text, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                SecondaryButton("Share") { shareFile(context, file, "text/plain") }
                SecondaryButton("Copy") { copyText(context, text) }
                Spacer(Modifier.weight(1f))
                PrimaryButton("Close") { onClose() }
            }
        }
    }
}
