package com.oblivion.djayclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCodecTest {
    private val full = SessionSnapshot(
        DeckSession("content://media/external/audio/media/42", "Midnight Circuit", 138_000, 1.005f),
        DeckSession("content://x/y%z", "Tab\there\nand 100% sure", 46_000, 0.97f),
        0.25f, SyncController.B_FOLLOWS_A,
    )

    @Test fun roundTripsEverythingIncludingAwkwardTitles() {
        assertEquals(full, SessionCodec.decode(SessionCodec.encode(full)))
    }

    @Test fun oneDeckAndNoSyncRoundTrip() {
        val s = SessionSnapshot(null, DeckSession("content://a", null, 0, 1f), 0.5f, null)
        assertEquals(s, SessionCodec.decode(SessionCodec.encode(s)))
        assertTrue(SessionSnapshot(null, null, 0.5f, null).isEmpty)
    }

    @Test fun garbageIsRejected() {
        for (bad in listOf(null, "", "nope", "djsession1\nA\tonly\ttwo", "djsession1\nA\tu\tt\tx\t1.0",
            "djsession1\nA\tu\tt\t-5\t1.0", "djsession1\nA\tu\tt\t5\t9.0", "djsession1\nZ\t1", "djsession1\nA\t%zz\tt\t5\t1.0", "djsession1\nA\t%\tt\t5\t1.0")) {
            assertNull("reject: $bad", SessionCodec.decode(bad))
        }
    }

    @Test fun mixerValuesAreClampedAndLocksValidated() {
        val d = SessionCodec.decode("djsession1\nM\t7.5\tgarbage")!!
        assertEquals(1f, d.crossfader, 0f)
        assertNull(d.syncLock)
        assertNotNull(SessionCodec.decode("djsession1\nM\t0.5\t"))
    }

    @Test fun forgettingCuesKeepsAHandCorrectedBpm() {
        val rec = SavedTrackState(200_000, 5_000, 1_000, 4_000, 4f, listOf(1_000L, null), userBpm = 128.5f)
        val kept = TrackStateCodec.withoutCues(rec)!!
        assertEquals(128.5f, kept.userBpm!!, 0f)
        assertNull(kept.cuePointMs); assertNull(kept.loopInMs); assertTrue(kept.hotCues.isEmpty())
        assertNull(TrackStateCodec.withoutCues(rec.copy(userBpm = null)))
    }
}
