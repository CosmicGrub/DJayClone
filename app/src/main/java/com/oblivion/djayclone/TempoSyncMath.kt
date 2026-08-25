package com.oblivion.djayclone

import kotlin.math.abs

/**
 * Ratio-aware sync target math - pure Kotlin, no Android/Compose/ExoPlayer
 * imports, fully unit-testable without instrumentation. Extracted out of
 * MixerScreen's sync-lock LaunchedEffect rather than left as inline
 * arithmetic there: this is the one piece of that effect worth testing in
 * isolation (the rest is state-management wiring, not math).
 *
 * The problem this replaces: the original sync-lock only ever computed a
 * 1:1 target (`leaderEffectiveBpm / followerBaseBpm`), so two tracks at a
 * genuine half/double-time relationship (a 140 BPM track and a 70 BPM
 * track, a real DnB-vs-dubstep pairing) would fight the lock - forced to
 * either double or halve to hit an artificial 1:1 target neither track
 * actually needs. Real DJ software (rekordbox's explicit x2/÷2 beatgrid
 * buttons) treats this as a named, expected relationship, not an edge case.
 */
object TempoSyncMath {

    /** Relative tolerance for judging a BPM ratio "close enough" to a
     * candidate. An engineering default, not a sourced industry constant -
     * no vendor publishes theirs. 3% keeps near-1.0 noise (BPM-detection
     * jitter) from ever being misread as a real half/double-time match:
     * the three candidates (0.5, 1.0, 2.0) are spaced >=50% apart, so
     * anything under ~25% tolerance has zero ambiguity between them. */
    const val DEFAULT_RATIO_TOLERANCE = 0.03f

    /** multiplier's real meaning: target followerEffectiveBpm =
     * leaderEffectiveBpm * multiplier. Not "the follower's speed
     * multiplier" directly - that would invert HALF_TIME/DOUBLE_TIME. */
    enum class SyncRatio(val multiplier: Float, val shortLabel: String) {
        UNITY(1.0f, ""),
        DOUBLE_TIME(2.0f, "×2"),
        HALF_TIME(0.5f, "½×"),
    }

    data class RatioMatch(val ratio: SyncRatio, val relativeError: Float)

    /**
     * ratio = followerEffectiveBpm / leaderEffectiveBpm, checked against
     * {1.0, 2.0, 0.5} in that priority order so near-unity noise is never
     * misread as an octave error. Returns null if nothing matches within
     * [tolerance] - the two decks are simply unrelated tempos, and the
     * caller should fall back to UNITY rather than force a false lock.
     *
     * Worked examples (the actual verification this function's correctness
     * rests on):
     *   leader=140, follower base=70 (effective 70, speed 1.0):
     *     ratio = 70/140 = 0.5 -> HALF_TIME, 0% error.
     *     target speed = (140*0.5)/70 = 1.0 -> no change needed.
     *     Matches Mixxx's own published 140/70 DnB-vs-dubstep example.
     *   leader=142, follower base=70:
     *     ratio = 70/142 = 0.4930 -> |0.4930-0.5|/0.5 = 1.41% -> HALF_TIME.
     *     target speed = (142*0.5)/70 = 1.0143 -> follower nudges +1.43%.
     *   leader=128, follower base=128: ratio=1.0 -> UNITY, 0% error.
     *   leader=128, follower base=85: ratio=1.505 -> nearest candidate
     *     (DOUBLE_TIME) is 24.75% off -> no match -> null (caller falls
     *     back to UNITY rather than forcing an ambiguous "close enough").
     */
    fun detectRatio(
        leaderEffectiveBpm: Float,
        followerEffectiveBpm: Float,
        tolerance: Float = DEFAULT_RATIO_TOLERANCE,
    ): RatioMatch? {
        if (leaderEffectiveBpm <= 0f || followerEffectiveBpm <= 0f) return null
        val raw = followerEffectiveBpm / leaderEffectiveBpm
        for (candidate in listOf(SyncRatio.UNITY, SyncRatio.DOUBLE_TIME, SyncRatio.HALF_TIME)) {
            val err = abs(raw - candidate.multiplier) / candidate.multiplier
            if (err < tolerance) return RatioMatch(candidate, err)
        }
        return null
    }

    /** followerBaseBpm = DeckUiState.bpm (unmodified, pre-speed reading
     * from AudioAnalyzer's one-shot pass) - not effectiveBpm, since the
     * result IS the new playbackSpeed to apply on top of that base. */
    fun targetPlaybackSpeed(leaderEffectiveBpm: Float, followerBaseBpm: Float, ratio: SyncRatio): Float {
        require(followerBaseBpm > 0f) { "followerBaseBpm must be > 0" }
        return (leaderEffectiveBpm * ratio.multiplier) / followerBaseBpm
    }
}

/** AUTO runs [TempoSyncMath.detectRatio]; the other three force that ratio
 * directly regardless of measurement, for when auto-detection disagrees
 * with what the DJ actually wants (e.g. deliberately half-timing a track
 * that isn't really in a 2:1 relationship, for a creative tempo drop). */
enum class SyncRatioMode { AUTO, UNITY, DOUBLE_TIME, HALF_TIME }
