package com.deadlockclipper.app.util

/** An error with a plain-language message for the user and technical details for the log. */
class FriendlyError(override val message: String, val details: String = "") : Exception(message)

/** The user pressed Cancel. */
class Cancelled : Exception("Cancelled")

/** Cancel flag and progress reporting handed to long-running work. */
class Task(private val onProgress: (Double, String) -> Unit = { _, _ -> }, private val parent: Task? = null) {
    @Volatile
    private var flag = false
    private var last = 0L

    val isCancelled: Boolean get() = flag || parent?.isCancelled == true

    fun cancel() {
        flag = true
    }

    fun check() {
        if (isCancelled) throw Cancelled()
    }

    /** Throttled to 10 updates per second, like the PC version. */
    fun progress(fraction: Double, message: String = "") {
        val now = System.nanoTime()
        if (now - last >= 100_000_000L || fraction >= 1.0) {
            last = now
            onProgress(fraction.coerceIn(0.0, 1.0), message)
        }
    }

    /** Progress of a sub-step mapped into [from, to] of the whole. */
    fun sub(from: Double, to: Double) = Task({ f, m -> onProgress((from + (to - from) * f).coerceIn(0.0, 1.0), m) }, this)
}
