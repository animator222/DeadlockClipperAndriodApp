package com.deadlockclipper.app.ui

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.deadlockclipper.app.media.BadgeDetector
import com.deadlockclipper.app.media.Previews
import com.deadlockclipper.app.ui.editor.SmallBtn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.deadlockclipper.core.VisionConst
import com.deadlockclipper.core.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * "Kill badge area" setup: move to a moment with a kill and put a box around the badge.
 * The live check runs the real detector on the box a few times a second and circles the badge.
 */
@Composable
fun BadgeAreaScreen(vm: AppViewModel) {
    val info = vm.info ?: return
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            setMediaItem(MediaItem.fromUri(Uri.parse(info.uri)))
            prepare()
            seekTo((info.duration * 400).toLong()) // start 40% in
        }
    }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var position by remember { mutableFloatStateOf((info.duration * 0.4).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf(vm.settings.badgeRegion(info)) }
    var found by remember { mutableStateOf<BadgeDetector.Found?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) position = player.currentPosition / 1000f
            playing = player.isPlaying
            delay(100)
        }
    }
    // Live check: the real detector on the box of the frame on screen.
    LaunchedEffect(Unit) {
        var lastKey = ""
        while (true) {
            delay(250)
            val tv = texture ?: continue
            val key = "${player.currentPosition}|$region"
            if (key == lastKey && !player.isPlaying) continue
            lastKey = key
            val bmp = tv.bitmap ?: continue
            val r = region
            found = withContext(Dispatchers.Default) {
                val rgba = Mat()
                val bgr = Mat()
                try {
                    Utils.bitmapToMat(bmp, rgba)
                    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                    BadgeDetector.checkFrame(bgr, r)
                } catch (e: Exception) {
                    null
                } finally {
                    rgba.release(); bgr.release(); bmp.recycle()
                }
            }
        }
    }
    fun seek(t: Double) {
        val tt = t.coerceIn(0.0, info.duration)
        player.seekTo((tt * 1000).toLong())
        position = tt.toFloat()
    }
    fun frame(n: Int) {
        player.pause()
        seek(player.currentPosition / 1000.0 + n / maxOf(1.0, info.fps))
    }
    val hit = found?.takeIf { it.score >= 0.5 && it.x >= 0 }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(C.card).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallBtn("✕") { vm.showSettings() }
            Text("Kill badge area", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            SmallBtn("?") { showHelp = !showHelp }
            SmallBtn("Reset") { region = VisionConst.defaultRegion(info) }
            SmallBtn("Save", selected = true) { vm.saveBadgeRegion(region) }
        }
        if (showHelp) {
            Subtle(
                "1. Move to a moment where the red KILL / ASSIST badge is on screen (slider, ▶, or frame by frame).\n" +
                    "2. Drag a box around it (drag inside to move, the corner to resize, outside to draw a new one) - " +
                    "or tap 'Fit box to badge' once it's found.\n" +
                    "3. Leave about one badge-width of room around it and keep the crosshair out, then Save. " +
                    "You only need to do this once per layout.",
                modifier = Modifier.padding(8.dp),
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
            val aspect = info.width.toFloat() / info.height
            val boxW = minOf(maxWidth.value, maxHeight.value * aspect)
            val boxH = boxW / aspect
            Box(Modifier.size(boxW.dp, boxH.dp)) {
                AndroidView({ ctx -> TextureView(ctx).also { player.setVideoTextureView(it); texture = it } }, Modifier.fillMaxSize())
                RegionOverlay(region, hit?.let { it.x to it.y }) { region = it }
            }
        }
        Column(Modifier.fillMaxWidth().background(C.card).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (hit != null) "✔ Kill badge found in the box (score ${"%.2f".format(hit.score)})"
                    else "No kill badge in the box on this frame. Move to a moment with a KILL or ASSIST badge.",
                    color = if (hit != null) C.good else C.subtle, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    fontWeight = if (hit != null) FontWeight.Bold else FontWeight.Normal,
                )
                SmallBtn("⌖ Fit box to badge", enabled = hit != null) {
                    val f = hit ?: return@SmallBtn
                    // About one badge-width of room on each side.
                    val badge = 105.0 * minOf(info.width, info.height) / 1080
                    val hw = 1.6 * badge / info.width
                    val hh = 1.2 * badge / info.height
                    val x0 = maxOf(0.0, f.x - hw)
                    val y0 = maxOf(0.0, f.y - hh)
                    val x1 = minOf(1.0, f.x + hw)
                    val y1 = minOf(1.0, f.y + hh)
                    region = listOf(x0, y0, x1 - x0, y1 - y0)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                SmallBtn("⏪") { seek(player.currentPosition / 1000.0 - 5) }
                SmallBtn("◀|") { frame(-1) }
                SmallBtn(if (playing) "⏸" else "▶", selected = true) { if (player.isPlaying) player.pause() else player.play() }
                SmallBtn("|▶") { frame(1) }
                SmallBtn("⏩") { seek(player.currentPosition / 1000.0 + 5) }
                Text("${fmtTime(position.toDouble())} / ${fmtTime(info.duration)}", color = C.subtle, fontSize = 12.sp, maxLines = 1)
            }
            Slider(
                value = position, valueRange = 0f..info.duration.toFloat(),
                onValueChange = { dragging = true; position = it; player.seekTo((it * 1000).toLong()) },
                onValueChangeFinished = { dragging = false },
                colors = SliderDefaults.colors(thumbColor = C.primary, activeTrackColor = C.primary),
            )
        }
    }
}

