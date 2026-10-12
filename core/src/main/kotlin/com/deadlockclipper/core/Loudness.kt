package com.deadlockclipper.core

/** Game-audio loudness: one RMS value (dB) per HOP seconds, and fight intensity from it. */
object Loudness {
    const val SAMPLE_RATE = 16000
    const val HOP = 0.1 // one loudness value per 0.1 s
    const val HIGHPASS_HZ = 150.0

    /**
     * Loudness relative to the "normal" level around that moment of the match.
     *
     * Farming and walking around happen all match long, so they become the baseline and
     * fights stand out above it. Returns (short-term, sustained) intensity in dB above baseline.
     */
    fun intensity(curveDb: FloatArray): Pair<FloatArray, FloatArray> {
        val n = curveDb.size
        if (n == 0) return FloatArray(0) to FloatArray(0)
        val block = 100 // 10 s blocks
        val blocks = maxOf(1, n / block)
        val medians = FloatArray(blocks) { i -> Num.median(curveDb, i * block, (i + 1) * block).toFloat() }
        // Rolling median over ~90 s of blocks gives a slowly changing "normal" level.
        val k = 4
        val smooth = DoubleArray(blocks) { i -> Num.median(medians, maxOf(0, i - k), i + k + 1) }
        val centers = DoubleArray(blocks) { (it + 0.5) * block }
        val rel = DoubleArray(n) { i -> curveDb[i] - Num.interp(i.toDouble(), centers, smooth) }
        return Num.movingAverage(rel, 5) to Num.movingAverage(rel, 30)
    }

    /** Which parts of the video to scan. Normally all of it; quick scan only the louder parts. */
    fun scanPlan(audioDb: FloatArray, duration: Double, quick: Boolean): List<DoubleArray> {
        if (!quick || audioDb.isEmpty()) return listOf(doubleArrayOf(0.0, duration))
        val sustained = intensity(audioDb).second
        val level = Num.percentile(sustained, SMART_SCAN_PERCENTILE)
        val segments = ArrayList<DoubleArray>()
        for (r in Num.runs(BooleanArray(sustained.size) { sustained[it] > level })) {
            val start = maxOf(0.0, r[0] * HOP - SCAN_PAD_BEFORE)
            val end = minOf(duration, r[1] * HOP + SCAN_PAD_AFTER)
            val last = segments.lastOrNull()
            if (last != null && start <= last[1] + 10) last[1] = maxOf(last[1], end)
            else segments.add(doubleArrayOf(start, end))
        }
        val covered = segments.sumOf { it[1] - it[0] }
        if (covered > 0.7 * duration || segments.isEmpty()) return listOf(doubleArrayOf(0.0, duration))
        return segments
    }

    const val SMART_SCAN_PERCENTILE = 60.0
    const val SCAN_PAD_BEFORE = 10.0
    const val SCAN_PAD_AFTER = 12.0
}
