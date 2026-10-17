package com.oblivion.djayclone

import com.oblivion.djayclone.TransportMath.Loop
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportMathTest {

    private val dur = 200_000L
    private val ceiling = dur - TransportMath.END_MARGIN_MS // 199_995
    private val bpm = 120f // 1 beat = 500 ms

    // ---- halve -------------------------------------------------------------

    @Test
    fun halvingAManualLoopHalvesItsLengthAndKeepsIn() {
        val h = TransportMath.halve(Loop(10_000, 14_000, null), bpm)!!
        assertEquals(10_000L, h.inMs)
        assertEquals(12_000L, h.outMs)
        assertNull(h.beats)
    }

    @Test
    fun halvingABeatLoopIsRecomputedFromTheBpm() {
        val h = TransportMath.halve(Loop(0, 2_000, 4f), bpm)!!
        assertEquals(2f, h.beats!!, 0f)
        assertEquals(1_000L, h.outMs)
    }

    @Test
    fun halvingBelowTheMinimumLoopIsRefused() {
        assertNull(TransportMath.halve(Loop(0, 30, null), bpm)) // 15 ms < 20 ms
        assertNotNull(TransportMath.halve(Loop(0, 40, null), bpm)) // exactly 20 ms is fine
        assertNull(TransportMath.halve(Loop(0, 20, 1f / 64f), bpm)) // beat-loop path refuses too
    }

    // ---- double ------------------------------------------------------------

    @Test
    fun doublingGrowsTheLoopKeepingIn() {
        val d = TransportMath.double(Loop(100_000, 101_000, null), bpm, dur)!!
        assertEquals(100_000L, d.inMs)
        assertEquals(102_000L, d.outMs)
    }

    @Test
    fun doublingStopsAtTheEndOfTheTrack() {
        assertNull(TransportMath.double(Loop(199_000, 199_900, null), bpm, dur))
        assertNull(TransportMath.double(Loop(190_000, 195_000, null), bpm, dur)) // would end at 200_000
        // Landing exactly on the ceiling is still allowed.
        val ok = TransportMath.double(Loop(149_995, 174_995, null), bpm, dur)!!
        assertEquals(ceiling, ok.outMs)
        assertNull(TransportMath.double(Loop(0, 1_000, null), bpm, 0L)) // unloaded track
    }

    @Test
    fun halveThenDoubleReturnsToTheOriginalBeatLoopWithoutDrift() {
        // An awkward tempo, where per-step millisecond truncation would drift.
        val odd = 127.3f
        val start = Loop(5_000, 5_000 + BeatTimeMath.beatsToSourceMs(odd, 4f).toLong(), 4f)
        var l = start
        repeat(3) { l = TransportMath.halve(l, odd)!! }
        assertEquals(0.5f, l.beats!!, 0f)
        repeat(3) { l = TransportMath.double(l, odd, dur)!! }
        assertEquals(4f, l.beats!!, 0f)
        assertTrue("within 1 ms of the original", abs(l.lengthMs - start.lengthMs) <= 1)
    }

    @Test
    fun aClampedBeatLoopIsResizedFromItsRealLengthNotItsLabel() {
        // An 8-beat label on a loop the end of the track squeezed to ~3 beats.
        val clamped = Loop(198_000, 199_995, 8f) // 1_995 ms, but 8 beats @120 = 4_000 ms
        val h = TransportMath.halve(clamped, bpm)!!
        assertEquals(997L, h.lengthMs)
        assertNull("the beat label is dropped, it no longer describes the loop", h.beats)
        assertNull("doubling must not pass the end", TransportMath.double(Loop(198_000, 199_995, 8f), bpm, dur))
    }

    // ---- move --------------------------------------------------------------

    @Test
    fun movingSlidesByOneLoopLengthKeepingTheLength() {
        val l = Loop(10_000, 12_000, 4f)
        val fwd = TransportMath.move(l, +1, dur)!!
        assertEquals(12_000L, fwd.inMs); assertEquals(14_000L, fwd.outMs); assertEquals(4f, fwd.beats!!, 0f)
        val back = TransportMath.move(l, -1, dur)!!
        assertEquals(8_000L, back.inMs); assertEquals(10_000L, back.outMs)
    }

    @Test
    fun movingClampsAtEachEndAndRefusesWhenItCannotMove() {
        val nearEnd = TransportMath.move(Loop(197_000, 199_000, null), +1, dur)!!
        assertEquals(ceiling, nearEnd.outMs)
        assertEquals(2_000L, nearEnd.lengthMs)
        val nearStart = TransportMath.move(Loop(500, 2_500, null), -1, dur)!!
        assertEquals(0L, nearStart.inMs)
        assertEquals(2_000L, nearStart.lengthMs)
        assertNull(TransportMath.move(Loop(0, 2_000, null), -1, dur))
        assertNull(TransportMath.move(Loop(197_995, ceiling, null), +1, dur))
        assertNull(TransportMath.move(Loop(1_000, 2_000, null), 0, dur))
        assertNull(TransportMath.move(Loop(1_000, 2_000, null), +1, 0L))
    }

    @Test
    fun movingLaterNeverSlidesALoopEarlier() {
        assertNull(TransportMath.move(Loop(199_000, 199_998, null), +1, dur)) // out above the ceiling
        assertNull(TransportMath.move(Loop(199_000, 200_000, null), +1, dur))
        assertNull(TransportMath.move(Loop(0, 200_000, null), +1, dur))
    }

    @Test
    fun tinyTracksDoNotCrashTheClampingMaths() {
        assertEquals(0L, TransportMath.fractionToPositionMs(1f, 3L))
        assertEquals(0L, TransportMath.fractionToPositionMs(0.5f, 5L))
        assertNull(TransportMath.beatJumpTarget(0, 120f, 4f, 3L))
        assertNull(TransportMath.move(Loop(0, 2, null), +1, 4L))
    }

    // ---- beat jump ---------------------------------------------------------

    @Test
    fun beatJumpMovesByWholeBeatsOfTheAnalysedTempo() {
        assertEquals(12_000L, TransportMath.beatJumpTarget(10_000, bpm, 4f, dur))
        assertEquals(9_500L, TransportMath.beatJumpTarget(10_000, bpm, -1f, dur))
        assertEquals(10_250L, TransportMath.beatJumpTarget(10_000, bpm, 0.5f, dur))
    }

    @Test
    fun beatJumpClampsAndReportsNoOps() {
        assertEquals(0L, TransportMath.beatJumpTarget(1_000, bpm, -16f, dur))
        assertEquals(ceiling, TransportMath.beatJumpTarget(199_000, bpm, 16f, dur))
        assertNull("already at the start", TransportMath.beatJumpTarget(0, bpm, -4f, dur))
        assertNull("already at the end", TransportMath.beatJumpTarget(ceiling, bpm, 4f, dur))
        assertNull(TransportMath.beatJumpTarget(10_000, null, 4f, dur))
        assertNull(TransportMath.beatJumpTarget(10_000, 0f, 4f, dur))
        assertNull(TransportMath.beatJumpTarget(10_000, bpm, 0f, dur))
        assertNull(TransportMath.beatJumpTarget(10_000, bpm, 4f, 0L))
    }

    @Test
    fun wrapIntoLoopKeepsAJumpInsideAnActiveLoop() {
        val loop = Loop(10_000, 14_000, null) // 4 s long
        assertEquals(12_000L, TransportMath.wrapIntoLoop(12_000, loop))
        assertEquals(10_000L, TransportMath.wrapIntoLoop(14_000, loop)) // out is exclusive
        assertEquals(11_000L, TransportMath.wrapIntoLoop(15_000, loop))
        assertEquals(13_000L, TransportMath.wrapIntoLoop(9_000, loop))
        assertEquals(10_000L, TransportMath.wrapIntoLoop(2_000, loop)) // far before: (2000-10000) mod 4000 = 0
        assertEquals(5_000L, TransportMath.wrapIntoLoop(5_000, null))
    }

    // ---- waveform tap ------------------------------------------------------

    @Test
    fun waveformFractionMapsToPositionWithinTheTrack() {
        assertEquals(0L, TransportMath.fractionToPositionMs(0f, dur))
        assertEquals(100_000L, TransportMath.fractionToPositionMs(0.5f, dur))
        assertEquals(ceiling, TransportMath.fractionToPositionMs(1f, dur))
        assertEquals(ceiling, TransportMath.fractionToPositionMs(7f, dur))
        assertEquals(0L, TransportMath.fractionToPositionMs(-2f, dur))
        assertEquals(0L, TransportMath.fractionToPositionMs(Float.NaN, dur))
        assertNull(TransportMath.fractionToPositionMs(0.5f, 0L))
    }

    // ---- clocks ------------------------------------------------------------

    @Test
    fun clockFormatsMinutesAndHours() {
        assertEquals("0:00", TransportMath.formatClock(0))
        assertEquals("0:00", TransportMath.formatClock(999))
        assertEquals("0:59", TransportMath.formatClock(59_999))
        assertEquals("1:00", TransportMath.formatClock(60_000))
        assertEquals("59:59", TransportMath.formatClock(3_599_999))
        assertEquals("1:00:00", TransportMath.formatClock(3_600_000))
        assertEquals("5:29:06", TransportMath.formatClock(19_746_422)) // the 5.5 h voice memo's length
        assertEquals("0:00", TransportMath.formatClock(-5))
    }

    @Test
    fun remainingCountsDownAndRoundsUp() {
        assertEquals("-3:20", TransportMath.formatRemaining(0, 200_000))
        assertEquals("-0:01", TransportMath.formatRemaining(199_500, 200_000)) // 500 ms left still reads 1 s
        assertEquals("-0:01", TransportMath.formatRemaining(199_001, 200_000))
        assertEquals("-0:00", TransportMath.formatRemaining(200_000, 200_000))
        assertEquals("-0:00", TransportMath.formatRemaining(250_000, 200_000))
        assertEquals("-1:00:00", TransportMath.formatRemaining(0, 3_600_000))
    }

    @Test
    fun nearEndWarningCoversTheLastThirtySecondsOnly() {
        assertFalse(TransportMath.isNearEnd(169_999, dur)) // 30.001 s left
        assertTrue(TransportMath.isNearEnd(170_000, dur)) // exactly 30 s
        assertTrue(TransportMath.isNearEnd(199_999, dur))
        assertFalse("ended", TransportMath.isNearEnd(200_000, dur))
        assertFalse("unloaded", TransportMath.isNearEnd(0, 0L))
        assertTrue(TransportMath.isNearEnd(180_000, dur, warnSeconds = 30))
        assertFalse(TransportMath.isNearEnd(150_000, dur, warnSeconds = 30))
    }
}
