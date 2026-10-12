package com.deadlockclipper.app.util

import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's log file (logs/deadlock_clipper.log), like the PC version: everything important is
 * written there so a problem can be reported by sharing one file.
 */
object AppLog {
    private const val MAX_BYTES = 2_000_000L
    private const val BACKUPS = 5
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    val logFile: File? get() = file

    fun init(dir: File, version: String) {
        dir.mkdirs()
        file = File(dir, "deadlock_clipper.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                e("crash", "Unhandled error on ${thread.name}", e)
                File(dir, "crash.log").appendText("${stamp.format(Date())} ${stackTrace(e)}\n")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
        i("startup", "=".repeat(70))
        i("startup", "Deadlock Clipper $version starting")
        i("startup", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) on ${Build.MANUFACTURER} ${Build.MODEL}, ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    fun d(tag: String, msg: String) = write("DEBUG", tag, msg, null)
    fun i(tag: String, msg: String) = write("INFO", tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = write("WARNING", tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = write("ERROR", tag, msg, e)

    fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    @Synchronized
    private fun write(level: String, tag: String, msg: String, e: Throwable?) {
        when (level) {
            "DEBUG" -> Log.d("DLC/$tag", msg)
            "INFO" -> Log.i("DLC/$tag", msg)
            "WARNING" -> Log.w("DLC/$tag", msg, e)
            else -> Log.e("DLC/$tag", msg, e)
        }
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) rotate(f)
            val line = "${stamp.format(Date())} ${level.padEnd(7)} $tag: $msg\n" + (e?.let { stackTrace(it) } ?: "")
            f.appendText(line)
        } catch (_: Throwable) {
        }
    }

    private fun rotate(f: File) {
        for (i in BACKUPS - 1 downTo 1) {
            val src = File(f.path + ".$i")
            if (src.exists()) src.renameTo(File(f.path + ".${i + 1}"))
        }
        f.renameTo(File(f.path + ".1"))
    }

    /** The last part of the log, for "Copy error details". */
    fun tail(maxChars: Int = 20_000): String = try {
        val text = file?.readText().orEmpty()
        text.takeLast(maxChars)
    } catch (_: Throwable) {
        ""
    }
}
