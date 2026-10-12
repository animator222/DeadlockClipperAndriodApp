package com.deadlockclipper.app.ui.editor

import android.net.Uri
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.deadlockclipper.app.media.Previews
import com.deadlockclipper.app.ui.AppViewModel
import com.deadlockclipper.app.ui.C
import com.deadlockclipper.app.ui.EditorTarget
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.core.fmtPrecise
import com.deadlockclipper.core.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val STRIP_FRAMES = 16

/**
 * Clip editor: a live preview of the finished clip with layers (gameplay, webcam/items video
 * layers, text), a properties panel, playback controls and a trim timeline.
 * Also used without a clip ("My layout"), the default for all clips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: AppViewModel, target: EditorTarget) {
    val info = vm.info ?: return
    val context = LocalContext.current
    val st = remember(target) { EditorState(info, target) }
    var hint by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("layers") }
    var typing by remember { mutableStateOf(false) }
    val thumbs = remember { mutableStateListOf<ImageBitmap>() }
    val focus = remember { FocusRequester() }

    val player = remember(target) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(info.uri)))
            prepare()
            seekTo((st.s0 * 1000).toLong())
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    AppLog.e("editor", "Media player error: ${error.errorCodeName}", error)
                    hint = "The preview can't play this file. Editing still works; the export is not affected."
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }
            })
        }
    }
    var lastSurface by remember { mutableStateOf<Surface?>(null) }
    val glView = remember(target) {
        EditorGlView(context) { surface ->
            player.setVideoSurface(surface)
            lastSurface?.release()
            lastSurface = surface
        }.apply { this.info = info }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(target) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> {
                    player.pause(); glView.onPause()
                }
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            player.clearVideoSurface()
            player.release()
            glView.releaseTexture()
            lastSurface?.release()
        }
    }
    LaunchedEffect(st.comp, st.mode) {
        glView.comp = st.comp
        glView.sourceMode = st.mode == "source"
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(target) {
        while (true) {
            val t = player.currentPosition / 1000.0
            st.stopAt?.let { stop ->
                if (t >= stop) {
                    st.stopAt = null
                    player.pause()
                }
            }
            if (t > st.v1) st.v1 = minOf(info.duration, t + 20)
            st.pos = t
            delay(50)
        }
    }
    LaunchedEffect(st.v0, st.v1) {
        val v0 = st.v0
        val span = st.v1 - st.v0
        thumbs.clear()
        delay(200) // the view often changes several times in a row
        val frames = withContext(Dispatchers.IO) {
            (0 until STRIP_FRAMES).mapNotNull { k ->
                Previews.stripFrame(context, info.uri, v0 + span * (k + 0.5) / STRIP_FRAMES, 160, 90)?.asImageBitmap()
            }
        }
        thumbs.clear()
        thumbs.addAll(frames)
    }

    fun seek(t: Double) {
        val tt = t.coerceIn(0.0, info.duration)
        player.seekTo((tt * 1000).toLong())
        st.pos = tt
    }
    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            st.stopAt = null
            player.play()
        }
    }
    fun step(seconds: Double) = seek(player.currentPosition / 1000.0 + seconds)
    fun frame(n: Int) {
        player.pause()
        seek(player.currentPosition / 1000.0 + n / maxOf(1.0, info.fps))
    }
    fun playSelection() {
        seek(st.s0)
        st.stopAt = st.s1
        player.play()
    }
    fun leave() {
        if (st.dirty) confirmDiscard = true else vm.closeEditor()
    }
    fun save() {
        if (st.template) {
            vm.saveMyLayout(st.comp, fromTemplate = true)
            vm.closeEditor()
        } else {
            vm.saveClipEdit(target.item!!, st.comp, st.s0, st.s1)
        }
    }
    BackHandler { leave() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(C.edBg).focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                // Keyboard: Space play/pause, arrows 1 s, Shift+arrows one frame, I/O start/end, Del delete layer.
                if (typing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Spacebar -> togglePlay()
                    Key.DirectionLeft -> if (e.isShiftPressed) frame(-1) else step(-1.0)
                    Key.DirectionRight -> if (e.isShiftPressed) frame(1) else step(1.0)
                    Key.I -> st.markIn(player.currentPosition / 1000.0)
                    Key.O -> st.markOut(player.currentPosition / 1000.0)
                    Key.Delete, Key.Backspace -> st.deleteCurrent()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        val wide = maxWidth > 840.dp
        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------- top bar
            Row(
                Modifier.fillMaxWidth().background(C.edPanel).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (st.template) "My layout" else "Edit clip ${target.item?.index}", color = Color(0xFFF2F3F7), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    val chip = st.chip ?: target.item?.clip?.reasons?.joinToString(" • ")
                    if (!chip.isNullOrBlank()) Text(chip, color = C.reason, fontSize = 12.sp, maxLines = 1)
                }
                EdButton("Cancel") { leave() }
                EdButton(if (st.template) "Save layout" else "Save", selected = true) { save() }
                if (!st.template) {
                    Box {
                        EdButton("⋮") { menu = true }
                        DropdownMenu(menu, { menu = false }, Modifier.background(C.edPanel)) {
                            DropdownMenuItem(text = { Text("★ Save as my layout", color = Color(0xFFE6E8EE)) }, onClick = {
                                menu = false
                                vm.saveMyLayout(st.comp.copy(layers = st.comp.layers.filter { it.isVideo }), fromTemplate = false) // text is per clip
                                st.chip = "★ Saved as my layout"
                            })
                            DropdownMenuItem(text = { Text("↻ Apply layout to all clips", color = Color(0xFFE6E8EE)) }, onClick = {
                                menu = false
                                vm.applyToAll(st.comp, target.item)
                                st.chip = "↻ Layout applied to all clips"
                            })
                        }
                    }
                }
            }

            if (wide) {
                Row(Modifier.weight(1f).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Panel(Modifier.width(250.dp).fillMaxHeight()) { LayersPanel(st) }
                    Column(Modifier.weight(1f)) {
                        StageArea(st, glView, hint, Modifier.weight(1f))
                        Transport(st, info.duration, playing, muted, { togglePlay() }, { step(it) }, { frame(it) }, { playSelection() }, { muted = it })
                        Timeline(st, thumbs) { seek(it) }
                        TrimRow(st) { player.currentPosition / 1000.0 }
                    }
                    Panel(Modifier.width(310.dp).fillMaxHeight()) { PropertiesPanel(st) { typing = it } }
                }
            } else {
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    StageArea(st, glView, hint, Modifier.weight(1f).heightIn(min = 200.dp))
                    Transport(st, info.duration, playing, muted, { togglePlay() }, { step(it) }, { frame(it) }, { playSelection() }, { muted = it })
                    Timeline(st, thumbs) { seek(it) }
                    TrimRow(st) { player.currentPosition / 1000.0 }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        EdButton("Layers", selected = tab == "layers") { tab = if (tab == "layers") "" else "layers" }
                        EdButton("Properties", selected = tab == "props") { tab = if (tab == "props") "" else "props" }
                        if (tab.isNotEmpty()) EdButton("▼ Hide") { tab = "" }
                    }
                    if (tab.isNotEmpty()) {
                        Panel(Modifier.fillMaxWidth().height(260.dp)) {
                            if (tab == "layers") LayersPanel(st) else PropertiesPanel(st) { typing = it }
                        }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = C.edPanel,
            title = { Text("Discard your changes?", color = Color(0xFFF2F3F7)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.closeEditor() }) { Text("Discard", color = C.accent) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing", color = Color(0xFFF2F3F7)) } },
        )
    }
}

@Composable
private fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.background(C.edPanel, RoundedCornerShape(10.dp)).padding(12.dp).verticalScroll(rememberScrollState()),
    ) { content() }
}

@Composable
private fun StageArea(st: EditorState, glView: EditorGlView, hint: String?, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
            EdButton("▣  Canvas", selected = st.mode == "canvas") { st.setModeChecked("canvas") }
            Spacer(Modifier.width(4.dp))
            EdButton("▢  Source video", selected = st.mode == "source") { st.setModeChecked("source") }
        }
        Text(
            hint ?: if (st.mode == "source") "Source video: drag/resize the selected layer's box to choose what part of the video it shows"
            else "Canvas: this is your finished clip. Drag layers to move them, drag a corner or pinch to resize",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(C.stage, RoundedCornerShape(10.dp))) {
            AndroidView({ (glView.parent as? android.view.ViewGroup)?.removeView(glView); glView }, Modifier.fillMaxSize())
            EditorOverlay(st) {}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Transport(
    st: EditorState, duration: Double, playing: Boolean, muted: Boolean,
    toggle: () -> Unit, step: (Double) -> Unit, frame: (Int) -> Unit, playSelection: () -> Unit, setMuted: (Boolean) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EdButton("⏪") { step(-5.0) }
        EdButton("◀|") { frame(-1) }
        EdButton(if (playing) "⏸" else "▶", selected = true) { toggle() }
        EdButton("|▶") { frame(1) }
        EdButton("⏩") { step(5.0) }
        Text(
            "${fmtPrecise(st.pos)} / ${fmtTime(duration)}", color = Color(0xFFDFE2EA), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 6.dp),
        )
        if (!st.template) EdButton("▶ Play clip") { playSelection() }
        EdButton(if (muted) "🔇" else "🔊", selected = muted) { setMuted(!muted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrimRow(st: EditorState, now: () -> Double) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!st.template) {
            EdButton("[  Set start here") { st.markIn(now()) }
            EdButton("Set end here  ]") { st.markOut(now()) }
            Text(
                "Clip ${fmtPrecise(st.s0)} → ${fmtPrecise(st.s1)}  •  ${"%.1f".format(st.s1 - st.s0)}s",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        EdButton("↔ Show more timeline") { st.widen() }
    }
}
