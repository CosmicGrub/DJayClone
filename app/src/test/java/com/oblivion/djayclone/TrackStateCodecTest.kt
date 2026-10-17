package com.oblivion.djayclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStateCodecTest {

    private val full = SavedTrackState(
        durationMs = 200_000,
        cuePointMs = 1_234,
        loopInMs = 10_000, loopOutMs = 14_000, loopBeats = 4f,
        hotCues = listOf(1_000, null, 3_000, null, 5_000, null, null, 8_000),
    )

    @Test
    fun roundTripsEverything() {
        assertEquals(full, TrackStateCodec.decode(TrackStateCodec.encode(full)))
    }

    @Test
    fun roundTripsAManualLoopWithNoBeatsAndSparseFields() {
        val s = SavedTrackState(180_000, null, 5_000, 9_000, null, listOf(null, null, null, null))
        assertEquals(s, TrackStateCodec.decode(TrackStateCodec.encode(s)))
        val noLoop = SavedTrackState(180_000, 42, null, null, null, emptyList())
        assertEquals(noLoop, TrackStateCodec.decode(TrackStateCodec.encode(noLoop)))
    }

    @Test
    fun encodedFormIsTheDocumentedText() {
        assertEquals("v1;d=200000;c=1234;l=10000,14000,4.0;h=1000,,3000,,5000,,,8000", TrackStateCodec.encode(full))
    }

    @Test
    fun aHalfLoopIsNotWritten() {
        val half = full.copy(loopInMs = 10_000, loopOutMs = null)
        assertFalse(TrackStateCodec.encode(half).contains(";l="))
    }

    @Test
    fun garbageAndUnknownVersionsDecodeToNull() {
        for (bad in listOf(null, "", "   ", "garbage", "v2;d=1000", "v1", "v1;d=abc", "v1;d=0", "v1;d=-5",
            "v1;d=1000;c=x", "v1;d=1000;l=1,2", "v1;d=1000;l=1,2,zz", "v1;d=1000;h=1,x,3", "v1;d=1000;broken")) {
            assertNull("should reject: $bad", TrackStateCodec.decode(bad))
        }
    }

    @Test
    fun unknownFieldsFromANewerVersionAreIgnoredNotFatal() {
        val d = TrackStateCodec.decode("v1;d=1000;c=5;zz=whatever")
        assertEquals(5L, d!!.cuePointMs)
    }

    @Test
    fun aRecordForADifferentLengthTrackIsRejected() {
        assertNotNull(TrackStateCodec.validated(full, 200_000))
        assertNotNull("within tolerance", TrackStateCodec.validated(full, 201_900))
        assertNotNull("within tolerance", TrackStateCodec.validated(full, 198_100))
        assertNull("different file", TrackStateCodec.validated(full, 203_000))
        assertNull(TrackStateCodec.validated(full, 0))
    }

    @Test
    fun validationDropsPositionsPastTheEndAndBrokenLoops() {
        val shorter = TrackStateCodec.validated(full.copy(durationMs = 200_000), 199_000)!! // still same file
        assertEquals(199_000, shorter.durationMs)
        // Hot cue at 8 s is fine; make one fall past the end of a 6 s track.
        val v = TrackStateCodec.validated(
            SavedTrackState(6_000, 6_500, 1_000, 5_000, 2f, listOf(1_000, 7_000, 5_999)), 6_000
        )!!
        assertNull("cue past end", v.cuePointMs)
        assertEquals(listOf(1_000L, null, 5_999L), v.hotCues)
        assertEquals(1_000L, v.loopInMs)
        val broken = TrackStateCodec.validated(SavedTrackState(6_000, null, 5_000, 2_000, 2f, emptyList()), 6_000)!!
        assertNull(broken.loopInMs); assertNull(broken.loopOutMs); assertNull(broken.loopBeats)
        val pastEnd = TrackStateCodec.validated(SavedTrackState(6_000, null, 1_000, 9_000, null, emptyList()), 6_000)!!
        assertNull("loop out past the end", pastEnd.loopOutMs)
    }

    @Test
    fun restoreFillsOnlyWhatTheDeckDoesNotAlreadyHold() {
        val saved = SavedTrackState(200_000, 5_000, 10_000, 14_000, 4f, listOf(1_000L, 2_000L, null, 4_000L))
        val empty = TrackStateCodec.Live(null, null, null, null, List(4) { null })
        assertEquals(
            TrackStateCodec.Live(5_000, 10_000, 14_000, 4f, listOf(1_000L, 2_000L, null, 4_000L)),
            TrackStateCodec.restore(empty, saved, 4)
        )
        // "Keep loops/cues across load" carried a cue and a loop from the previous track:
        // they win, but the hot cues (never carried) are STILL restored.
        val carried = TrackStateCodec.Live(777, 100, 900, null, List(4) { null })
        val r = TrackStateCodec.restore(carried, saved, 4)
        assertEquals(777L, r.cuePointMs)
        assertEquals(100L, r.loopInMs); assertEquals(900L, r.loopOutMs); assertNull(r.loopBeats)
        assertEquals(listOf(1_000L, 2_000L, null, 4_000L), r.hotCues)
        // A pad tapped before READY keeps its own slot; the rest still fill in.
        val tapped = TrackStateCodec.Live(null, null, null, null, listOf(null, 9_999L, null, null))
        assertEquals(listOf(1_000L, 9_999L, null, 4_000L), TrackStateCodec.restore(tapped, saved, 4).hotCues)
        // No record: nothing changes. Pad count change: reshaped.
        assertEquals(carried, TrackStateCodec.restore(carried, null, 4))
        assertEquals(8, TrackStateCodec.restore(empty.copy(hotCues = List(8) { null }), saved, 8).hotCues.size)
    }

    @Test
    fun validationBoundaries() {
        val v = TrackStateCodec.validated(SavedTrackState(6_000, 6_000, 0, 6_000, null, listOf(6_000, 5_999, -1)), 6_000)!!
        assertNull("cue == duration is past the end", v.cuePointMs)
        assertEquals(listOf(null, 5_999L, null), v.hotCues)
        assertEquals("a loop may start at 0 and end exactly at the duration", 0L, v.loopInMs)
        assertEquals(6_000L, v.loopOutMs)
        assertNotNull("exactly at the tolerance edge", TrackStateCodec.validated(full, 202_000))
        assertNull("one ms beyond it", TrackStateCodec.validated(full, 202_001))
    }

    @Test
    fun anEmptyRecordIsRecognisedSoItCanBeDeleted() {
        assertTrue(SavedTrackState(1_000, null, null, null, null, listOf(null, null)).isEmpty)
        assertTrue(SavedTrackState(1_000, null, null, null, null, emptyList()).isEmpty)
        assertFalse(full.isEmpty)
        assertFalse(SavedTrackState(1_000, 5, null, null, null, emptyList()).isEmpty)
    }

    @Test
    fun lowerPadCountDoesNotEraseHigherPadsOnSave() {
        val saved16 = List<Long?>(16) { if (it == 12) 99_000L else null }
        val live8 = List<Long?>(8) { null }
        val merged = TrackStateCodec.mergeHotCues(live8, saved16)
        assertEquals(16, merged.size)
        assertEquals(99_000L, merged[12])
        // ...but a deliberate delete inside the current count sticks.
        val live8Deleted = List<Long?>(8) { null }
        val saved = List<Long?>(8) { if (it == 3) 5_000L else null }
        assertNull(TrackStateCodec.mergeHotCues(live8Deleted, saved)[3])
        assertEquals(live8, TrackStateCodec.mergeHotCues(live8, null))
    }

    @Test
    fun hotCuesAreReshapedToTheCurrentPadCount() {
        val saved = listOf<Long?>(1L, null, 3L, null, 5L)
        assertEquals(listOf<Long?>(1L, null, 3L, null), TrackStateCodec.hotCuesForCount(saved, 4))
        assertEquals(8, TrackStateCodec.hotCuesForCount(saved, 8).size)
        assertEquals(5L, TrackStateCodec.hotCuesForCount(saved, 8)[4])
        assertNull(TrackStateCodec.hotCuesForCount(saved, 8)[7])
    }
}
