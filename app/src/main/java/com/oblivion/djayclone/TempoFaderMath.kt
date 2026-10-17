package com.oblivion.djayclone

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Maths for the per-deck tempo (pitch) fader. Pure Kotlin.
 *
 * Fader position runs -1..+1 left to right. Normally right = faster; with
 * [inverted] right = slower. [rangePct] is the fader's full-scale tempo change.
 */
object TempoFaderMath {
    val ALLOWED_RANGES = listOf(4, 6, 8, 10, 16, 25, 50)
    const val DEFAULT_RANGE = 8

    /** Within this of exactly 1.0 the fader reads dead centre: a detent so
     * "back to original tempo" is bit-exact (Sonic is bypassed at 1.0). */
    const val DETENT = 0.001f

    fun coerceRange(pct: Int): Int = if (pct in ALLOWED_RANGES) pct else DEFAULT_RANGE

    fun speedForPosition(pos: Float, rangePct: Int, inverted: Boolean = false): Float {
        val p = (if (pos.isNaN()) 0f else pos).coerceIn(-1f, 1f) * (if (inverted) -1f else 1f)
        val speed = 1f + p * coerceRange(rangePct) / 100f
        if (abs(speed - 1f) <= DETENT) return 1f
        return speed.coerceIn(DeckSpeedRange.MIN, DeckSpeedRange.MAX)
    }

    data class FaderPos(val pos: Float, val beyondRange: Boolean)

    /** Where the thumb sits for [speed]; if the tempo is outside the fader's
     * range (set by sync, nudges or a wider setting) the thumb pins to the
     * end and [FaderPos.beyondRange] is true. */
    fun positionForSpeed(speed: Float, rangePct: Int, inverted: Boolean = false): FaderPos {
        val range = coerceRange(rangePct) / 100f
        val raw = (speed - 1f) / range * (if (inverted) -1f else 1f)
        val clamped = raw.coerceIn(-1f, 1f)
        return FaderPos(clamped, abs(raw) > 1f + 1e-4f)
    }

    /** `+3.25%`, `−0.50%` (U+2212), `0.00%` - never `-0.00%`. */
    fun formatDelta(speed: Float): String {
        val hundredths = ((speed - 1f) * 10_000f).roundToInt()
        if (hundredths == 0) return "0.00%"
        val sign = if (hundredths > 0) "+" else "−"
        val a = abs(hundredths)
        return "%s%d.%02d%%".format(sign, a / 100, a % 100)
    }
}

/** Limits how often a 60 Hz drag reaches the audio engine: a value passes if
 * [minIntervalMs] elapsed since the last accepted one and it moved by at
 * least [minDelta]. The caller applies the final value on release. */
class ParamGovernor(private val minIntervalMs: Long = 66L, private val minDelta: Float = 0.0001f) {
    private var lastT = Long.MIN_VALUE
    private var lastV = Float.NaN

    fun offer(nowMs: Long, value: Float): Float? {
        val first = lastV.isNaN()
        if (!first && (nowMs - lastT < minIntervalMs || abs(value - lastV) < minDelta)) return null
        lastT = nowMs; lastV = value
        return value
    }

    fun reset() { lastT = Long.MIN_VALUE; lastV = Float.NaN }
}
