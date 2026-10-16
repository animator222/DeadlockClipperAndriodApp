package com.deadlockclipper.app.ui.editor

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.deadlockclipper.app.gl.TextRender
import com.deadlockclipper.app.ui.C
import com.deadlockclipper.core.Composition
import com.deadlockclipper.core.Layer
import kotlin.math.abs
import kotlin.math.max

private const val SNAP = 0.012 // snap to the centre lines within this fraction of the canvas

/** Selection boxes and handles over the preview, and touch editing of the layers. */
@Composable
fun EditorOverlay(st: EditorState, onTouched: () -> Unit) {
    val handlePx = with(LocalDensity.current) { 26.dp.toPx() }
    val tagText = with(LocalDensity.current) { 12.dp.toPx() }
    val touched by rememberUpdatedState(onTouched)

    Canvas(
        Modifier.fillMaxSize().pointerInput(st) {
            awaitEachGesture {
                val down = awaitFirstDown()
                touched()
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val drag = press(st, w, h, down.position, handlePx)
                var pinching = false
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        pinching = true
                        val zoom = event.calculateZoom()
                        if (st.mode == "source") {
                            // Source view: pinch zooms into the video, two fingers move around it.
                            val c = event.calculateCentroid()
                            val pan = event.calculatePan()
                            st.zoomSource(zoom, c.x, c.y, pan.x, pan.y, w, h)
                        } else if (zoom != 1f) {
                            // Canvas: pinch zooms the selected layer (like the mouse wheel on the PC).
                            st.updateCurrent { lay ->
                                if (lay.isVideo) lay.copy(dst = listOf(lay.dst[0], lay.dst[1], (lay.dst[2] * zoom).coerceIn(0.03, 6.0)))
                                else lay.copy(size = (lay.size * zoom).coerceIn(0.008, 0.4))
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } else if (!pinching && drag != null) {
                        val p = pressed[0].position
                        if (pressed[0].positionChange() != Offset.Zero) {
                            if (drag.kind.startsWith("src")) dragSource(st, w, h, drag, p) else dragCanvas(st, w, h, drag, p)
                            pressed[0].consume()
                        }
                    } else if (!pinching && st.mode == "source" && st.srcZoom > 1f) {
                        // One finger on an empty part of the zoomed video moves around it.
                        val d = pressed[0].positionChange()
                        if (d != Offset.Zero) {
                            st.panSource(d.x, d.y, w, h)
                            pressed[0].consume()
                        }
                    }
                }
                st.guideX = false
                st.guideY = false
            }
        },
    ) {
        val comp = st.comp
        if (st.mode == "source") {
            val sr = st.sourceRect(size.width, size.height)
            comp.layers.forEachIndexed { i, lay ->
                if (!lay.isVideo) return@forEachIndexed
                val r = srcBox(lay, sr)
                if (i == st.sel) selection(r, C.layer(i), lay.name, handlePx, tagText)
                else {
                    drawRect(C.layer(i), Offset(r.left, r.top), Size(r.width(), r.height()), style = Stroke(2.dp.toPx()))
                    label(lay.name, r.left + 6, r.top + tagText + 4, C.layer(i), tagText)
                }
            }
        } else {
            val (cw, ch) = comp.size
            val (cr, _) = Stage.fit(size.width, size.height, cw.toFloat(), ch.toFloat())
            drawRect(Color(1f, 1f, 1f, 40f / 255f), Offset(cr.left, cr.top), Size(cr.width(), cr.height()), style = Stroke(1f))
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            if (st.guideX) drawLine(Color(0xFFFF4FD8), Offset(cr.centerX(), cr.top), Offset(cr.centerX(), cr.bottom), 2f, pathEffect = dash)
            if (st.guideY) drawLine(Color(0xFFFF4FD8), Offset(cr.left, cr.centerY()), Offset(cr.right, cr.centerY()), 2f, pathEffect = dash)
            val lay = comp.layers.getOrNull(st.sel)
            if (lay != null && !lay.hidden) {
                val r = toWidget(layerBox(st, st.sel), cr, cw)
                selection(r, C.layer(st.sel), if (lay.isText) "Text" else lay.name, handlePx, tagText)
            }
        }
    }
}

