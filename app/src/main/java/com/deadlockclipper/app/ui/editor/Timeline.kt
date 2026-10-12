package com.deadlockclipper.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.deadlockclipper.core.fmtTime

/** Filmstrip with draggable start/end handles and a playhead. Tap anywhere to jump there. */
@Composable
fun Timeline(st: EditorState, thumbs: List<ImageBitmap>, onSeek: (Double) -> Unit) {
    val density = LocalDensity.current
    val stripH = with(density) { 64.dp.toPx() }
    val handleW = with(density) { 14.dp.toPx() }
    val touch = with(density) { 14.dp.toPx() }
    val textPx = with(density) { 11.dp.toPx() }
    val seek by rememberUpdatedState(onSeek)

    Canvas(
        Modifier.fillMaxWidth().height(96.dp).pointerInput(st) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat()
                fun x(t: Double) = ((t - st.v0) / (st.v1 - st.v0) * w).toFloat()
                fun t(px: Float) = st.v0 + px.coerceIn(0f, w) / maxOf(1f, w) * (st.v1 - st.v0)
                val x0 = x(st.s0)
                val x1 = x(st.s1)
                val px = down.position.x
                val kind = when {
                    st.template -> "seek"
                    px in (x0 - handleW - touch)..(x0 + touch) -> "start"
                    px in (x1 - touch)..(x1 + handleW + touch) -> "end"
                    else -> "seek"
                }
                fun apply(p: Float) {
                    val tt = t(p)
                    when (kind) {
                        "start" -> {
                            st.trimmed(minOf(maxOf(tt, st.s1 - MAX_LEN, st.v0), st.s1 - MIN_LEN), st.s1)
                            seek(st.s0)
                        }
                        "end" -> {
                            st.trimmed(st.s0, maxOf(minOf(tt, st.s0 + MAX_LEN, st.v1), st.s0 + MIN_LEN))
                            seek(st.s1)
                        }
                        else -> seek(tt)
                    }
                }
                apply(px)
                down.consume()
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    apply(c.position.x)
                    c.consume()
                }
            }
        },
    ) {
        val w = size.width
        fun x(t: Double) = ((t - st.v0) / (st.v1 - st.v0) * w).toFloat()
        drawRect(Color(0xFF0D0E11), Offset.Zero, Size(w, stripH))
        if (thumbs.isNotEmpty()) {
            val tw = w / thumbs.size
            thumbs.forEachIndexed { i, img ->
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset((i * tw).toInt(), 0), dstSize = IntSize(tw.toInt() + 1, stripH.toInt()))
            }
        }
        val x0 = x(st.s0)
        val x1 = x(st.s1)
        val dim = Color(0f, 0f, 0f, 160f / 255f)
        drawRect(dim, Offset.Zero, Size(maxOf(0f, x0), stripH))
        drawRect(dim, Offset(x1, 0f), Size(maxOf(0f, w - x1), stripH))
        val len = st.s1 - st.s0
        val color = if (len in MIN_LEN..MAX_LEN) Color(0xFFF0B35A) else Color(0xFFFF6B6B)
        if (!st.template) {
            drawRect(color, Offset(x0, 1.5f), Size(x1 - x0, stripH - 3), style = Stroke(3.dp.toPx()))
            drawRoundRect(color, Offset(x0 - handleW, 0f), Size(handleW, stripH), CornerRadius(4f))
            drawRoundRect(color, Offset(x1, 0f), Size(handleW, stripH), CornerRadius(4f))
            for (hx in listOf(x0 - handleW / 2, x1 + handleW / 2)) {
                drawLine(Color(0xFF15171C), Offset(hx, stripH * 0.35f), Offset(hx, stripH * 0.65f), 2.dp.toPx())
            }
        }
        // time ruler
        val span = st.v1 - st.v0
        val step = listOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800).firstOrNull { span / it <= 8 } ?: 3600
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFF9A9CA5.toInt(); textSize = textPx; textAlign = android.graphics.Paint.Align.CENTER
        }
        var t = (Math.floor(st.v0 / step) + 1) * step
        while (t < st.v1) {
            val xx = x(t)
            drawLine(Color(0xFF9A9CA5), Offset(xx, stripH), Offset(xx, stripH + 5), 1f)
            drawContext.canvas.nativeCanvas.drawText(fmtTime(t), xx, stripH + 8 + textPx, paint)
            t += step
        }
        // playhead
        val px = x(st.pos)
        drawLine(Color.White, Offset(px, 0f), Offset(px, stripH + 4), 2.dp.toPx())
        drawCircle(Color.White, 4.dp.toPx(), Offset(px, stripH + 4))
    }
}
