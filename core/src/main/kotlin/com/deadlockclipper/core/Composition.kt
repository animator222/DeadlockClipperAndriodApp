package com.deadlockclipper.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a finished clip looks like: a canvas (1080x1920 or 1920x1080) with a blurred background
 * and layers on top, bottom to top.
 *
 *   video layer: a part of the source video ("src", fractions x, y, w, h of the source frame)
 *                placed on the canvas ("dst": centre x, centre y and width, as fractions of the
 *                canvas; the height follows from the source part's shape). A layer may be bigger
 *                than the canvas - that is how the gameplay gets zoomed.
 *   text layer:  text with font, size, colour, shadow and outline, centred at (cx, cy).
 *
 * The same description drives the live preview in the editor, the thumbnails and the export.
 */
@Serializable
data class Layer(
    val type: String = "video", // "video" or "text"
    val name: String = "Video",
    val src: List<Double> = listOf(0.0, 0.0, 1.0, 1.0),
    val dst: List<Double> = listOf(0.5, 0.5, 1.0),
    val hidden: Boolean = false,
    // text layers
    val text: String = "YOUR TEXT",
    val font: String = "Anton",
    val size: Double = 0.06, // fraction of the canvas's long side
    val bold: Boolean = false,
    val color: String = "#ffffff",
    val shadow: Boolean = true,
    @SerialName("shadow_color") val shadowColor: String = "#000000",
    @SerialName("shadow_strength") val shadowStrength: Double = 0.6,
    val outline: Boolean = true,
    @SerialName("outline_color") val outlineColor: String = "#000000",
    @SerialName("outline_width") val outlineWidth: Double = 0.10,
    val cx: Double = 0.5,
    val cy: Double = 0.15,
) {
    val isVideo get() = type == "video"
    val isText get() = type == "text"
}

@Serializable
data class Comp(val canvas: String = "vertical", val layers: List<Layer> = emptyList()) {
    val size: Pair<Int, Int> get() = Composition.CANVAS[canvas] ?: Composition.CANVAS.getValue("vertical")
}

object Composition {
    val CANVAS = mapOf("vertical" to (1080 to 1920), "horizontal" to (1920 to 1080))

    fun textLayer() = Layer(type = "text", name = "Text")

    /** Width (fraction of the canvas) at which the source part just fits the canvas, times zoom. */
    fun fitWidth(info: VideoInfo, src: List<Double>, canvas: String, zoom: Double = 1.0): Double {
        val (cw, ch) = CANVAS.getValue(canvas)
        val sw = src[2] * info.width
        val sh = src[3] * info.height
        return min(cw / sw, ch / sh) * zoom * sw / cw
    }

    /** Full frame (zoom 1) or zoomed gameplay, centred, on the blurred background. */
    fun simple(info: VideoInfo, orientation: String, zoom: Double = 1.0): Comp {
        val src = listOf(0.0, 0.0, 1.0, 1.0)
        return Comp(
            orientation,
            listOf(Layer(type = "video", name = "Gameplay", src = src, dst = listOf(0.5, 0.5, fitWidth(info, src, orientation, maxOf(1.0, zoom))))),
        )
    }

    /** A new layer showing a corner of the source, placed near the top of the canvas. */
    fun newVideoLayer(info: VideoInfo, name: String = "Webcam"): Layer {
        val src = if (info.width >= info.height) listOf(0.02, 0.70, 0.22, 0.27) else listOf(0.05, 0.05, 0.40, 0.15)
        return Layer(type = "video", name = name, src = src, dst = listOf(0.5, 0.15, 0.45))
    }

    private fun validBox(v: List<Double>?) = v != null && v.size == 4 && v[2] > 0.01 && v[3] > 0.01

    /** A valid composition or null (used for saved layouts and per-clip edits). */
    fun clean(comp: Comp?): Comp? {
        if (comp == null || comp.canvas !in CANVAS) return null
        val layers = comp.layers.mapNotNull { lay ->
            when {
                lay.isVideo && validBox(lay.src) && lay.dst.size == 3 && lay.dst[2] > 0.01 ->
                    lay.copy(name = lay.name.ifBlank { "Video" }.take(30), src = lay.src.map { it.coerceIn(0.0, 1.0) })
                lay.isText -> lay.copy(text = lay.text.take(300))
                else -> null
            }
        }
        if (layers.none { it.isVideo }) return null
        return Comp(comp.canvas, layers)
    }

    /** Saved layouts depend on the source shape and the output shape. */
    fun templateKey(info: VideoInfo, orientation: String) = (if (info.portrait) "portrait" else "landscape") + ">" + orientation

    /** The clip's own edited layout, or the default from the settings. */
    fun forClip(clip: Clip?, settings: Settings, info: VideoInfo): Comp {
        clean(clip?.comp)?.let { return it }
        val orientation = settings.outputOrientation
        if (settings.verticalFormat == "custom") {
            val saved = clean(settings.myLayouts[templateKey(info, orientation)])
            if (saved != null && saved.canvas == orientation) return saved
        }
        val zoom = if (settings.verticalFormat == "zoom" || settings.verticalFormat == "custom") settings.zoomLevel else 1.0
        return simple(info, orientation, zoom)
    }

    /** (x, y, w, h) of a video layer on the canvas, in canvas pixels (may go past the edges). */
    fun videoRect(layer: Layer, info: VideoInfo, comp: Comp): DoubleArray {
        val (cw, ch) = comp.size
        val (_, _, sw, sh) = layer.src
        val (cx, cy, wf) = layer.dst
        val w = wf * cw
        val h = w * (sh * info.height) / maxOf(1e-6, sw * info.width)
        return doubleArrayOf(cx * cw - w / 2, cy * ch - h / 2, w, h)
    }

    fun even(v: Double) = maxOf(2, (v / 2).roundToInt() * 2)

    /** Short description of a saved layout, front to back. */
    fun describe(comp: Comp) = comp.layers.reversed().joinToString(", ") { if (it.isVideo) it.name else "text" }
}
