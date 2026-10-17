package com.oblivion.djayclone

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Deck-transport arithmetic: loop resize/move, beat jump, waveform-tap to
 * position, and clock formatting. Pure Kotlin (no Android/Compose/ExoPlayer)
 * so every edge case is unit-tested on the JVM (TransportMathTest); the deck
 * ViewModel and Compose layer only do state plumbing around these.
 *
 * All positions are SOURCE-time milliseconds (ExoPlayer media positions,
 * which advance at file speed whatever the playback-speed setting is), and
 * every beat length comes from the track's ANALYSED base BPM - see
 * [BeatTimeMath] for why using the speed-adjusted tempo would apply the
 * speed twice.
 */
object TransportMath {

    /** Shortest loop the deck will arm. Matches DeckViewModel's historical
     * MIN_LOOP_MS - one definition so resize maths and the arming code agree. */
    const val MIN_LOOP_MS = 20L

    /** Landing exactly on the last millisecond risks racing ExoPlayer's own
     * transition to STATE_ENDED (see DeckViewModel.setBeatLoop). */
    const val END_MARGIN_MS = 5L

    /** Highest position a seek/loop may use. Never negative: a track shorter
     * than the end margin used to make `coerceIn(0, duration - margin)` throw. */
    private fun ceilingMs(durationMs: Long): Long = (durationMs - END_MARGIN_MS).coerceAtLeast(0L)

    /** True when [loop]'s recorded length really is [Loop.beats] beats at
     * [baseBpm]. A beat loop that was clamped by the end of the track keeps
     * its original beat label but not its length, and resizing it from the
     * label would grow it past the end (it would never loop). */
    private fun beatLabelIsHonest(loop: Loop, baseBpm: Float?): Boolean {
        val beats = loop.beats ?: return false
        if (baseBpm == null || baseBpm <= 0f) return false
        return abs(loop.lengthMs - BeatTimeMath.beatsToSourceMs(baseBpm, beats)) <= 2.0
    }

    /** In/out of a loop, in source ms. Always out > in. */
    data class Loop(val inMs: Long, val outMs: Long, val beats: Float?) {
        val lengthMs: Long get() = outMs - inMs
    }

    // ---- loop resize / move ------------------------------------------------

    /** Half the loop length, keeping IN fixed. A beat loop is recomputed from
     * the BPM (so halving then doubling returns exactly to the original
     * rather than accumulating millisecond truncation); a manual loop halves
     * its own length. Null if the result would be shorter than [MIN_LOOP_MS]. */
    fun halve(loop: Loop, baseBpm: Float?): Loop? {
        val beats = loop.beats
        if (beats != null && baseBpm != null && beatLabelIsHonest(loop, baseBpm)) {
            val nb = beats / 2f
            val len = BeatTimeMath.beatsToSourceMs(baseBpm, nb).roundToLong()
            return if (len >= MIN_LOOP_MS) Loop(loop.inMs, loop.inMs + len, nb) else null
        }
        val len = loop.lengthMs / 2
        return if (len >= MIN_LOOP_MS) Loop(loop.inMs, loop.inMs + len, null) else null
    }

    /** Twice the loop length, keeping IN fixed. Null if there is no room left
     * before the end of the track for a strictly longer loop. */
    fun double(loop: Loop, baseBpm: Float?, durationMs: Long): Loop? {
        if (durationMs <= 0) return null
        val ceiling = ceilingMs(durationMs)
        val beats = loop.beats
        val candidate = if (beats != null && baseBpm != null && beatLabelIsHonest(loop, baseBpm)) {
            val nb = beats * 2f
            Loop(loop.inMs, loop.inMs + BeatTimeMath.beatsToSourceMs(baseBpm, nb).roundToLong(), nb)
        } else {
            Loop(loop.inMs, loop.inMs + loop.lengthMs * 2, null)
        }
        return if (candidate.outMs <= ceiling) candidate else null
    }

