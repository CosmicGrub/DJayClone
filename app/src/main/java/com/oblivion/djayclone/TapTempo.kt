package com.oblivion.djayclone

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TapResult(val bpm: Float?, val taps: Int, val spreadPct: Float, val stable: Boolean)

/**
 * Tap tempo: tolerant of a missed beat and precise over many taps.
 *
 * Each interval is assigned a whole number of beats (a skipped tap counts as
 * 2), then tap time is fitted against cumulative beat index by least squares;
 * BPM = 60000 / slope. Timestamps are injected so there is no clock here.
 */
class TapTempo(
    private val resetGapMs: Long = 2000L,
    private val debounceMs: Long = 100L,
    private val maxTaps: Int = 12,
) {
    private val taps = ArrayList<Long>()

    fun reset() = taps.clear()

    fun tap(nowMs: Long): TapResult {
        if (taps.isNotEmpty()) {
            val gap = nowMs - taps.last()
            if (gap < debounceMs) return result()
            if (gap > resetGapMs) taps.clear()
        }
        taps.add(nowMs)
        while (taps.size > maxTaps) taps.removeAt(0)
        return result()
    }

    private fun result(): TapResult {
        val n = taps.size
        if (n < 2) return TapResult(null, n, 0f, false)
        val intervals = (1 until n).map { (taps[it] - taps[it - 1]).toDouble() }
        val sorted = intervals.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        val idx = DoubleArray(n)
        for (i in 1 until n) idx[i] = idx[i - 1] + max(1, (intervals[i - 1] / median).roundToInt())
        val t = DoubleArray(n) { (taps[it] - taps[0]).toDouble() }
        val mi = idx.average()
        val mt = t.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (idx[i] - mi) * (t[i] - mt)
            den += (idx[i] - mi) * (idx[i] - mi)
        }
        if (den <= 0.0) return TapResult(null, n, 0f, false)
        val slope = num / den
        if (slope <= 0.0) return TapResult(null, n, 0f, false)
        val intercept = mt - slope * mi
        var ss = 0.0
        for (i in 0 until n) {
            val e = t[i] - (intercept + slope * idx[i])
            ss += e * e
        }
        val spread = (if (n > 2) sqrt(ss / (n - 2)) / slope * 100.0 else 0.0).toFloat()
        val bpm = (60_000.0 / slope).toFloat()
        val ok = !bpm.isNaN() && bpm in BpmMath.MIN_BPM..BpmMath.MAX_BPM
        return TapResult(if (ok) bpm else null, n, spread, ok && n >= 6 && abs(spread) < 2.5f)
    }
}