/** A box on top of the video: drag inside to move, drag the corner to resize, drag outside to draw a new box. */
@Composable
fun RegionOverlay(region: List<Double>, marker: Pair<Double, Double>? = null, onChange: (List<Double>) -> Unit) {
    val handle = with(LocalDensity.current) { 28.dp.toPx() }
    val latest by rememberUpdatedState(region)
    val emit by rememberUpdatedState(onChange)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            var mode = ""
            var startPos = Offset.Zero
            var orig = latest
            var current = latest
            detectDragGestures(
                onDragStart = { p ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val (x, y, rw, rh) = latest.map { it.toFloat() }
                    val corner = Offset((x + rw) * w, (y + rh) * h)
                    mode = when {
                        (p - corner).getDistance() < handle -> "resize"
                        p.x in x * w..(x + rw) * w && p.y in y * h..(y + rh) * h -> "move"
                        else -> "draw"
                    }
                    startPos = p
                    orig = latest
                    current = latest
                },
                onDrag = { change, _ ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val p = Offset(change.position.x.coerceIn(0f, w), change.position.y.coerceIn(0f, h))
                    val (x, y, rw, rh) = orig
                    current = when (mode) {
                        "move" -> listOf(
                            (x + (p.x - startPos.x) / w).coerceIn(0.0, 1 - rw), (y + (p.y - startPos.y) / h).coerceIn(0.0, 1 - rh), rw, rh,
                        )
                        "resize" -> listOf(x, y, (p.x / w - x).coerceAtLeast(0.03), (p.y / h - y).coerceAtLeast(0.03))
                            .let { r -> listOf(r[0], r[1], minOf(r[2], 1 - r[0]), minOf(r[3], 1 - r[1])) }
                        else -> {
                            val x0 = minOf(startPos.x, p.x) / w
                            val y0 = minOf(startPos.y, p.y) / h
                            listOf(x0.toDouble(), y0.toDouble(), (kotlin.math.abs(p.x - startPos.x) / w).toDouble(), (kotlin.math.abs(p.y - startPos.y) / h).toDouble())
                        }
                    }
                    emit(current)
                },
                onDragEnd = {
                    val (x, y, rw, rh) = current
                    val min = 0.03
                    if (rw < min || rh < min) {
                        current = listOf(x, y, maxOf(rw, 0.12).coerceAtMost(1 - x), maxOf(rh, 0.12).coerceAtMost(1 - y))
                        emit(current)
                    }
                },
            )
        },
    ) {
        val w = size.width
        val h = size.height
        val (x, y, rw, rh) = region.map { it.toFloat() }
        val l = x * w
        val t = y * h
        val r = (x + rw) * w
        val b = (y + rh) * h
        val dim = Color(0f, 0f, 0f, 140f / 255f)
        drawRect(dim, Offset(0f, 0f), Size(w, t))
        drawRect(dim, Offset(0f, b), Size(w, h - b))
        drawRect(dim, Offset(0f, t), Size(l, b - t))
        drawRect(dim, Offset(r, t), Size(w - r, b - t))
        drawRect(C.reason, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 3f))
        val hs = handle * 0.6f
        drawRect(C.reason, Offset(r - hs / 2, b - hs / 2), Size(hs, hs))
        marker?.let { (mx, my) ->
            // Circle the badge the live check found.
            val radius = (105f * minOf(w, h) / 1080f) * 0.6f
            drawCircle(C.good, radius, Offset(mx.toFloat() * w, my.toFloat() * h), style = Stroke(width = 4f))
        }
    }
}
