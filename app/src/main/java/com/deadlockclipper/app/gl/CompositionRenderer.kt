package com.deadlockclipper.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.deadlockclipper.core.Comp
import com.deadlockclipper.core.Composition
import com.deadlockclipper.core.Layer
import com.deadlockclipper.core.VideoInfo
import java.nio.FloatBuffer

/** A video frame to draw from: a decoder's SurfaceTexture (OES) or a bitmap uploaded as a 2D texture. */
class Source(val tex: Int, val oes: Boolean, val matrix: FloatArray)

/**
 * Draws a composition with OpenGL: the blurred background, the video layers and the text layers.
 * Used for the editor's live preview, the thumbnails and the exported clips, so they all match.
 *
 * Coordinates: everything is placed in "space" pixels (the canvas, or the source frame) with the
 * origin at the top-left; the current viewport shows exactly that space.
 */
class CompositionRenderer {
    private val progOes = GlUtil.program(VERTEX, FRAG_HEADER_OES + FRAG_BODY)
    private val prog2d = GlUtil.program(VERTEX, FRAG_HEADER_2D + FRAG_BODY)
    private val progBlur = GlUtil.program(VERTEX, FRAG_BLUR)
    private val progColor = GlUtil.program(VERTEX, FRAG_COLOR)
    private val pos: FloatBuffer = GlUtil.floats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val uv: FloatBuffer = GlUtil.floats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private var blurA: Fbo? = null
    private var blurB: Fbo? = null
    private val texts = HashMap<String, Int>() // text layer + size -> texture

    private var spaceW = 1f
    private var spaceH = 1f

    /** Sets the coordinate space for the following draws (the viewport must already be set). */
    fun space(w: Number, h: Number) {
        spaceW = w.toFloat()
        spaceH = h.toFloat()
    }

