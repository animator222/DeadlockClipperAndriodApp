package com.deadlockclipper.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
import android.os.Build
import com.deadlockclipper.app.gl.CompositionRenderer
import com.deadlockclipper.app.gl.EglCore
import com.deadlockclipper.app.gl.Fbo
import com.deadlockclipper.app.gl.GlUtil
import com.deadlockclipper.app.gl.Source
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.core.Comp
import com.deadlockclipper.core.VideoInfo
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Single frames of the video, and finished-look pictures of a composition (thumbnails, previews). */
object Previews {
    private val gl = Executors.newSingleThreadExecutor { r -> Thread(r, "preview-gl") }
    private var egl: EglCore? = null
    private var renderer: CompositionRenderer? = null

    private val retrieverLock = Any()
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUri: String? = null

    /** A frame at [t] seconds (rotation applied), or null if the phone can't produce one. */
    fun frameAt(context: Context, uri: String, t: Double, exact: Boolean = true): Bitmap? = synchronized(retrieverLock) {
        try {
            if (retrieverUri != uri) {
                retriever?.release()
                retriever = MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(uri)) }
                retrieverUri = uri
            }
            val us = (maxOf(0.0, t) * 1e6).toLong()
            val r = retriever!!
            r.getFrameAtTime(us, if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            AppLog.w("preview", "No frame at %.1fs: $e".format(t))
            retriever?.release()
            retriever = null
            retrieverUri = null
            null
        }
    }

    /** A small frame for the editor's filmstrip. */
    fun stripFrame(context: Context, uri: String, t: Double, w: Int, h: Int): Bitmap? = synchronized(retrieverLock) {
        val full = if (Build.VERSION.SDK_INT >= 27) {
            try {
                if (retrieverUri != uri) frameAt(context, uri, 0.0, false)?.recycle()
                retriever?.getScaledFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
            } catch (e: Exception) {
                null
            }
        } else {
            frameAt(context, uri, t, false)?.let { b -> Bitmap.createScaledBitmap(b, w, h, true).also { if (it != b) b.recycle() } }
        }
        full
    }

    fun releaseRetriever() = synchronized(retrieverLock) {
        retriever?.release()
        retriever = null
        retrieverUri = null
    }

    /** The finished look of [comp] on [frame], [outW] x [outH] pixels. Call from a background thread. */
    fun render(frame: Bitmap?, comp: Comp, info: VideoInfo, outW: Int, outH: Int): Bitmap = gl.submit(Callable {
        if (egl == null) {
            egl = EglCore().also { it.makeCurrent(it.pbuffer()) }
            renderer = CompositionRenderer()
        }
        val r = renderer!!
        val fbo = Fbo(outW, outH)
        var tex = 0
        try {
            val src = frame?.let {
                tex = GlUtil.texture2d()
                GlUtil.upload(tex, it)
                Source(tex, false, GlUtil.FLIP_Y)
            }
            val restore = {
                fbo.bind()
            }
            restore()
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            r.drawComp(src, comp, info, outW, outH, restore)
            fbo.bind()
            GlUtil.readBitmap(outW, outH)
        } finally {
            GlUtil.deleteTexture(tex)
            fbo.release()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }).get()

    /** Preview size for a canvas: [longSide] pixels on its long side. */
    fun previewSize(comp: Comp, longSide: Int): Pair<Int, Int> {
        val (cw, ch) = comp.size
        return if (cw >= ch) longSide to (longSide * ch / cw) else (longSide * cw / ch) to longSide
    }
}
