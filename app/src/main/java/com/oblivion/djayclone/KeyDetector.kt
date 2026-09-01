package com.oblivion.djayclone

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Harmonic key detection - Krumhansl-Schmuckler key-profile matching over a
 * chromagram (12-bin pitch-class energy histogram). This is the standard MIR
 * approach (the same core method behind Essentia's "krumhansl" Key algorithm
 * and music21's default key-finding). Pure Kotlin, zero Android imports,
 * fully unit-testable without instrumentation - same discipline as
 * TempoSyncMath.kt. Reuses Fft.kt, whose own doc comment already anticipated
 * this ("harmonic key detection would need the same primitive later").
 *
 * Output is Camelot notation (e.g. "8B" = C major, "8A" = A minor), not a
 * plain key name - this is a DJ app, and Camelot is what every real
 * harmonic-mixing tool (Mixed In Key, rekordbox, Serato, djay) actually
 * shows, chosen specifically so harmonically-compatible keys land on
 * adjacent numbers/letters on the wheel.
 */
object KeyDetector {

    // Krumhansl-Kessler (1982) key-profile weights - the standard, widely-
    // cited perceptual "fit" of each scale degree to a tonic, index 0 =
    // tonic. Same constants used by Essentia's "krumhansl" Key profile and
    // music21's default key-finding algorithm - not something this file
    // invented, so don't hand-tune these without a cited source.
    private val MAJOR_PROFILE = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f,
    )
    private val MINOR_PROFILE = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f,
    )

    // 65536 @44.1kHz -> ~0.67Hz/bin. This is deliberately a MUCH bigger
    // window than AudioAnalyzer's other FFT passes (1024 for the spectral
    // waveform) - and correctness-load-bearing, not just "more precision is
    // nice": a Hann window's mainlobe (null-to-null) spans ~4 bins, i.e.
    // ~4*sampleRate/FFT_SIZE Hz. A first cut of this file used 4096
    // (~10.8Hz/bin, ~43Hz mainlobe) on the theory that a semitone's width
    // (~3.9Hz at 65Hz, growing with frequency) was "close enough" - real
    // synthetic-chord unit tests (KeyDetectorTest) caught this outright
    // misclassifying triads (a G major triad read back as B minor) because
    // a 43Hz mainlobe is nearly FOUR semitones wide at these frequencies,
    // smearing each note's energy across several neighboring pitch classes.
    // At 65536 the mainlobe (~2.7Hz) is narrower than one semitone even at
    // MIN_HZ (~3.9Hz), so a played note's energy stays inside its own
    // pitch class. The tradeoff this buys precision with is time
    // resolution (~1.49s/window) - irrelevant here, since a whole-track key
    // estimate doesn't need to track anything faster than that. A true
    // Constant-Q transform (resolution scaling with frequency, not fixed)
    // would be the textbook-correct answer, but re-verified empirically via
    // real unit tests, this fixed-size window is good enough for a DJ
    // helper feature without that added complexity.
    private const val FFT_SIZE = 65536
    // Fewer windows than AudioAnalyzer's other passes (which use 300) -
    // each window here already covers ~1.49s of audio, so fewer of them are
    // needed to sample a track's harmonic content thoroughly, and it keeps
    // total cost (60 windows x a much bigger FFT) from growing unbounded.
    private const val CHROMA_WINDOWS = 60
    private const val MIN_HZ = 65f   // ~C2 - below this is sub-bass with no clear pitch class
    private const val MAX_HZ = 2100f // ~C7 - above this starts double-counting harmonics of lower notes

    /** Returns Camelot notation ("1A".."12B"), or null if [pcm] is too short
     * to analyze or carries no meaningful pitch content (silence). */
    fun detectKey(pcm: FloatArray, sampleRateHz: Int): String? {
        if (pcm.size < FFT_SIZE) return null
        val chroma = buildChromagram(pcm, sampleRateHz)
        val total = chroma.sum()
        if (total < 1e-6f) return null
        for (i in chroma.indices) chroma[i] = chroma[i] / total

        var bestPc = 0
        var bestIsMajor = true
        var bestScore = Float.NEGATIVE_INFINITY
        for (pc in 0 until 12) {
            val majorScore = correlate(chroma, MAJOR_PROFILE, pc)
            if (majorScore > bestScore) { bestScore = majorScore; bestPc = pc; bestIsMajor = true }
            val minorScore = correlate(chroma, MINOR_PROFILE, pc)
            if (minorScore > bestScore) { bestScore = minorScore; bestPc = pc; bestIsMajor = false }
        }
        return camelotCode(bestPc, bestIsMajor)
    }

    /** Pearson correlation between the normalized chroma vector and
     * [profile] rotated so its tonic (index 0) sits at candidate pitch
     * class [pc]. Highest correlation across all 24 (pc, mode) candidates
     * wins - the standard Krumhansl-Schmuckler decision rule. */
    private fun correlate(chroma: FloatArray, profile: FloatArray, pc: Int): Float {
        val rotated = FloatArray(12) { i -> profile[(i - pc + 12) % 12] }
        val chromaMean = chroma.average().toFloat()
        val profileMean = rotated.average().toFloat()
        var num = 0f
        var chromaSq = 0f
        var profileSq = 0f
        for (i in 0 until 12) {
            val cd = chroma[i] - chromaMean
            val pd = rotated[i] - profileMean
            num += cd * pd
            chromaSq += cd * cd
            profileSq += pd * pd
        }
        val denom = sqrt(chromaSq * profileSq)
        return if (denom > 1e-9f) num / denom else 0f
    }

    /**
     * Builds a 12-bin pitch-class energy histogram from representative
     * windows spread across [pcm] - same "one centered FFT window per
     * bucket" structure AudioAnalyzer.buildSpectralWaveform already uses
     * (same cost order, ~4.8x heavier per-FFT since this window is 4x
     * bigger, still a bounded one-shot per-track cost).
     */
    private fun buildChromagram(pcm: FloatArray, sampleRateHz: Int): FloatArray {
        val chroma = FloatArray(12)
        val perBucket = max(1, pcm.size / CHROMA_WINDOWS)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)

        for (b in 0 until CHROMA_WINDOWS) {
            val bucketStart = b * perBucket
            val bucketEnd = min(pcm.size, bucketStart + perBucket)
            if (bucketStart >= bucketEnd) continue
            val windowStart = ((bucketStart + bucketEnd) / 2 - FFT_SIZE / 2)
                .coerceIn(0, max(0, pcm.size - FFT_SIZE))

            for (i in 0 until FFT_SIZE) {
                val sampleIdx = windowStart + i
                val sample = if (sampleIdx < pcm.size) pcm[sampleIdx] else 0f
                // Hann window - same reasoning as buildSpectralWaveform: a
                // key estimate reads accumulated band energy, not just
                // peaks, so the window's own hard edges must not leak
                // energy across bins.
                val hann = 0.5f * (1f - cos(2.0 * Math.PI * i / (FFT_SIZE - 1)).toFloat())
                re[i] = sample * hann
                im[i] = 0f
            }
            Fft.transform(re, im)

            for (k in 1 until FFT_SIZE / 2) {
                val freqHz = k.toFloat() * sampleRateHz / FFT_SIZE
                if (freqHz < MIN_HZ || freqHz > MAX_HZ) continue
                val magnitude = sqrt(re[k] * re[k] + im[k] * im[k])
                // Pitch class relative to A4=440Hz (pc 9 = A in the C=0
                // convention used throughout this file), rounded to the
                // nearest semitone. Safe (always-non-negative) modulo since
                // pcRaw can go negative for freqHz < 440Hz.
                val semitonesFromA4 = 12.0 * ln(freqHz / 440.0) / ln(2.0)
                val pcRaw = Math.round(semitonesFromA4).toInt() + 9
                val pc = ((pcRaw % 12) + 12) % 12
                chroma[pc] += magnitude
            }
        }
        return chroma
    }

    /**
     * Major Camelot number follows the circle of fifths directly: each
     * ascending perfect fifth (+7 semitones) is +1 step on the wheel,
     * anchored so C major = 8B (the anchor point every published Camelot
     * table agrees on). Minor keys share their relative major's number (a
     * minor third up, +3 semitones) with letter "A" instead of "B" - e.g.
     * A minor, the relative minor of C major, is 8A.
     *
     * Deliberately derived from music theory rather than hand-typed as a
     * 24-entry lookup table - a formula can't silently transpose two
     * entries the way a manually-typed table could, and KeyDetectorTest
     * still checks the output against the full standard table for every
     * one of the 24 keys as a belt-and-suspenders cross-check.
     *
     * internal (not private) specifically so that exhaustive table test can
     * call this directly for all 24 (pitchClass, isMajor) combinations
     * without needing to synthesize and FFT-analyze audio for each one.
     */
    internal fun camelotCode(pitchClass: Int, isMajor: Boolean): String {
        val majorTonicPc = if (isMajor) pitchClass else (pitchClass + 3) % 12
        val number = ((7 * (majorTonicPc + 1)) % 12) + 1
        val letter = if (isMajor) "B" else "A"
        return "$number$letter"
    }
}
