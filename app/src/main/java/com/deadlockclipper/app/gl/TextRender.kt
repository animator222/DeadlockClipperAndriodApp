package com.deadlockclipper.app.gl

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.core.Layer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws text layers. The same code paints the editor preview, the thumbnails and the exported
 * clips, so the export looks exactly like the editor.
 */
object TextRender {
    private class FontDef(val file: String?, val system: Typeface? = null, val boldFile: String? = null, val variable: Boolean = false)

    private val fonts = linkedMapOf(
        "Anton" to FontDef("Anton-Regular.ttf"),
        "Bangers" to FontDef("Bangers-Regular.ttf"),
        "Bebas Neue" to FontDef("BebasNeue-Regular.ttf"),
        "Luckiest Guy" to FontDef("LuckiestGuy-Regular.ttf"),
        "Montserrat" to FontDef("Montserrat-Variable.ttf", variable = true),
        "Oswald" to FontDef("Oswald-Variable.ttf", variable = true),
        "Poppins" to FontDef("Poppins-Bold.ttf", boldFile = "Poppins-ExtraBold.ttf"),
        "Russo One" to FontDef("RussoOne-Regular.ttf"),
        // Good-looking fonts every Android phone has.
        "Roboto Black" to FontDef(null, Typeface.create("sans-serif-black", Typeface.NORMAL)),
        "Roboto Condensed" to FontDef(null, Typeface.create("sans-serif-condensed", Typeface.BOLD)),
        "Serif" to FontDef(null, Typeface.create(Typeface.SERIF, Typeface.BOLD)),
    )
    private val cache = HashMap<String, Typeface>()
    private lateinit var assets: AssetManager

    fun init(assets: AssetManager) {
        this.assets = assets
    }

    fun families(): List<String> = fonts.keys.toList()

    @Synchronized
    fun typeface(name: String, bold: Boolean): Pair<Typeface, Boolean> {
        val def = fonts[name] ?: fonts.getValue("Anton")
        // Returns the typeface and whether bold has to be faked (single-weight fonts).
        val key = "$name|$bold"
        val fakeBold = bold && !def.variable && def.boldFile == null && def.system == null
        cache[key]?.let { return it to fakeBold }
        val tf = try {
            when {
                def.system != null -> if (bold) Typeface.create(def.system, Typeface.BOLD) else def.system
                def.variable -> Typeface.Builder(assets, "fonts/${def.file}")
                    .setFontVariationSettings("'wght' ${if (bold) 850 else 600}").build()
                bold && def.boldFile != null -> Typeface.createFromAsset(assets, "fonts/${def.boldFile}")
                else -> Typeface.createFromAsset(assets, "fonts/${def.file}")
            }
        } catch (e: Exception) {
            AppLog.w("text", "Could not load font $name", e)
            Typeface.DEFAULT_BOLD
        }
        cache[key] = tf
        return tf to fakeBold
    }

    fun parseColor(hex: String, fallback: Int = Color.WHITE): Int = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        fallback
    }

    class Shape(val path: Path, val px: Float, val fakeBold: Boolean) {
        val bounds = RectF().also { path.computeBounds(it, true) }
    }

    /** The text outline, centred at the layer's position, in canvas pixels. */
    fun shape(layer: Layer, cw: Int, ch: Int): Shape? {
        if (layer.text.isBlank()) return null
        val px = (layer.size * max(cw, ch)).toFloat().coerceAtLeast(4f) // size is a fraction of the long side
        val (tf, fakeBold) = typeface(layer.font, layer.bold)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = px
        }
        val fm = paint.fontMetrics
        val lineSpacing = -fm.ascent + fm.descent + fm.leading
        val path = Path()
        var y = 0f
        for (line in layer.text.split("\n")) {
            val w = paint.measureText(line)
            val p = Path()
            paint.getTextPath(line, 0, line.length, -w / 2, y - fm.ascent, p)
            path.addPath(p)
            y += lineSpacing
        }
        val box = RectF()
        path.computeBounds(box, true)
        if (box.isEmpty) box.set(-px, 0f, px, px)
        path.offset((layer.cx * cw).toFloat() - box.centerX(), (layer.cy * ch).toFloat() - box.centerY())
        return Shape(path, px, fakeBold)
    }

    /** Paint one text layer on a canvas that works in canvas pixels. Returns its bounding box. */
    fun draw(c: Canvas, layer: Layer, cw: Int, ch: Int): RectF {
        val shape = shape(layer, cw, ch) ?: return RectF()
        val px = shape.px
        val outlineW = if (layer.outline) (layer.outlineWidth * px).toFloat() else 0f
        val boldW = if (shape.fakeBold) px / 22f else 0f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = if (boldW > 0) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            strokeWidth = boldW
            strokeJoin = Paint.Join.ROUND
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = outlineW * 2 + boldW
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        if (layer.shadow) {
            val strength = layer.shadowStrength.coerceIn(0.0, 1.0).toFloat()
            val offset = px * 0.07f * strength
            val soft = px * 0.06f * strength
            val steps = 10
            val base = parseColor(layer.shadowColor, Color.BLACK)
            val alpha = (255 * min(1f, 0.25f + strength) / steps * 2.2f).toInt().coerceIn(0, 255)
            val color = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
            fill.color = color
            stroke.color = color
            val moved = Path()
            for (k in 0 until steps) { // several offset copies make a soft drop shadow
                val a = 2 * Math.PI * k / steps
                shape.path.offset(offset + soft * cos(a).toFloat(), offset + soft * sin(a).toFloat(), moved)
                c.drawPath(moved, fill)
                if (outlineW > 0) c.drawPath(moved, stroke)
            }
        }
        if (outlineW > 0) {
            stroke.color = parseColor(layer.outlineColor, Color.BLACK)
            c.drawPath(shape.path, stroke)
        }
        fill.color = parseColor(layer.color)
        c.drawPath(shape.path, fill)
        return RectF(shape.bounds).apply { inset(-outlineW, -outlineW) }
    }

    /** Bounds of a text layer in canvas pixels, with a little padding (for selecting it). */
    fun box(layer: Layer, cw: Int, ch: Int): RectF {
        val s = shape(layer, cw, ch) ?: return RectF(
            (layer.cx * cw - 40).toFloat(), (layer.cy * ch - 20).toFloat(), (layer.cx * cw + 40).toFloat(), (layer.cy * ch + 20).toFloat(),
        )
        val pad = s.px * 0.15f
        return RectF(s.bounds).apply { inset(-pad, -pad) }
    }

    /** A transparent outW x outH image of the whole canvas with just this text layer. */
    fun bitmap(layer: Layer, cw: Int, ch: Int, outW: Int, outH: Int): Bitmap {
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(outW.toFloat() / cw, outH.toFloat() / ch)
        draw(c, layer, cw, ch)
        return bmp
    }
}