private fun DrawScope.label(text: String, x: Float, y: Float, color: Color, textPx: Float) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb(); textSize = textPx; isFakeBoldText = true
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

private fun DrawScope.selection(r: RectF, color: Color, name: String, handlePx: Float, textPx: Float) {
    drawRect(color, Offset(r.left, r.top), Size(r.width(), r.height()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
    val hs = handlePx * 0.45f
    for (c in corners(r).values) {
        drawRect(color, Offset(c.x - hs / 2, c.y - hs / 2), Size(hs, hs))
        drawRect(Color(0xFF0B0C10), Offset(c.x - hs / 2, c.y - hs / 2), Size(hs, hs), style = Stroke(1f))
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = textPx; isFakeBoldText = true }
    val tw = max(60f, paint.measureText(name) + 14)
    val top = (r.top - textPx - 10).coerceAtLeast(0f)
    drawRect(color, Offset(r.left, top), Size(tw, textPx + 8))
    paint.color = 0xFF0B0C10.toInt()
    drawContext.canvas.nativeCanvas.drawText(name, r.left + 7, top + textPx + 1, paint)
}

private fun corners(r: RectF) = mapOf(
    "tl" to Offset(r.left, r.top), "tr" to Offset(r.right, r.top),
    "bl" to Offset(r.left, r.bottom), "br" to Offset(r.right, r.bottom),
)

private fun hitCorner(r: RectF, p: Offset, handle: Float): String? =
    corners(r).entries.firstOrNull { abs(p.x - it.value.x) <= handle && abs(p.y - it.value.y) <= handle }?.key

/** Layer bounds in canvas pixels. */
private fun layerBox(st: EditorState, i: Int): RectF {
    val lay = st.comp.layers[i]
    val (cw, ch) = st.comp.size
    if (lay.isVideo) {
        val r = Composition.videoRect(lay, st.info, st.comp)
        return RectF(r[0].toFloat(), r[1].toFloat(), (r[0] + r[2]).toFloat(), (r[1] + r[3]).toFloat())
    }
    return TextRender.box(lay, cw, ch)
}

private fun toWidget(r: RectF, cr: RectF, cw: Int): RectF {
    val k = cr.width() / cw
    return RectF(cr.left + r.left * k, cr.top + r.top * k, cr.left + r.right * k, cr.top + r.bottom * k)
}

private fun srcBox(lay: Layer, sr: RectF): RectF {
    val (x, y, w, h) = lay.src.map { it.toFloat() }
    return RectF(sr.left + x * sr.width(), sr.top + y * sr.height(), sr.left + (x + w) * sr.width(), sr.top + (y + h) * sr.height())
}

private class Drag(val kind: String, val corner: String?, val start: Offset, val orig: Layer, val r0: RectF?)

private fun press(st: EditorState, w: Float, h: Float, p: Offset, handle: Float): Drag? {
    val comp = st.comp
    if (st.mode == "source") {
        val sr = st.sourceRect(w, h)
        st.current?.takeIf { it.isVideo }?.let { lay ->
            val r = srcBox(lay, sr)
            hitCorner(r, p, handle)?.let { return Drag("src-resize", it, p, lay, r) }
        }
        for (i in comp.layers.indices.reversed()) {
            val lay = comp.layers[i]
            if (lay.isVideo && srcBox(lay, sr).contains(p.x, p.y)) {
                st.select(i)
                return Drag("src-move", null, p, lay, srcBox(lay, sr))
            }
        }
        return null
    }
    val (cw, ch) = comp.size
    val (cr, _) = Stage.fit(w, h, cw.toFloat(), ch.toFloat())
    st.current?.takeIf { !it.hidden }?.let { lay ->
        val r = toWidget(layerBox(st, st.sel), cr, cw)
        hitCorner(r, p, handle)?.let { return Drag("resize", it, p, lay, r) }
    }
    for (i in comp.layers.indices.reversed()) { // top-most first
        val lay = comp.layers[i]
        if (!lay.hidden && toWidget(layerBox(st, i), cr, cw).contains(p.x, p.y)) {
            st.select(i)
            return Drag("move", null, p, lay, null)
        }
    }
    return null
}

private fun dragCanvas(st: EditorState, w: Float, h: Float, d: Drag, p: Offset) {
    val (cw, ch) = st.comp.size
    val (cr, k) = Stage.fit(w, h, cw.toFloat(), ch.toFloat())
    val orig = d.orig
    if (d.kind == "move") {
        val dx = (p.x - d.start.x) / k / cw
        val dy = (p.y - d.start.y) / k / ch
        var cx = (if (orig.isVideo) orig.dst[0] else orig.cx) + dx
        var cy = (if (orig.isVideo) orig.dst[1] else orig.cy) + dy
        val snapX = abs(cx - 0.5) < SNAP
        val snapY = abs(cy - 0.5) < SNAP
        if (snapX) cx = 0.5
        if (snapY) cy = 0.5
        st.guideX = snapX
        st.guideY = snapY
        st.updateCurrent { lay -> if (lay.isVideo) lay.copy(dst = listOf(cx, cy, lay.dst[2])) else lay.copy(cx = cx, cy = cy) }
        return
    }
    // Resize from a corner; the opposite corner stays put, the shape is kept.
    val r0 = d.r0 ?: return
    val opp = when (d.corner) {
        "tl" -> Offset(r0.right, r0.bottom)
        "tr" -> Offset(r0.left, r0.bottom)
        "bl" -> Offset(r0.right, r0.top)
        else -> Offset(r0.left, r0.top)
    }
    val scale = max(0.05f, max(abs(p.x - opp.x) / max(1f, r0.width()), abs(p.y - opp.y) / max(1f, r0.height())))
    val nw = r0.width() * scale
    val nh = r0.height() * scale
    val sx = if (d.corner == "tl" || d.corner == "bl") -1 else 1
    val sy = if (d.corner == "tl" || d.corner == "tr") -1 else 1
    val cxW = opp.x + sx * nw / 2
    val cyW = opp.y + sy * nh / 2
    val cx = ((cxW - cr.left) / k / cw).toDouble()
    val cy = ((cyW - cr.top) / k / ch).toDouble()
    st.updateCurrent { lay ->
        if (lay.isVideo) lay.copy(dst = listOf(cx, cy, maxOf(0.03, orig.dst[2] * scale)))
        else lay.copy(size = (orig.size * scale).coerceIn(0.008, 0.4), cx = cx, cy = cy)
    }
}

private fun dragSource(st: EditorState, w: Float, h: Float, d: Drag, p: Offset) {
    val sr = st.sourceRect(w, h)
    val (x, y, sw, sh) = d.orig.src
    if (d.kind == "src-move") {
        val nx = (x + (p.x - d.start.x) / sr.width()).coerceIn(0.0, 1 - sw)
        val ny = (y + (p.y - d.start.y) / sr.height()).coerceIn(0.0, 1 - sh)
        st.updateCurrent { it.copy(src = listOf(nx, ny, sw, sh)) }
        return
    }
    val px = ((p.x - sr.left) / sr.width()).toDouble().coerceIn(0.0, 1.0)
    val py = ((p.y - sr.top) / sr.height()).toDouble().coerceIn(0.0, 1.0)
    var x0 = x
    var y0 = y
    var x1 = x + sw
    var y1 = y + sh
    val c = d.corner ?: "br"
    if ('l' in c) x0 = minOf(px, x1 - 0.02) else x1 = maxOf(px, x0 + 0.02)
    if ('t' in c) y0 = minOf(py, y1 - 0.02) else y1 = maxOf(py, y0 + 0.02)
    // Keep the layer's size on the canvas roughly the same when its source area changes.
    st.updateCurrent { lay ->
        lay.copy(src = listOf(x0, y0, x1 - x0, y1 - y0), dst = listOf(lay.dst[0], lay.dst[1], d.orig.dst[2] * (x1 - x0) / maxOf(1e-6, sw)))
    }
}
