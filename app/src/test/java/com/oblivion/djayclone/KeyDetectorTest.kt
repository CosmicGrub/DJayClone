package com.oblivion.djayclone

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real JVM unit tests for KeyDetector - runnable with `./gradlew
 * testDebugUnitTest`, no emulator/device needed, since KeyDetector.kt
 * (like Fft.kt and TempoSyncMath.kt) has zero Android imports.
 *
 * Two independent checks, deliberately kept separate:
 *  1. [camelotTableMatchesStandardReference] - the derived Camelot-number
 *     formula against the full, hand-written 24-entry standard reference
 *     table (from published Camelot wheel charts - Mixed In Key/rekordbox/
 *     Serato/djay all agree on this table). Pure logic, no audio, no FFT -
 *     if this fails, the formula itself is wrong.
 *  2. The detectKey(...) tests - synthesize a pure sine-tone triad PCM
 *     buffer for a handful of representative keys and assert the full
 *     decode-free pipeline (chromagram -> Krumhansl-Schmuckler correlation
 *     -> Camelot mapping) recovers the correct key from raw audio. A
 *     played triad's three notes (root/3rd/5th) land exactly on the three
 *     most-heavily-weighted scale degrees in the Krumhansl-Kessler
 *     profiles, so this is close to a best-case signal - it proves the
 *     pipeline is wired correctly, not that it's robust to messy real-world
 *     mixes (that's a "good enough for a DJ helper feature" tradeoff, not
 *     a claim of lab-grade key detection).
 */
class KeyDetectorTest {

    @Test
    fun camelotTableMatchesStandardReference() {
        // pitch class (C=0..B=11) -> Camelot code, straight from a
        // published Camelot wheel chart. Independent of KeyDetector's own
        // derivation - this is the ground truth being checked against.
        val majorReference = mapOf(
            0 to "8B", 1 to "3B", 2 to "10B", 3 to "5B", 4 to "12B", 5 to "7B",
            6 to "2B", 7 to "9B", 8 to "4B", 9 to "11B", 10 to "6B", 11 to "1B",
        )
        val minorReference = mapOf(
            0 to "5A", 1 to "12A", 2 to "7A", 3 to "2A", 4 to "9A", 5 to "4A",
            6 to "11A", 7 to "6A", 8 to "1A", 9 to "8A", 10 to "3A", 11 to "10A",
        )
        for (pc in 0 until 12) {
            assertEquals("major pc=$pc", majorReference.getValue(pc), KeyDetector.camelotCode(pc, isMajor = true))
            assertEquals("minor pc=$pc", minorReference.getValue(pc), KeyDetector.camelotCode(pc, isMajor = false))
        }
    }

    @Test
    fun detectsCMajorFromTriad() {
        // C4, E4, G4
        val pcm = chordPcm(doubleArrayOf(261.6256, 329.6276, 392.0000))
        assertEquals("8B", KeyDetector.detectKey(pcm, SAMPLE_RATE))
    }

    @Test
    fun detectsAMinorFromTriad() {
        // A3, C4, E4 - the relative minor of C major; same key signature,
        // different tonic - this specifically exercises that the algorithm
        // distinguishes relative major/minor rather than just detecting
        // "these three pitch classes are present."
        val pcm = chordPcm(doubleArrayOf(220.0000, 261.6256, 329.6276))
        assertEquals("8A", KeyDetector.detectKey(pcm, SAMPLE_RATE))
    }

    @Test
    fun detectsGMajorFromTriad() {
        // G3, B3, D4 - a non-zero rotation, catches a rotation-direction
        // bug that testing only pc=0 (C major) could never surface.
        val pcm = chordPcm(doubleArrayOf(196.0000, 246.9417, 293.6648))
        assertEquals("9B", KeyDetector.detectKey(pcm, SAMPLE_RATE))
    }

    @Test
    fun detectsFMajorFromTriad() {
        // F3, A3, C4
        val pcm = chordPcm(doubleArrayOf(174.6141, 220.0000, 261.6256))
        assertEquals("7B", KeyDetector.detectKey(pcm, SAMPLE_RATE))
    }

    @Test
    fun returnsNullForSilence() {
        val pcm = FloatArray(SAMPLE_RATE * 2) // 2s of true digital silence
        assertEquals(null, KeyDetector.detectKey(pcm, SAMPLE_RATE))
    }

    @Test
    fun returnsNullForTooShortClip() {
        val pcm = FloatArray(100) // far below FFT_SIZE
        assertEquals(null, KeyDetector.detectKey(pcm, SAMPLE_RATE))
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val DURATION_SEC = 8.0 // comfortably > one ~1.49s (65536-sample) analysis window

        /** Sums equal-amplitude sine tones at [freqsHz] into a mono PCM
         * buffer, scaled well under clipping (0.25 per tone, <=3 tones
         * summed here so peak stays under 0.75). */
        fun chordPcm(freqsHz: DoubleArray): FloatArray {
            val n = (SAMPLE_RATE * DURATION_SEC).toInt()
            val pcm = FloatArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                var sample = 0.0
                for (f in freqsHz) sample += 0.25 * sin(2.0 * PI * f * t)
                pcm[i] = sample.toFloat()
            }
            return pcm
        }
    }
}
