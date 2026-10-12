package com.deadlockclipper.app.ui.editor

import android.content.Context
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import com.deadlockclipper.app.gl.CompositionRenderer
import com.deadlockclipper.app.gl.GlUtil
import com.deadlockclipper.app.gl.Source
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.core.Comp
import com.deadlockclipper.core.VideoInfo
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Where the canvas / source frame sits inside the stage (shared by the GL view and the touch overlay). */
object Stage {
    const val PAD = 16f

    fun fit(viewW: Float, viewH: Float, contentW: Float, contentH: Float): Pair<RectF, Float> {
        val aw = viewW - 2 * PAD
        val ah = viewH - 2 * PAD
        val k = minOf(aw / contentW, ah / contentH).coerceAtLeast(0.0001f)
        val w = contentW * k
        val h = contentH * k
        val l = (viewW - w) / 2
        val t = (viewH - h) / 2
        return RectF(l, t, l + w, t + h) to k
    }
}

/**
 * The editor's live preview: the video player renders into a SurfaceTexture and every frame is
 * drawn with the same renderer the export uses, so the preview is exactly the finished clip.
 */
class EditorGlView(context: Context, private val onSurface: (Surface?) -> Unit) : GLSurfaceView(context) {
    @Volatile
    var comp: Comp? = null
        set(v) { field = v; requestRender() }

    @Volatile
    var info: VideoInfo? = null

    @Volatile
    var sourceMode = false
        set(v) { field = v; requestRender() }

    private val renderer = object : Renderer {
        private var r: CompositionRenderer? = null
        private var st: SurfaceTexture? = null
        private var tex = 0
        private val matrix = FloatArray(16)
        private var hasFrame = false
        @Volatile private var frameWaiting = false
        private var w = 1
        private var h = 1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                r = CompositionRenderer()
                tex = GlUtil.oesTexture()
                val texture = SurfaceTexture(tex)
                texture.setOnFrameAvailableListener {
                    frameWaiting = true
                    requestRender()
                }
                st?.release()
                st = texture
                hasFrame = false
                val surface = Surface(texture)
                post { onSurface(surface) }
            } catch (e: Exception) {
                AppLog.e("editor", "Preview setup failed", e)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            w = width
            h = height
        }

        override fun onDrawFrame(gl: GL10?) {
            val renderer = r ?: return
            val texture = st ?: return
            try {
                if (frameWaiting) {
                    frameWaiting = false
                    texture.updateTexImage()
                    texture.getTransformMatrix(matrix)
                    hasFrame = true
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, w, h)
                GLES20.glClearColor(0.043f, 0.047f, 0.063f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val c = comp ?: return
                val v = info ?: return
                val src = if (hasFrame) Source(tex, true, matrix) else null
                if (sourceMode) {
                    val (rect, _) = Stage.fit(w.toFloat(), h.toFloat(), v.width.toFloat(), v.height.toFloat())
                    viewport(rect)
                    renderer.drawSource(src, c)
                } else {
                    val (cw, ch) = c.size
                    val (rect, _) = Stage.fit(w.toFloat(), h.toFloat(), cw.toFloat(), ch.toFloat())
                    val restore = {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        viewport(rect)
                    }
                    restore()
                    renderer.drawComp(src, c, v, rect.width().toInt().coerceAtLeast(2), rect.height().toInt().coerceAtLeast(2), restore)
                }
            } catch (e: Exception) {
                AppLog.e("editor", "Preview draw failed", e)
            }
        }

        private fun viewport(r: RectF) {
            GLES20.glViewport(r.left.toInt(), (h - r.bottom).toInt(), r.width().toInt(), r.height().toInt())
        }

        fun release() {
            st?.release()
            st = null
        }
    }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun releaseTexture() = renderer.release()
}
