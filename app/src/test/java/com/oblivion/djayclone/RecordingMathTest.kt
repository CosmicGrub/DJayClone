package com.oblivion.djayclone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMathTest {

    @Test fun captureRate() {
        assertEquals(44100, CaptureSpec.chooseRate(44100))
        assertEquals(48000, CaptureSpec.chooseRate(48000))
        for (r in listOf(96000, 32000, 0, -1)) assertEquals(48000, CaptureSpec.chooseRate(r))
        assertEquals(48000, CaptureSpec.chooseRate(null))
    }

    @Test fun clockUsesFramesAndDoesNotOverflow() {
        assertEquals(2000L, CaptureClock.elapsedMs(88_200, 44100))
        assertEquals(3_600_000L, CaptureClock.elapsedMs(48_000L * 3600, 48000))
        assertEquals((1L shl 33) * 1000 / 48000, CaptureClock.elapsedMs(1L shl 33, 48000))
        assertEquals(0L, CaptureClock.elapsedMs(100, 0))
    }

    @Test fun storageMaths() {
        assertEquals(176_400L, StorageBudget.bytesPerSecond(44100, 2))
        assertEquals(192_000L, StorageBudget.bytesPerSecond(48000, 2))
        val one = StorageBudget.remainingMs(1_000_000_000, 52_428_800, 176_400, 1)
        val two = StorageBudget.remainingMs(1_000_000_000, 52_428_800, 176_400, 2)
        assertEquals(5_371_000L, one) // whole seconds
        assertEquals(2_685_000L, two)
        assertEquals(0L, StorageBudget.remainingMs(52_428_800, 52_428_800, 176_400))
        assertEquals(0L, StorageBudget.remainingMs(10, 52_428_800, 176_400))
        assertTrue(StorageBudget.remainingMs(Long.MAX_VALUE, 0, 176_400) > 0)
        assertTrue(StorageBudget.shouldStop(52_428_800))
        assertFalse(StorageBudget.shouldStop(52_428_801))
    }

    @Test fun storageLevelsAndFormat() {
        assertEquals(StorageBudget.Level.OK, StorageBudget.level(15 * 60_000L))
        assertEquals(StorageBudget.Level.WARN, StorageBudget.level(15 * 60_000L - 1))
        assertEquals(StorageBudget.Level.CRITICAL, StorageBudget.level(3 * 60_000L - 1))
        assertEquals(StorageBudget.Level.STOP, StorageBudget.level(99_999_999, stop = true))
        assertEquals("3h 12m", StorageBudget.format((3 * 60 + 12) * 60_000L))
        assertEquals("45m", StorageBudget.format(45 * 60_000L))
        assertEquals("<1m", StorageBudget.format(59_000))
        assertEquals("<1m", StorageBudget.format(-5))
        assertEquals("99h 0m", StorageBudget.format(Long.MAX_VALUE))
        assertEquals(1_000_000L + StorageBudget.RESERVE_BYTES, StorageBudget.saveNeeds(1_000_000))
    }

    @Test fun stopNeedsConfirmationOnlyOnLongTakes() {
        assertEquals(StopGuard.Result.Confirmed, StopGuard.onTap(0, 29_999, null))
        assertEquals(StopGuard.Result.Armed(0), StopGuard.onTap(0, 30_000, null))
        assertEquals(StopGuard.Result.Confirmed, StopGuard.onTap(3999, 60_000, 1000))
        assertEquals(StopGuard.Result.Armed(4000), StopGuard.onTap(4000, 60_000, 1000))
        assertTrue(StopGuard.stillArmed(1500, 1000))
        assertFalse(StopGuard.stillArmed(4000, 1000))
        assertFalse(StopGuard.stillArmed(10, null))
    }

    @Test fun discardConfirmation() {
        assertFalse(DiscardPolicy.needsConfirm(9_999, false))
        assertTrue(DiscardPolicy.needsConfirm(10_000, false))
        assertFalse(DiscardPolicy.needsConfirm(3_600_000, true))
    }

    @Test fun watchdog() {
        assertFalse(CaptureWatchdog.isStalled(1999, 0, null))
        assertTrue(CaptureWatchdog.isStalled(2000, 0, null))
        assertFalse(CaptureWatchdog.isStalled(11_999, 0, 10_000))
        assertTrue(CaptureWatchdog.isStalled(12_000, 0, 10_000))
    }

    @Test fun notificationText() {
        assertEquals("Preparing to record", NotificationText.compose(false, 0, StorageBudget.Level.OK))
        val t = NotificationText.compose(true, 5_025_000, StorageBudget.Level.OK)
        assertTrue(t, t.contains("1:23:45"))
        assertFalse(t.contains("Low storage"))
        assertTrue(NotificationText.compose(true, 1000, StorageBudget.Level.CRITICAL).contains("Low storage"))
        assertTrue(NotificationText.title(true).isNotBlank() && NotificationText.title(false).isNotBlank())
    }

    private fun u(b: ByteArray, o: Int) = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    @Test fun goldenHeader() {
        val h = WavHeader.build(44100, 2, 16, 176_400)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals(176_436L, u(h, 4))
        assertEquals(44_100L, u(h, 24))
        assertEquals(176_400L, u(h, 28))
        assertEquals(4, h[32].toInt()); assertEquals(16, h[34].toInt())
        assertEquals(176_400L, u(h, 40))
    }

    @Test fun sizesAreUnsignedAndClamped() {
        val big = WavHeader.build(44100, 2, 16, 3_000_000_000L)
        assertEquals(3_000_000_000L, u(big, 40))
        assertEquals(3_000_000_036L, u(big, 4))
        val over = WavHeader.build(44100, 2, 16, 5_000_000_000L)
        assertEquals(4_294_967_256L, u(over, 40))
        assertEquals(WavHeader.maxDataBytes(4), u(over, 40))
        assertTrue(WavHeader.isAtLimit(4_294_967_256L, 4))
        assertFalse(WavHeader.isAtLimit(1_000, 4))
    }

    @Test fun parseRoundTrips() {
        for (rate in listOf(44100, 48000)) for (ch in listOf(1, 2)) {
            val info = WavHeader.parse(WavHeader.build(rate, ch, 16, 1_000))!!
            assertEquals(WavHeader.Info(rate, ch, 16, 1_000), info)
        }
        assertNull(WavHeader.parse(ByteArray(10)))
        assertNull(WavHeader.parse(ByteArray(44)))
    }

    @Test fun repairTrimsToWholeFrames() {
        val stale = WavHeader.build(44100, 2, 16, 0)
        val fixed = WavHeader.repaired(stale, 44 + 176_403)!!
        assertEquals(176_400L, u(fixed, 40))
        assertEquals(176_436L, u(fixed, 4))
        assertNull(WavHeader.repaired(ByteArray(44), 1000))
        assertEquals(0L, u(WavHeader.repaired(stale, 10)!!, 40))
        assertArrayEquals(WavHeader.build(48000, 2, 16, 4), WavHeader.repaired(WavHeader.build(48000, 2, 16, 999), 48)!!)
        val info = WavHeader.parse(fixed)!!
        assertEquals(1000L, WavHeader.durationMs(info, 176_400))
        assertNotNull(info)
    }
}
