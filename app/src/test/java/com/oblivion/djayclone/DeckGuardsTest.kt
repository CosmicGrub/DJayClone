package com.oblivion.djayclone

import androidx.media3.common.PlaybackException
import com.oblivion.djayclone.LoadGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckGuardsTest {

    @Test
    fun aLockedDeckRefusesEveryLoadEvenWhenIdle() {
        for (playing in listOf(true, false)) for (confirm in listOf(true, false)) {
            assertEquals(Decision.REFUSE_LOCKED, LoadGuard.decide(true, playing, confirm))
        }
    }

    @Test
    fun aPlayingDeckAsksFirstOnlyWhenTheSettingIsOn() {
        assertEquals(Decision.CONFIRM_PLAYING, LoadGuard.decide(false, true, true))
        assertEquals(Decision.LOAD, LoadGuard.decide(false, true, false))
    }

    @Test
    fun anIdleUnlockedDeckAlwaysLoadsImmediately() {
        assertEquals(Decision.LOAD, LoadGuard.decide(false, false, true))
        assertEquals(Decision.LOAD, LoadGuard.decide(false, false, false))
    }

    @Test
    fun everyKnownErrorCodeGetsAPlainSentenceNotTheRawCode() {
        val known = listOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        )
        for (code in known) {
            val text = PlaybackErrorText.describe(code)
            assertFalse("code $code must not fall through to the generic text", text.startsWith("Couldn't play"))
            assertFalse("no raw numbers for a known code: $text", text.any { it.isDigit() })
            assertTrue(text.endsWith("."))
        }
    }

    @Test
    fun eachCodeMapsToItsOwnMessage() {
        val expected = mapOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND to "find this file",
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION to "permission",
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE to "ended unexpectedly",
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED to "damaged",
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED to "damaged",
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED to "can't decode",
            PlaybackException.ERROR_CODE_DECODING_FAILED to "can't decode",
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED to "can't decode",
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED to "another app",
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED to "Audio output",
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED to "Audio output",
        )
        for ((code, phrase) in expected) {
            assertTrue("code $code should mention '$phrase': ${PlaybackErrorText.describe(code)}",
                PlaybackErrorText.describe(code).contains(phrase))
        }
    }

    @Test
    fun differentFailureKindsReadDifferently() {
        val missing = PlaybackErrorText.describe(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val format = PlaybackErrorText.describe(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        val output = PlaybackErrorText.describe(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
        assertEquals(3, setOf(missing, format, output).size)
        assertNotEquals(PlaybackErrorText.describe(PlaybackException.ERROR_CODE_IO_NO_PERMISSION), missing)
    }

    @Test
    fun unknownCodesAreStillReportedWithTheirNumberForBugReports() {
        assertEquals("Couldn't play this file (error 1234).", PlaybackErrorText.describe(1234))
    }
}
