package com.oblivion.djayclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoControlsTest {
    @Test fun lockedDeckBlocksOnlyNormalInput() {
        for (k in InputGate.Kind.values()) {
            assertEquals(k != InputGate.Kind.NORMAL, InputGate.admit(true, k))
            assertTrue(InputGate.admit(false, k))
        }
        assertEquals(setOf('B'), InputGate.decksToReset(true, false))
        assertEquals(setOf('A', 'B'), InputGate.decksToReset(false, false))
        assertTrue(InputGate.decksToReset(true, true).isEmpty())
    }

    @Test fun faderCentreIsExactlyOneAndEndsMatchRange() {
        assertEquals(1f, TempoFaderMath.speedForPosition(0f, 8), 0f)
        assertEquals(1f, TempoFaderMath.speedForPosition(0.005f, 8), 0f) // inside detent
        assertEquals(1.08f, TempoFaderMath.speedForPosition(1f, 8), 1e-6f)
        assertEquals(0.92f, TempoFaderMath.speedForPosition(-1f, 8), 1e-6f)
        assertEquals(0.92f, TempoFaderMath.speedForPosition(1f, 8, inverted = true), 1e-6f)
        assertEquals(1.5f, TempoFaderMath.speedForPosition(1f, 50), 1e-6f)
        assertEquals(1.08f, TempoFaderMath.speedForPosition(9f, 8), 1e-6f)
        assertEquals(1f, TempoFaderMath.speedForPosition(Float.NaN, 8), 0f)
        assertEquals("unknown range falls back to 8", 1.08f, TempoFaderMath.speedForPosition(1f, 7), 1e-6f)
    }

    @Test fun positionRoundTripsAndFlagsOutOfRange() {
        for (inv in listOf(false, true)) for (r in TempoFaderMath.ALLOWED_RANGES) for (i in -10..10) {
            val pos = i / 10f
            val speed = TempoFaderMath.speedForPosition(pos, r, inv)
            val back = TempoFaderMath.positionForSpeed(speed, r, inv)
            if (kotlin.math.abs(pos) > 0.5f) assertEquals("r=$r inv=$inv pos=$pos", pos, back.pos, 1e-3f)
            assertFalse(back.beyondRange)
        }
        val over = TempoFaderMath.positionForSpeed(1.2f, 8)
        assertTrue(over.beyondRange)
        assertEquals(1f, over.pos, 0f)
    }

    @Test fun deltaFormatting() {
        assertEquals("+3.25%", TempoFaderMath.formatDelta(1.0325f))
        assertEquals("−0.50%", TempoFaderMath.formatDelta(0.995f))
        assertEquals("0.00%", TempoFaderMath.formatDelta(1f))
        assertEquals("0.00%", TempoFaderMath.formatDelta(0.99999f))
        assertEquals("+100.00%", TempoFaderMath.formatDelta(2f))
    }

    @Test fun governorThrottlesButPassesFirstAndBigMoves() {
        val g = ParamGovernor(66, 0.0001f)
        assertEquals(1.01f, g.offer(0, 1.01f)!!, 0f)
        assertNull(g.offer(30, 1.02f))
        assertNotNull(g.offer(70, 1.02f))
        assertNull("same value", g.offer(200, 1.02f))
        g.reset()
        assertNotNull(g.offer(201, 1.02f))
    }

    @Test fun bpmHalfDoubleAndRange() {
        assertEquals(64f, BpmMath.halve(128f)!!, 0f)
        assertEquals(170f, BpmMath.double(85f)!!, 0f)
        assertNull(BpmMath.double(200f))
        assertNull(BpmMath.halve(60f))
        assertEquals(128f, BpmMath.times(85.33f, 1.5f)!!, 0.02f)
    }

    @Test fun bpmParse() {
        assertEquals(128.5f, BpmMath.parse(" 128,5 ")!!, 0f)
        assertEquals(120f, BpmMath.parse("120")!!, 0f)
        for (bad in listOf(null, "", "abc", "1e2", "-120", "+120", "NaN", "Infinity", "12.3.4", "39", "261", "0x80", "128 bpm")) {
            assertNull("reject $bad", BpmMath.parse(bad))
        }
    }

    @Test fun loopRelabelling() {
        val chips = listOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)
        assertEquals(4f, BpmMath.relabelLoopBeats(2000, 120f, chips)!!, 0f)
        assertEquals(2f, BpmMath.relabelLoopBeats(2000, 60f, chips)!!, 0f)
        assertNull(BpmMath.relabelLoopBeats(1300, 120f, chips))
        assertNull(BpmMath.relabelLoopBeats(2000, 0f, chips))
    }

    private fun tapsAt(vararg t: Long): TapResult {
        val tt = TapTempo()
        var r = TapResult(null, 0, 0f, false)
        for (x in t) r = tt.tap(x)
        return r
    }

    @Test fun tapBasics() {
        assertEquals(120f, tapsAt(0, 500, 1000, 1500).bpm!!, 1e-3f)
        assertNull(tapsAt(0).bpm)
        val two = tapsAt(0, 500)
        assertEquals(120f, two.bpm!!, 1e-3f)
        assertFalse(two.stable)
    }

    @Test fun tapToleratesAMissedBeat() {
        val r = tapsAt(0, 500, 1000, 2000, 2500, 3000)
        assertEquals(120f, r.bpm!!, 0.05f)
        assertEquals(6, r.taps)
    }

    @Test fun tapResetsAfterGapAndDebounces() {
        val r = tapsAt(0, 500, 1000, 3500)
        assertEquals(1, r.taps)
        assertNull(r.bpm)
        assertEquals(1, tapsAt(0, 60).taps)
    }

    @Test fun tapJitterStaysPrecise() {
        val tt = TapTempo()
        var r = TapResult(null, 0, 0f, false)
        val jit = intArrayOf(9, -11, 4, -7, 12, -3, 8, -12, 5, -2, 10, -6)
        for (i in 0 until 12) r = tt.tap((i * 468.75).toLong() + jit[i])
        assertTrue("bpm ${r.bpm}", kotlin.math.abs(r.bpm!! - 128f) < 0.6f)
        assertTrue(r.stable)
    }

    @Test fun tapWindowFollowsATempoChange() {
        val tt = TapTempo()
        var r = TapResult(null, 0, 0f, false)
        var t = 0.0
        repeat(12) { r = tt.tap(t.toLong()); t += 500 }
        repeat(12) { r = tt.tap(t.toLong()); t += 461.54 }
        assertEquals(130f, r.bpm!!, 0.3f)
    }

    @Test fun tapRangeLimits() {
        assertNotNull(tapsAt(0, 300, 600, 900).bpm)
        assertNull(tapsAt(0, 150, 300, 450).bpm)
        val tt = TapTempo()
        tt.tap(0); tt.tap(500); tt.reset()
        assertEquals(1, tt.tap(900).taps)
    }
}
