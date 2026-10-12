package com.deadlockclipper.app.util

import android.content.Context
import java.io.File

/** Where the app keeps its files (all inside the app's own storage). */
class Paths(context: Context) {
    private val base: File = context.getExternalFilesDir(null) ?: context.filesDir
    val logs = File(base, "logs").apply { mkdirs() }
    val downloads = File(base, "downloads").apply { mkdirs() }
    val cache = File(context.filesDir, "analysis").apply { mkdirs() } // saved scans
    val tmp = File(context.cacheDir, "work").apply { mkdirs() } // thumbnails, clips being encoded
    val settings = File(context.filesDir, "settings.json")
    val analysisReport get() = File(logs, "last_analysis.txt")
    val detections get() = File(logs, "detections.jpg")
}
