package com.deadlockclipper.app.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deadlockclipper.app.ui.EditorTarget
import com.deadlockclipper.core.Comp
import com.deadlockclipper.core.Composition
import com.deadlockclipper.core.Layer
import com.deadlockclipper.core.VideoInfo

const val MIN_LEN = 3.0
const val MAX_LEN = 60.0

/** Everything the editor is changing: the layers, the selection, the trim and the timeline view. */
class EditorState(val info: VideoInfo, target: EditorTarget) {
    val template = target.item == null
    var comp by mutableStateOf(target.comp)
        private set
    var sel by mutableIntStateOf(if (target.comp.layers.isEmpty()) -1 else target.comp.layers.size - 1)
    var mode by mutableStateOf("canvas") // or "source"
    var s0 by mutableDoubleStateOf(target.start)
    var s1 by mutableDoubleStateOf(target.end)
    var pos by mutableDoubleStateOf(target.start)
    var v0 by mutableDoubleStateOf(0.0)
    var v1 by mutableDoubleStateOf(1.0)
    var dirty by mutableStateOf(false)
    var chip by mutableStateOf<String?>(null)
    var guideX by mutableStateOf(false)
    var guideY by mutableStateOf(false)
    var stopAt: Double? = null

    init {
        val pad = maxOf(20.0, (target.end - target.start) * 0.6)
        v0 = maxOf(0.0, target.start - pad)
        v1 = minOf(info.duration, target.end + pad)
        // Start with the gameplay layer selected (the bottom video layer).
        sel = comp.layers.indexOfFirst { it.isVideo }
    }

    val current: Layer? get() = comp.layers.getOrNull(sel)

    fun changeComp(c: Comp) {
        comp = c
        dirty = true
        if (sel >= c.layers.size) sel = c.layers.size - 1
    }

    fun update(i: Int, f: (Layer) -> Layer) {
        val layers = comp.layers.toMutableList()
        if (i !in layers.indices) return
        layers[i] = f(layers[i])
        changeComp(comp.copy(layers = layers))
    }

    fun updateCurrent(f: (Layer) -> Layer) = update(sel, f)

    fun select(i: Int) {
        sel = i
    }

    fun setModeChecked(m: String) {
        mode = m
        if (m == "source" && current?.isVideo != true) {
            sel = comp.layers.indexOfFirst { it.isVideo }
        }
    }

    fun addVideo() {
        val n = comp.layers.count { it.isVideo }
        val name = when (n) {
            1 -> "Webcam"
            2 -> "Items"
            else -> "Video $n"
        }
        changeComp(comp.copy(layers = comp.layers + Composition.newVideoLayer(info, name)))
        sel = comp.layers.size - 1
        setModeChecked("source") // first job: mark which part of the video it shows
    }

    fun addText() {
        changeComp(comp.copy(layers = comp.layers + Composition.textLayer()))
        sel = comp.layers.size - 1
        mode = "canvas"
    }

    fun deleteCurrent() {
        val i = sel
        val lay = current ?: return
        if (lay.isVideo && comp.layers.count { it.isVideo } == 1) return // keep at least one video layer
        changeComp(comp.copy(layers = comp.layers.filterIndexed { k, _ -> k != i }))
        sel = minOf(i, comp.layers.size - 1)
    }

    fun duplicateCurrent() {
        val lay = current ?: return
        val dup = if (lay.isVideo) lay.copy(dst = listOf(lay.dst[0] + 0.03, lay.dst[1] + 0.03, lay.dst[2]), name = lay.name + " copy")
        else lay.copy(cy = minOf(0.95, lay.cy + 0.05))
        val layers = comp.layers.toMutableList()
        layers.add(sel + 1, dup)
        changeComp(comp.copy(layers = layers))
        sel += 1
    }

    /** step +1 = bring forward (towards the front), -1 = send backward. */
    fun move(step: Int) {
        val i = sel
        val j = i + step
        if (i !in comp.layers.indices || j !in comp.layers.indices) return
        val layers = comp.layers.toMutableList()
        val t = layers[i]; layers[i] = layers[j]; layers[j] = t
        changeComp(comp.copy(layers = layers))
        sel = j
    }

    fun toggleHidden(i: Int) = update(i) { it.copy(hidden = !it.hidden) }

    /** Replace the gameplay layer's placement with full frame / zoomed, keeping the other layers. */
    fun resetLayout(zoom: Double) {
        val base = Composition.simple(info, comp.canvas, zoom).layers[0]
        val i = comp.layers.indexOfFirst { it.isVideo }
        if (i >= 0) update(i) { it.copy(src = base.src, dst = base.dst) }
    }

    fun fitWidth() = updateCurrent { lay ->
        if (!lay.isVideo) return@updateCurrent lay
        val (cw, ch) = comp.size
        val sw = lay.src[2] * info.width
        val sh = lay.src[3] * info.height
        lay.copy(dst = listOf(lay.dst[0], lay.dst[1], if (sw / sh < cw.toDouble() / ch) minOf(1.0, (ch / sh) * sw / cw) else 1.0))
    }

    fun fillCanvas() = updateCurrent { lay ->
        if (!lay.isVideo) return@updateCurrent lay
        val (cw, ch) = comp.size
        val sw = lay.src[2] * info.width
        val sh = lay.src[3] * info.height
        lay.copy(dst = listOf(0.5, 0.5, maxOf(cw / sw, ch / sh) * sw / cw))
    }

    fun centerLayer() = updateCurrent { lay ->
        if (lay.isVideo) lay.copy(dst = listOf(0.5, 0.5, lay.dst[2])) else lay.copy(cx = 0.5)
    }

    // ------------------------------------------------------------ trimming

    fun markIn(now: Double) {
        if (template) return
        s0 = minOf(maxOf(now, s1 - MAX_LEN), s1 - MIN_LEN).coerceAtLeast(0.0)
        dirty = true
    }

    fun markOut(now: Double) {
        if (template) return
        s1 = minOf(maxOf(minOf(now, s0 + MAX_LEN), s0 + MIN_LEN), info.duration)
        dirty = true
    }

    fun trimmed(a: Double, b: Double) {
        if (template) return
        s0 = a
        s1 = b
        dirty = true
    }

    fun widen() {
        v0 = maxOf(0.0, v0 - 30)
        v1 = minOf(info.duration, v1 + 30)
    }
}