    private fun program(p: Int, tex: Int, target: Int, matrix: FloatArray) {
        GLES20.glUseProgram(p)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "sTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uTex"), 1, false, matrix, 0)
    }

    private fun quad(x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float) {
        quadCorners(x, y, w, h, floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1))
    }

    /** uvs: top-left, top-right, bottom-left, bottom-right (image coordinates, origin top-left). */
    private fun quadCorners(x: Float, y: Float, w: Float, h: Float, uvs: FloatArray) {
        val x0 = x / spaceW * 2 - 1
        val x1 = (x + w) / spaceW * 2 - 1
        val y0 = 1 - y / spaceH * 2
        val y1 = 1 - (y + h) / spaceH * 2
        pos.position(0)
        pos.put(floatArrayOf(x0, y0, x1, y0, x0, y1, x1, y1)).position(0)
        uv.position(0)
        uv.put(uvs).position(0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Draws part of a frame (uv rect in image fractions) into a rect of the current space. */
    fun drawImage(src: Source, u0: Float, v0: Float, u1: Float, v1: Float, x: Float, y: Float, w: Float, h: Float, bright: Float = 0f, alpha: Float = 1f) {
        val p = if (src.oes) progOes else prog2d
        program(p, src.tex, if (src.oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, src.matrix)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uBright"), bright)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uAlpha"), alpha)
        quad(x, y, w, h, u0, v0, u1, v1)
    }

    /** A 2D texture drawn rotated 90 degrees (used when the encoder only accepts the other shape). */
    fun drawRotated90(tex: Int, x: Float, y: Float, w: Float, h: Float) {
        program(prog2d, tex, GLES20.GL_TEXTURE_2D, GlUtil.IDENTITY)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog2d, "uBright"), 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog2d, "uAlpha"), 1f)
        quadCorners(x, y, w, h, floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f))
    }

    fun fill(r: Float, g: Float, b: Float, a: Float, x: Float, y: Float, w: Float, h: Float) {
        GLES20.glUseProgram(progColor)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progColor, "uTex"), 1, false, GlUtil.IDENTITY, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(progColor, "uColor"), r * a, g * a, b * a, a)
        quad(x, y, w, h, 0f, 0f, 1f, 1f)
    }

    private fun blend(on: Boolean) {
        if (on) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // premultiplied alpha
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    /**
     * The finished clip. The caller binds the target and sets the viewport to the canvas area.
     * [restore] re-binds that target after the background blur used its own buffers.
     */
    fun drawComp(src: Source?, comp: Comp, info: VideoInfo, outW: Int, outH: Int, restore: () -> Unit) {
        val (cw, ch) = comp.size
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        blend(false)
        if (src != null) {
            blurredBackground(src, info, cw, ch)
            restore()
            space(cw, ch)
            val a = blurA!!
            drawImage(Source(a.texture, false, GlUtil.IDENTITY), 0f, 0f, 1f, 1f, 0f, 0f, cw.toFloat(), ch.toFloat(), bright = -0.06f)
        } else {
            restore()
            space(cw, ch)
            fill(0.11f, 0.12f, 0.17f, 1f, 0f, 0f, cw.toFloat(), ch.toFloat())
        }
        val used = HashSet<String>()
        for (lay in comp.layers) {
            if (lay.hidden) continue
            if (lay.isVideo) {
                val r = Composition.videoRect(lay, info, comp)
                val (sx, sy, sw, sh) = lay.src
                if (src != null) {
                    blend(false)
                    drawImage(src, sx.toFloat(), sy.toFloat(), (sx + sw).toFloat(), (sy + sh).toFloat(), r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat())
                } else {
                    blend(true)
                    fill(1f, 1f, 1f, 0.08f, r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat())
                }
            } else if (lay.isText && lay.text.isNotBlank()) {
                val key = textKey(lay, cw, ch, outW, outH)
                used.add(key)
                val tex = texts.getOrPut(key) {
                    val bmp = TextRender.bitmap(lay, cw, ch, outW, outH)
                    GlUtil.texture2d().also { GlUtil.upload(it, bmp); bmp.recycle() }
                }
                blend(true)
                drawImage(Source(tex, false, GlUtil.FLIP_Y), 0f, 0f, 1f, 1f, 0f, 0f, cw.toFloat(), ch.toFloat())
            }
        }
        blend(false)
        // Forget text images that are no longer used (edited or deleted layers).
        val stale = texts.keys - used
        for (k in stale) GlUtil.deleteTexture(texts.remove(k)!!)
    }

    private fun textKey(lay: Layer, cw: Int, ch: Int, outW: Int, outH: Int) = "$lay|$cw|$ch|$outW|$outH"

    /** Like ffmpeg's scale (cover) + crop + boxblur=10:2 at a quarter of the canvas size. */
    private fun blurredBackground(src: Source, info: VideoInfo, cw: Int, ch: Int) {
        val bw = maxOf(8, cw / 4)
        val bh = maxOf(8, ch / 4)
        if (blurA?.width != bw || blurA?.height != bh) {
            blurA?.release(); blurB?.release()
            blurA = Fbo(bw, bh)
            blurB = Fbo(bw, bh)
        }
        val a = blurA!!
        val b = blurB!!
        // Scale the frame to cover the background and crop the middle.
        val frameAspect = info.width.toFloat() / info.height
        val bgAspect = bw.toFloat() / bh
        var u0 = 0f
        var v0 = 0f
        var u1 = 1f
        var v1 = 1f
        if (frameAspect > bgAspect) {
            val keep = bgAspect / frameAspect
            u0 = (1 - keep) / 2; u1 = u0 + keep
        } else {
            val keep = frameAspect / bgAspect
            v0 = (1 - keep) / 2; v1 = v0 + keep
        }
        a.bind()
        space(bw, bh)
        drawImage(src, u0, v0, u1, v1, 0f, 0f, bw.toFloat(), bh.toFloat())
        repeat(2) {
            blurPass(a, b, 1f / bw, 0f)
            blurPass(b, a, 0f, 1f / bh)
        }
    }

    private fun blurPass(from: Fbo, to: Fbo, dx: Float, dy: Float) {
        to.bind()
        space(to.width, to.height)
        program(progBlur, from.texture, GLES20.GL_TEXTURE_2D, GlUtil.IDENTITY)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progBlur, "uStep"), dx, dy)
        quad(0f, 0f, to.width.toFloat(), to.height.toFloat(), 0f, 0f, 1f, 1f)
    }

    /**
     * The source video with each video layer's area marked (editor "Source video" view), drawn
     * into the rect (x, y, w, h) of the current space (the caller sets viewport and space).
     */
    fun drawSource(src: Source?, comp: Comp, x: Float, y: Float, w: Float, h: Float) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        blend(false)
        if (src == null) {
            fill(0.11f, 0.12f, 0.17f, 1f, x, y, w, h)
            return
        }
        drawImage(src, 0f, 0f, 1f, 1f, x, y, w, h)
        blend(true)
        fill(0f, 0f, 0f, 90f / 255f, x, y, w, h)
        blend(false)
        for (lay in comp.layers) {
            if (!lay.isVideo) continue
            val (sx, sy, sw, sh) = lay.src.map { it.toFloat() }
            drawImage(src, sx, sy, sx + sw, sy + sh, x + sx * w, y + sy * h, sw * w, sh * h) // undimmed inside
        }
    }

    fun release() {
        blurA?.release(); blurB?.release()
        blurA = null; blurB = null
        for (t in texts.values) GlUtil.deleteTexture(t)
        texts.clear()
        for (p in listOf(progOes, prog2d, progBlur, progColor)) GLES20.glDeleteProgram(p)
    }

    companion object {
        private const val VERTEX = """
attribute vec2 aPos;
attribute vec2 aUV;
uniform mat4 uTex;
varying vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = (uTex * vec4(aUV.x, 1.0 - aUV.y, 0.0, 1.0)).xy;
}
"""
        private const val FRAG_HEADER_OES = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES sTex;
"""
        private const val FRAG_HEADER_2D = """precision mediump float;
uniform sampler2D sTex;
"""
        private const val FRAG_BODY = """
varying vec2 vTex;
uniform float uBright;
uniform float uAlpha;
void main() {
    vec4 c = texture2D(sTex, vTex);
    gl_FragColor = vec4(clamp(c.rgb + uBright * c.a, 0.0, 1.0), c.a) * uAlpha;
}
"""
        private const val FRAG_BLUR = """precision mediump float;
uniform sampler2D sTex;
uniform vec2 uStep;
varying vec2 vTex;
void main() {
    vec4 sum = vec4(0.0);
    for (int i = -10; i <= 10; i++) {
        sum += texture2D(sTex, vTex + float(i) * uStep);
    }
    gl_FragColor = sum / 21.0;
}
"""
        private const val FRAG_COLOR = """precision mediump float;
uniform vec4 uColor;
varying vec2 vTex;
void main() {
    gl_FragColor = uColor;
}
"""
    }
}
