package com.oblivion.djayclone

import kotlin.math.abs
import kotlin.math.roundToInt

/** BPM correction helpers (half / double / 3:2 / typed value). Pure Kotlin. */
object BpmMath {
    const val MIN_BPM = 40f
    const val MAX_BPM = 260f

    private fun inRange(v: Float) = !v.isNaN() && !v.isInfinite() && v in MIN_BPM..MAX_BPM
    private fun round2(v: Float) = (v * 100f).roundToInt() / 100f

    fun times(bpm: Float, factor: Float): Float? = (bpm * factor).takeIf { inRange(it) }?.let(::round2)
    fun halve(bpm: Float): Float? = times(bpm, 0.5f)
    fun double(bpm: Float): Float? = times(bpm, 2f)

    /** Typed BPM -> value, or null. Accepts ',' as the decimal separator;
     * rejects exponents, NaN/Infinity, signs and anything outside range. */
    fun parse(text: String?): Float? {
        val t = text?.trim()?.replace(',', '.') ?: return null
        if (t.isEmpty() || !t.all { it.isDigit() || it == '.' } || t.count { it == '.' } > 1) return null
        val v = t.toFloatOrNull() ?: return null
        return if (inRange(v)) round2(v) else null
    }

    /** True for a value a saved record may carry. */
    fun isValid(bpm: Float?): Boolean = bpm != null && inRange(bpm)

    /** Nearest loop chip (in beats) for a loop of [lengthMs] at [bpm], within
     * [tol] fraction, else null (= manual loop, no beat label). */
    fun relabelLoopBeats(lengthMs: Long, bpm: Float, chips: List<Float>, tol: Float = 0.02f): Float? {
        if (lengthMs <= 0 || !inRange(bpm)) return null
        val beats = lengthMs / (60_000f / bpm)
        val best = chips.minByOrNull { abs(it - beats) } ?: return null
        return best.takeIf { abs(best - beats) <= best * tol }
    }
}