    /** Slides the whole loop by one loop-length toward the end (+1) or the
     * start (-1), keeping its length. If a full step does not fit, slides as
     * far as the track allows; null if it cannot move at all. */
    fun move(loop: Loop, direction: Int, durationMs: Long): Loop? {
        if (durationMs <= 0 || direction == 0) return null
        val len = loop.lengthMs
        val ceiling = ceilingMs(durationMs)
        // Room is clamped at zero: a loop whose out already sits at/above the
        // ceiling used to get a NEGATIVE room and "move later" slid it earlier.
        val room = (ceiling - loop.outMs).coerceAtLeast(0L)
        val shift = if (direction > 0) minOf(len, room) else -minOf(len, loop.inMs)
        if (shift == 0L) return null
        return Loop(loop.inMs + shift, loop.outMs + shift, loop.beats)
    }

    // ---- beat jump ---------------------------------------------------------

    /** Where a beat jump of [beats] (negative = backward) from [positionMs]
     * lands, clamped to the playable range. Null if BPM is unknown, or the
     * jump would not move at all (already at the start/end). */
    fun beatJumpTarget(positionMs: Long, baseBpm: Float?, beats: Float, durationMs: Long): Long? {
        if (baseBpm == null || baseBpm <= 0f || durationMs <= 0 || beats == 0f) return null
        val delta = BeatTimeMath.beatsToSourceMs(baseBpm, beats).roundToLong()
        val target = (positionMs + delta).coerceIn(0L, ceilingMs(durationMs))
        return if (target == positionMs) null else target
    }

    /** When a loop is active, a jump is expected to keep the playhead inside
     * it (CDJ "loop shift" behaviour) rather than escape it: wrap the target
     * back into [loop] with modular arithmetic. Outside a loop, unchanged. */
    fun wrapIntoLoop(targetMs: Long, loop: Loop?): Long {
        if (loop == null || loop.lengthMs <= 0) return targetMs
        if (targetMs in loop.inMs until loop.outMs) return targetMs
        val rel = Math.floorMod(targetMs - loop.inMs, loop.lengthMs)
        return loop.inMs + rel
    }

    // ---- waveform tap / drag -----------------------------------------------

    /** Waveform x-fraction (0..1 across the strip) -> a seek position in ms,
     * clamped to the playable range. Null for an unloaded track. */
    fun fractionToPositionMs(fraction: Float, durationMs: Long): Long? {
        if (durationMs <= 0) return null
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        return (f.toDouble() * durationMs).roundToLong().coerceIn(0L, ceilingMs(durationMs))
    }

    // ---- clock formatting --------------------------------------------------

    /** "m:ss", or "h:mm:ss" from one hour up. Truncates (never rounds up), so
     * the clock never shows a second that has not fully elapsed. */
    fun formatClock(ms: Long): String {
        val total = (if (ms < 0) 0 else ms) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "-m:ss" time remaining, rounded UP to the next whole second so the
     * readout reaches "-0:00" only at the very end (a countdown, not an
     * elapsed clock, should not show 0 while there is still audio). */
    fun formatRemaining(positionMs: Long, durationMs: Long): String {
        val remaining = (durationMs - positionMs).coerceAtLeast(0L)
        val rounded = ((remaining + 999) / 1000) * 1000
        return "-" + formatClock(rounded)
    }

    /** True while less than [warnSeconds] of the track remain - drives the
     * track-end warning (flash/colour). False for an unloaded track and
     * once the track has ended. */
    fun isNearEnd(positionMs: Long, durationMs: Long, warnSeconds: Int = 30): Boolean {
        if (durationMs <= 0) return false
        val remaining = durationMs - positionMs
        return remaining in 1..(warnSeconds * 1000L)
    }

    /** Are two positions the same to within one UI tick? Used to avoid
     * rewriting state for sub-tick noise. */
    fun samePosition(a: Long, b: Long, toleranceMs: Long = 1L): Boolean = abs(a - b) <= toleranceMs
}
