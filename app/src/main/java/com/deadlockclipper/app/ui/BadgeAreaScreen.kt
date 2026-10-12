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
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.core.VisionConst
import com.deadlockclipper.core.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** "Kill badge area" setup: find a moment with a kill and draw a box around the badge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgeAreaScreen(vm: AppViewModel) {
    val info = vm.info ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            setMediaItem(MediaItem.fromUri(Uri.parse(info.uri)))
            prepare()
            seekTo((info.duration * 400).toLong()) // start 40% in
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var position by remember { mutableFloatStateOf((info.duration * 0.4).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf(vm.settings.badgeRegion(info)) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) position = player.currentPosition / 1000f
            playing = player.isPlaying
            delay(150)
        }
    }

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Title("Show the app where your kill badge appears")
        Column(Modifier.weight(1f)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val aspect = info.width.toFloat() / info.height
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val boxW = minOf(maxWidth.value, maxHeight.value * aspect)
                    val boxH = boxW / aspect
                    Box(Modifier.size(boxW.dp, boxH.dp).background(Color(0xFF0D0E11))) {
                        AndroidView({ ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } }, Modifier.fillMaxSize())
                        RegionOverlay(region) { region = it; result = null }
                    }
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Subtle(
                    "1. Use the slider or Play to find a moment where the red KILL / KILL ASSIST badge is on screen.\n" +
                        "2. Drag a box around the badge, leaving a little space around it (drag the corner to resize, drag outside the box to draw a new one).\n" +
                        "3. Press 'Check this frame' to make sure it's found, then Save. You only need to do this once per layout.",
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(if (playing) "⏸" else "▶") { if (player.isPlaying) player.pause() else player.play() }
            Slider(
                value = position, valueRange = 0f..info.duration.toFloat(),
                onValueChange = { dragging = true; position = it; player.seekTo((it * 1000).toLong()) },
                onValueChangeFinished = { dragging = false },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(thumbColor = C.primary, activeTrackColor = C.primary),
            )
            Subtle("${fmtTime(position.toDouble())} / ${fmtTime(info.duration)}")
        }
        result?.let { (text, ok) -> Text(text, color = if (ok) C.good else C.bad) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("Check this frame") {
                player.pause()
                val t = player.currentPosition / 1000.0
                val r = region
                result = "Checking..." to true
                scope.launch {
                    val score = withContext(Dispatchers.Default) {
                        val bmp = Previews.frameAt(context, info.uri, t) ?: return@withContext null
                        val rgba = Mat()
                        val bgr = Mat()
                        try {
                            Utils.bitmapToMat(bmp, rgba)
                            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                            BadgeDetector.checkFrame(bgr, r)
                        } finally {
                            rgba.release(); bgr.release(); bmp.recycle()
                        }
                    }
                    AppLog.i("badge", "Badge area check: score $score (region $r, expected size ${VisionConst.BADGE_DIAMETER_1080P} px)")
                    result = when {
                        score == null -> "Couldn't read this frame. Try another moment." to false
                        score >= 0.5 -> "✔ Kill badge found (score ${"%.2f".format(score)})" to true
                        else -> "✖ No kill badge found here (score ${"%.2f".format(score)}). Is the badge on screen and inside the box?" to false
                    }
                }
            }
            SecondaryButton("Reset to default") { region = VisionConst.defaultRegion(info); result = null }
            SecondaryButton("Cancel") { vm.showSettings() }
            PrimaryButton("Save") { vm.saveBadgeRegion(region) }
        }
    }
}

/** A box on top of the video: drag inside to move, drag the corner to resize, drag outside to draw a new box. */
@Composable
fun RegionOverlay(region: List<Double>, onChange: (List<Double>) -> Unit) {
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
    }
}
