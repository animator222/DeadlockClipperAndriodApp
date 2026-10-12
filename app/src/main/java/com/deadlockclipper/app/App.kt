package com.deadlockclipper.app

import android.app.Application
import com.deadlockclipper.app.gl.TextRender
import com.deadlockclipper.app.util.AppLog
import com.deadlockclipper.app.util.Paths
import org.opencv.android.OpenCVLoader

class App : Application() {
    lateinit var paths: Paths
        private set

    override fun onCreate() {
        super.onCreate()
        paths = Paths(this)
        AppLog.init(paths.logs, BuildConfig.VERSION_NAME)
        TextRender.init(assets)
        val cv = try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            AppLog.e("startup", "OpenCV failed to load", e)
            false
        }
        AppLog.i("startup", "OpenCV loaded: $cv")
    }
}
