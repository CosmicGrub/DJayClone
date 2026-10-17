package com.oblivion.djayclone

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerMathTest {

    private fun f(x: Float, c: CrossfaderCurve, rev: Boolean = false) = MixerMath.crossfadeFactors(x, c, rev)

    // ---- crossfader law -------------------------------------------------

    @Test
    fun everyCurveHasAFullSourceAtEachEndAndSilenceOnTheOther() {
        for (c in CrossfaderCurve.values()) {
            assertEquals("$c: A at x=0", 1f, f(0f, c).first, 1e-6f)
            assertEquals("$c: B at x=0 is exactly silent", 0f, f(0f, c).second, 0f)
            assertEquals("$c: B at x=1", 1f, f(1f, c).second, 1e-6f)
            assertEquals("$c: A at x=1 is exactly silent", 0f, f(1f, c).first, 0f)
        }
    }

    @Test
    fun smoothIsEqualPower() {
        for (i in 0..20) {
            val (a, b) = f(i / 20f, CrossfaderCurve.SMOOTH)
            assertEquals("a^2 + b^2 at x=${i / 20f}", 1f, a * a + b * b, 1e-5f)
        }
        assertEquals(sqrt(0.5f), f(0.5f, CrossfaderCurve.SMOOTH).first, 1e-5f)
    }

    @Test
    fun linearSumsToUnityAndDipsPowerInTheMiddle() {
        for (i in 0..20) {
            val (a, b) = f(i / 20f, CrossfaderCurve.LINEAR)
            assertEquals(1f, a + b, 1e-6f)
        }
        val (a, b) = f(0.5f, CrossfaderCurve.LINEAR)
        assertTrue("power dips below unity at centre", a * a + b * b < 0.6f)
    }

    @Test
    fun sharpKeepsBothDecksAtUnityFromTheCentreAndFadesOnlyNearTheEnds() {
        val centre = f(0.5f, CrossfaderCurve.SHARP)
        assertEquals(1f, centre.first, 1e-6f)
        assertEquals(1f, centre.second, 1e-6f)
        // A stays at full until the fader passes centre toward B.
        assertEquals(1f, f(0.25f, CrossfaderCurve.SHARP).first, 1e-6f)
        assertEquals(0.5f, f(0.75f, CrossfaderCurve.SHARP).first, 1e-6f)
        assertEquals(0.5f, f(0.25f, CrossfaderCurve.SHARP).second, 1e-6f)
    }

    @Test
    fun deckAFallsAndDeckBRisesMonotonically() {
        for (c in CrossfaderCurve.values()) {
            var prevA = 2f
            var prevB = -1f
            for (i in 0..100) {
                val (a, b) = f(i / 100f, c)
                assertTrue("$c: A never rises (x=${i / 100f})", a <= prevA + 1e-6f)
                assertTrue("$c: B never falls (x=${i / 100f})", b >= prevB - 1e-6f)
                prevA = a; prevB = b
            }
        }
    }

    @Test
    fun reversedSwapsTheEnds() {
        for (c in CrossfaderCurve.values()) {
            for (i in 0..10) {
                val x = i / 10f
                val normal = f(1f - x, c)
                val reversed = f(x, c, rev = true)
                assertEquals(normal.first, reversed.first, 1e-6f)
                assertEquals(normal.second, reversed.second, 1e-6f)
            }
        }
    }

    @Test
    fun outOfRangePositionsAreClamped() {
        assertEquals(f(0f, CrossfaderCurve.SMOOTH), f(-3f, CrossfaderCurve.SMOOTH))
        assertEquals(f(1f, CrossfaderCurve.SMOOTH), f(9f, CrossfaderCurve.SMOOTH))
    }

    // ---- effective volume ----------------------------------------------

    @Test
    fun effectiveVolumeMultipliesAndNeverExceedsUnity() {
        assertEquals(0.279083f, MixerMath.effectiveVolume(1f, 0.3947f, 0.7071f), 1e-3f) // measured on the tablet
        assertEquals(0.5f, MixerMath.effectiveVolume(0.5f, 1f, 1f), 1e-6f)
        assertEquals(1f, MixerMath.effectiveVolume(1f, 1f, 1.7f), 0f)
        assertEquals(0f, MixerMath.effectiveVolume(-1f, 1f, 1f), 0f)
        assertEquals(0f, MixerMath.effectiveVolume(1f, 1f, 0f), 0f)
    }

    // ---- sync controller -----------------------------------------------

    private val tol = TempoSyncMath.DEFAULT_RATIO_TOLERANCE
    private fun sync(
        lock: String?, mode: SyncRatioMode = SyncRatioMode.AUTO,
        bpmA: Float? = 128f, bpmB: Float? = 120f, sA: Float = 1f, sB: Float = 1f,
    ) = SyncController.compute(lock, mode, tol, bpmA, bpmB, sA, sB)

    @Test
    fun noLockOrMissingBpmMeansNoAction() {
        assertNull(sync(null))
        assertNull(sync(SyncController.B_FOLLOWS_A, bpmA = null))
        assertNull(sync(SyncController.B_FOLLOWS_A, bpmB = null))
        assertNull(sync(SyncController.B_FOLLOWS_A, bpmA = 0f))
        assertNull(sync("garbage"))
    }

    @Test
    fun followerIsSpedUpOrSlowedToTheLeadersEffectiveTempo() {
        val t = sync(SyncController.B_FOLLOWS_A)!!
        assertEquals(false, t.followerIsA)
        assertEquals(128f / 120f, t.speed, 1e-5f) // B (base 120) must run at 128/120

        val u = sync(SyncController.A_FOLLOWS_B)!!
        assertEquals(true, u.followerIsA)
        assertEquals(120f / 128f, u.speed, 1e-5f)
    }

    @Test
    fun leaderSpeedIsFoldedIntoItsEffectiveTempo() {
        // Leader A is nudged to 1.05x: B must follow 128*1.05 = 134.4 BPM.
        val t = sync(SyncController.B_FOLLOWS_A, sA = 1.05f)!!
        assertEquals(134.4f / 120f, t.speed, 1e-5f)
    }

    @Test
    fun halfTimeIsRecognisedInAutoAndForcedByMode() {
        // Leader 140, follower base 70: the documented DnB-vs-dubstep case.
        val auto = SyncController.compute(SyncController.B_FOLLOWS_A, SyncRatioMode.AUTO, tol, 140f, 70f, 1f, 1f)
        assertNull("70 vs 140 is already a perfect half-time match - no change", auto)
        val off = SyncController.compute(SyncController.B_FOLLOWS_A, SyncRatioMode.AUTO, tol, 142f, 70f, 1f, 1f)!!
        assertEquals(142f * 0.5f / 70f, off.speed, 1e-5f)
        // Forced UNITY ignores the 2:1 relationship and drags B all the way up.
        val forced = SyncController.compute(SyncController.B_FOLLOWS_A, SyncRatioMode.UNITY, tol, 140f, 70f, 1f, 1f)!!
        assertEquals(2f, forced.speed, 1e-5f)
    }

    @Test
    fun unrelatedTemposFallBackToUnityNotAFalseLock() {
        // 128 vs 85: ratio 0.66 matches nothing within tolerance -> UNITY.
        val t = SyncController.compute(SyncController.B_FOLLOWS_A, SyncRatioMode.AUTO, tol, 128f, 85f, 1f, 1f)!!
        assertEquals(128f / 85f, t.speed, 1e-5f)
    }

    @Test
    fun alreadyThereMeansNoRedundantSet() {
        assertNull(sync(SyncController.B_FOLLOWS_A, bpmA = 120f, bpmB = 120f))
        assertNull(sync(SyncController.B_FOLLOWS_A, sB = 128f / 120f))
        assertNotNull(sync(SyncController.B_FOLLOWS_A, sB = 1.0f))
    }

    @Test
    fun anOutOfRangeIdealIsClampedSoItDoesNotRefireForever() {
        // Leader 200, follower base 60, forced UNITY -> ideal 3.33x, but the
        // deck only supports 0.5..2.0.
        val t = SyncController.compute(SyncController.B_FOLLOWS_A, SyncRatioMode.UNITY, tol, 200f, 60f, 1f, 1f)!!
        assertEquals(DeckSpeedRange.MAX, t.speed, 0f)
        // Once the follower is AT the clamp, there is nothing more to do.
        assertNull(SyncController.compute(SyncController.B_FOLLOWS_A, SyncRatioMode.UNITY, tol, 200f, 60f, 1f, DeckSpeedRange.MAX))
    }

    @Test
    fun syncIsMirrorSymmetricForEveryRatioMode() {
        val tempos = listOf(70f, 85f, 120f, 128f, 140f, 174f)
        val speeds = listOf(0.9f, 1f, 1.07f)
        for (mode in SyncRatioMode.values()) for (a in tempos) for (b in tempos) for (sa in speeds) for (sb in speeds) {
            val aFollows = SyncController.compute(SyncController.A_FOLLOWS_B, mode, tol, a, b, sa, sb)
            val mirrored = SyncController.compute(SyncController.B_FOLLOWS_A, mode, tol, b, a, sb, sa)
            assertEquals("mode=$mode a=$a b=$b sa=$sa sb=$sb", mirrored?.speed, aFollows?.speed)
            assertEquals(mirrored == null, aFollows == null)
            if (aFollows != null) { assertTrue(aFollows.followerIsA); assertFalse(mirrored!!.followerIsA) }
        }
    }

    @Test
    fun theLeadersSpeedDrivesAFollowerA() {
        // A follows B, B nudged to 1.05x: A (base 128) must run at 120*1.05/128.
        val t = SyncController.compute(SyncController.A_FOLLOWS_B, SyncRatioMode.UNITY, tol, 128f, 120f, 1f, 1.05f)!!
        assertEquals(120f * 1.05f / 128f, t.speed, 1e-5f)
    }

    @Test
    fun theSyncStrategyIsStableUnderRepeatedApplication() {
        // Feed each result back in as the new follower speed: it must settle
        // in one step (a controller that oscillates would fight the DJ).
        var speedB = 1f
        repeat(5) {
            val t = SyncController.compute(SyncController.B_FOLLOWS_A, SyncRatioMode.AUTO, tol, 128f, 120f, 1f, speedB)
            if (t != null) speedB = t.speed
        }
        assertTrue(abs(speedB - 128f / 120f) < 1e-5f)
        assertNull(SyncController.compute(SyncController.B_FOLLOWS_A, SyncRatioMode.AUTO, tol, 128f, 120f, 1f, speedB))
    }
}
