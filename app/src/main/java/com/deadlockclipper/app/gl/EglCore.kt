package com.deadlockclipper.app.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** An OpenGL ES 2 context for the current thread (used off-screen and for the video encoder). */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    val context: EGLContext

    init {
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("No EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw RuntimeException("eglInitialize failed")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        var ok = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0
        if (!ok) {
            // Some drivers don't offer "recordable" configs together with pbuffers; try without.
            val plain = attribs.copyOf().also { it[it.indexOf(EGL_RECORDABLE_ANDROID) + 1] = EGL14.EGL_DONT_CARE }
            ok = EGL14.eglChooseConfig(display, plain, 0, configs, 0, 1, num, 0) && num[0] > 0
        }
        if (!ok) throw RuntimeException("No suitable EGL config")
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
    }

    fun windowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        return s
    }

    fun pbuffer(w: Int = 1, h: Int = 1): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        if (s == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        return s
    }

    fun makeCurrent(s: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) throw RuntimeException("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
    }

    fun swap(s: EGLSurface) = EGL14.eglSwapBuffers(display, s)

    fun presentationTime(s: EGLSurface, ns: Long) = EGLExt.eglPresentationTimeANDROID(display, s, ns)

    fun releaseSurface(s: EGLSurface) {
        EGL14.eglDestroySurface(display, s)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        // Not terminating the display: other threads may still be using it.
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
