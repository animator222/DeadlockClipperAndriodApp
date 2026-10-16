package com.deadlockclipper.app.ui.editor

import android.net.Uri
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
    var tab by remember { mutableStateOf("") } // "" = panels closed: the video gets the room
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
    LaunchedEffect(st.srcZoom, st.srcPanX, st.srcPanY) {
        glView.sourceView = floatArrayOf(st.srcZoom, st.srcPanX, st.srcPanY)
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
        // Phones: the preview gets almost all of the screen; the layer and property panels open
        // over the lower part only when asked for. Tablets get side panels.
        val tablet = maxWidth > 840.dp && maxHeight > 600.dp
        val landscape = !tablet && maxWidth > maxHeight
        val panelHeight = maxHeight * 0.4f
        val now = { player.currentPosition / 1000.0 }
        val controls: @Composable () -> Unit = {
            Transport(st, info.duration, playing, muted, { togglePlay() }, { step(it) }, { frame(it) }, { muted = it })
            if (!st.template) TrimRow(st, now) { playSelection() }
            Timeline(st, thumbs, 52.dp) { seek(it) }
        }
        val toolbar: @Composable () -> Unit = {
            ToolBar(st, tab) { tab = if (tab == it) "" else it }
        }
        val panel: @Composable () -> Unit = {
            if (tab == "layers") LayersPanel(st) else PropertiesPanel(st) { typing = it }
        }
        Column(Modifier.fillMaxSize()) {
            TopBar(st, target, menu, { menu = it }, { leave() }, { save() }, vm)
            when {
                tablet -> Row(Modifier.weight(1f).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Panel(Modifier.width(250.dp).fillMaxHeight()) { LayersPanel(st) }
                    Column(Modifier.weight(1f)) {
                        StageArea(st, glView, hint, Modifier.weight(1f).fillMaxWidth())
                        controls()
                    }
                    Panel(Modifier.width(310.dp).fillMaxHeight()) { PropertiesPanel(st) { typing = it } }
                }
                landscape -> Row(Modifier.weight(1f)) {
                    StageArea(st, glView, hint, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(340.dp).fillMaxHeight().background(C.edPanel).padding(horizontal = 6.dp)) {
                        controls()
                        toolbar()
                        if (tab.isNotEmpty()) Panel(Modifier.weight(1f).fillMaxWidth()) { panel() }
                    }
                }
                else -> {
                    StageArea(st, glView, hint, Modifier.weight(1f).fillMaxWidth())
                    Column(Modifier.fillMaxWidth().background(C.edPanel).padding(horizontal = 6.dp)) {
                        controls()
                        if (tab.isNotEmpty()) Panel(Modifier.fillMaxWidth().height(panelHeight)) { panel() }
                        toolbar()
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
        modifier.background(C.edBg, RoundedCornerShape(10.dp)).padding(10.dp).verticalScroll(rememberScrollState()),
    ) { content() }
}

/** A compact button for the phone layout. */
@Composable
fun SmallBtn(text: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(34.dp).background(if (selected) C.accent else Color(0xFF1D2130), RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color(0xFFE6E8EE) else Color(0xFF666B7A), fontSize = 14.sp, maxLines = 1) }
}

@Composable
private fun TopBar(
    st: EditorState, target: EditorTarget, menu: Boolean, setMenu: (Boolean) -> Unit,
    leave: () -> Unit, save: () -> Unit, vm: AppViewModel,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(C.edPanel).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SmallBtn("✕") { leave() }
        Column(Modifier.weight(1f)) {
            Text(if (st.template) "My layout" else "Edit clip ${target.item?.index}", color = Color(0xFFF2F3F7), fontSize = 16.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
            val chip = st.chip ?: target.item?.clip?.reasons?.firstOrNull()
            if (!chip.isNullOrBlank()) Text(chip, color = C.reason, fontSize = 11.sp, maxLines = 1)
        }
        if (!st.template) {
            Box {
                SmallBtn("⋮") { setMenu(true) }
                DropdownMenu(menu, { setMenu(false) }, Modifier.background(C.edPanel)) {
                    DropdownMenuItem(text = { Text("★ Save as my layout", color = Color(0xFFE6E8EE)) }, onClick = {
                        setMenu(false)
                        vm.saveMyLayout(st.comp.copy(layers = st.comp.layers.filter { it.isVideo }), fromTemplate = false) // text is per clip
                        st.chip = "★ Saved as my layout"
                    })
                    DropdownMenuItem(text = { Text("↻ Apply layout to all clips", color = Color(0xFFE6E8EE)) }, onClick = {
                        setMenu(false)
                        vm.applyToAll(st.comp, target.item)
                        st.chip = "↻ Layout applied to all clips"
                    })
                }
            }
        }
        SmallBtn(if (st.template) "Save layout" else "Save", selected = true) { save() }
    }
}

@Composable
private fun StageArea(st: EditorState, glView: EditorGlView, hint: String?, modifier: Modifier) {
    Box(modifier.background(C.stage)) {
        AndroidView({ (glView.parent as? android.view.ViewGroup)?.removeView(glView); glView }, Modifier.fillMaxSize())
        EditorOverlay(st) {}
        // Canvas / Source switch floats over the preview so the video keeps all the room.
        Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallBtn("Canvas", selected = st.mode == "canvas") { st.setModeChecked("canvas") }
            SmallBtn("Source", selected = st.mode == "source") { st.setModeChecked("source") }
            if (st.mode == "source") {
                if (st.srcZoom > 1.01f) SmallBtn("%.1fx  ✕".format(st.srcZoom)) { st.resetSourceZoom() }
                else Text("Pinch to zoom", color = C.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        if (hint != null) {
            Text(hint, color = C.reason, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter)
                .background(Color(0xCC0B0C10)).padding(6.dp))
        }
    }
}

@Composable
private fun Transport(
    st: EditorState, duration: Double, playing: Boolean, muted: Boolean,
    toggle: () -> Unit, step: (Double) -> Unit, frame: (Int) -> Unit, setMuted: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallBtn("⏪") { step(-5.0) }
        SmallBtn("◀|") { frame(-1) }
        SmallBtn(if (playing) "⏸" else "▶", selected = true) { toggle() }
        SmallBtn("|▶") { frame(1) }
        SmallBtn("⏩") { step(5.0) }
        Text(
            "${fmtPrecise(st.pos)} / ${fmtTime(duration)}", color = Color(0xFFDFE2EA), fontFamily = FontFamily.Monospace,
            fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        SmallBtn(if (muted) "🔇" else "🔊", selected = muted) { setMuted(!muted) }
    }
}

@Composable
private fun TrimRow(st: EditorState, now: () -> Double, playSelection: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallBtn("[ Start") { st.markIn(now()) }
        SmallBtn("End ]") { st.markOut(now()) }
        Text(
            "${fmtPrecise(st.s0)}→${fmtPrecise(st.s1)} • ${"%.1f".format(st.s1 - st.s0)}s",
            color = C.muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f),
        )
        SmallBtn("▶ Clip") { playSelection() }
    }
}

@Composable
private fun ToolBar(st: EditorState, tab: String, toggle: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SmallBtn(if (tab == "layers") "▼ Layers" else "Layers", selected = tab == "layers") { toggle("layers") }
        SmallBtn(if (tab == "props") "▼ Edit layer" else "Edit layer", selected = tab == "props") { toggle("props") }
        SmallBtn("+ Text") { st.addText(); if (tab != "props") toggle("props") }
        SmallBtn("+ Video") { st.addVideo() }
        SmallBtn("↔ Timeline") { st.widen() }
    }
}
