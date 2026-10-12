package com.deadlockclipper.app.gl

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/** A decoder output surface: frames arrive on a SurfaceTexture bound to an OES texture. */
class FrameWaiter(val texture: Int) : SurfaceTexture.OnFrameAvailableListener {
    val surfaceTexture = SurfaceTexture(texture)
    val surface = Surface(surfaceTexture)
    val matrix = FloatArray(16)
    private val lock = Object()
    private var available = false

    init {
        surfaceTexture.setOnFrameAvailableListener(this, handler)
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(lock) {
            available = true
            lock.notifyAll()
        }
    }

    /** Waits for the frame just released to the surface and latches it into the texture. */
    fun await(timeoutMs: Long = 2500): Boolean {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!available) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            available = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(matrix)
        return true
    }

    fun source() = Source(texture, true, matrix)

    fun release() {
        surface.release()
        surfaceTexture.release()
    }

    companion object {
        private val thread = HandlerThread("frame-callbacks").apply { start() }
        private val handler = Handler(thread.looper)
    }
}
