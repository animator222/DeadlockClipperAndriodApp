package com.deadlockclipper.core

import kotlin.math.floor

/** Small numpy look-alikes, so the ported analysis gives the same numbers as the PC app. */
object Num {
    /** numpy.percentile with linear interpolation (the numpy default). */
    fun percentile(values: FloatArray, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf().also { it.sort() }
        return percentileSorted(sorted, p)
    }

    fun percentileSorted(sorted: FloatArray, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val pos = (sorted.size - 1) * p / 100.0
        val lo = floor(pos).toInt().coerceIn(0, sorted.size - 1)
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val t = pos - lo
        // numpy's _lerp, with the float32 result numpy gives for float32 input.
        val a = sorted[lo].toDouble()
        val b = sorted[hi].toDouble()
        val diff = b - a
        val v = if (t >= 0.5) b - diff * (1 - t) else a + diff * t
        return v.toFloat().toDouble()
    }

    fun median(values: FloatArray, from: Int = 0, to: Int = values.size): Double {
        val a = from.coerceIn(0, values.size)
        val b = to.coerceIn(a, values.size)
        if (b <= a) return Double.NaN
        val part = values.copyOfRange(a, b).also { it.sort() }
        val n = part.size
        // numpy returns float32 for float32 input, so the mean of the middle two is float32 too.
        return if (n % 2 == 1) part[n / 2].toDouble() else ((part[n / 2 - 1] + part[n / 2]) / 2f).toDouble()
    }

    /** numpy.convolve(x, ones(n, float32)/n, mode="same") - summed the same way, so ties come out the same. */
    fun movingAverage(x: DoubleArray, n: Int): FloatArray {
        if (n <= 1 || x.isEmpty()) return FloatArray(x.size) { x[it].toFloat() }
        val k = (1f / n).toDouble() // numpy's kernel is float32
        val shift = (n - 1) / 2
        return FloatArray(x.size) { i ->
            val hiIdx = minOf(i + shift, x.size - 1)
            val loIdx = maxOf(i + shift - (n - 1), 0)
            var sum = 0.0
            for (m in hiIdx downTo loIdx) sum += x[m] * k
            sum.toFloat()
        }
    }

    /** numpy.interp for increasing xp: values outside are clamped to the ends. */
    fun interp(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
        if (xp.isEmpty()) return 0.0
        if (x <= xp[0]) return fp[0]
        if (x >= xp[xp.size - 1]) return fp[fp.size - 1]
        var lo = 0
        var hi = xp.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xp[mid] <= x) lo = mid else hi = mid
        }
        val t = (x - xp[lo]) / (xp[hi] - xp[lo])
        return fp[lo] + (fp[hi] - fp[lo]) * t
    }

    /** (start, endExclusive) for each run of true values. */
    fun runs(mask: BooleanArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < mask.size) {
            if (mask[i]) {
                val start = i
                while (i < mask.size && mask[i]) i++
                out.add(intArrayOf(start, i))
            } else {
                i++
            }
        }
        return out
    }

    /** numpy.searchsorted(sorted, v) with side="left". */
    fun searchSorted(sorted: FloatArray, v: Double): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** Like Python's f"{x:.0f}": rounds half to even on the exact binary value. */
fun fmt0(x: Double): String = java.math.BigDecimal(x).setScale(0, java.math.RoundingMode.HALF_EVEN).toPlainString()

fun fmtTime(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** 1:05.3 - minutes, seconds with one decimal (editor timeline). */
fun fmtPrecise(t: Double): String {
    val v = t.coerceAtLeast(0.0)
    val m = floor(v / 60).toInt()
    val s = v - m * 60
    return "%d:%04.1f".format(m, s)
}
