package com.oblivion.djayclone

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crossfader law. Pure Kotlin, no Android imports, so it is unit-tested on the
 * JVM (MixerMathTest).
 *
 *  - [SMOOTH]: equal-power (cos/sin). Constant perceived loudness through the
 *    blend; 0.707 each at centre. This is what the app has always done.
 *  - [LINEAR]: straight amplitude fade. Dips ~3 dB in the middle for
 *    uncorrelated material - a flatter, more "radio" blend.
 *  - [SHARP]: the club/"cut-in" law. Each side stays at FULL volume until the
 *    fader passes the centre, then fades to zero at the far end. Both decks
 *    are at unity in the middle (the point of the curve: you can hold a mix
 *    open with the fader parked at centre, and cut in/out near either end),
 *    so the summed level at centre is hotter than SMOOTH. That is how a real
 *    mixer's sharp curve behaves; gain-staging is the DJ's job.
 */
enum class CrossfaderCurve(val label: String) {
    SMOOTH("Smooth"),
    LINEAR("Linear"),
    SHARP("Sharp"),
}

object MixerMath {

    private const val HALF_PI = PI / 2.0

    /** Per-deck crossfader multipliers for fader position [x] (0 = full A,
     * 1 = full B), as (factorA, factorB). [x] is clamped to 0..1;
     * [reversed] swaps the decks' ends ("hamster" mode). */
    fun crossfadeFactors(x: Float, curve: CrossfaderCurve, reversed: Boolean = false): Pair<Float, Float> {
        val p = (if (reversed) 1f - x else x).coerceIn(0f, 1f)
        val a: Float
        val b: Float
        when (curve) {
            CrossfaderCurve.SMOOTH -> {
                a = cos(p.toDouble() * HALF_PI).toFloat()
                b = sin(p.toDouble() * HALF_PI).toFloat()
            }
            CrossfaderCurve.LINEAR -> {
                a = 1f - p
                b = p
            }
            CrossfaderCurve.SHARP -> {
                a = ((1f - p) * 2f).coerceIn(0f, 1f)
                b = (p * 2f).coerceIn(0f, 1f)
            }
        }
        // cos(pi/2) is 6e-17 in double, which survives the float cast as a
        // tiny non-zero: snap the ends to an exact zero so a fully-cut deck
        // is truly silent.
        return Pair(if (a < 1e-6f) 0f else a, if (b < 1e-6f) 0f else b)
    }

    /** The multiplier handed to ExoPlayer's Player.volume: the DJ's channel
     * fader x Auto Gain's trim x the crossfader factor, clamped to the
     * 0..1 range Player.volume accepts (it can only attenuate). */
    fun effectiveVolume(channelGain: Float, autoGainTrim: Float, crossfadeFactor: Float): Float =
        (channelGain * autoGainTrim * crossfadeFactor).coerceIn(0f, 1f)
}

/**
 * Continuous tempo-sync follower maths, extracted from MixerScreen's old
 * LaunchedEffect so it is testable and so it can run for as long as the
 * mixer exists (not only while the Mixer screen happens to be composed -
 * opening Settings or the Library used to silently stop a locked follower
 * from tracking its leader).
 */
object SyncController {

    const val B_FOLLOWS_A = "B_FOLLOWS_A"
    const val A_FOLLOWS_B = "A_FOLLOWS_B"

    /** Only changes below this are treated as "already there", so the
     * follower is not re-set every tick over float noise. */
    const val SPEED_EPSILON = 0.0005f

    data class Target(val followerIsA: Boolean, val speed: Float)

    /**
     * What (if anything) the locked follower's playback speed should be set
     * to right now. Returns null when no lock is engaged, either deck has no
     * detected BPM, or the follower is already within [SPEED_EPSILON] of
     * where it should be.
     *
     * The returned speed is already clamped to DeckViewModel's real speed
     * range (0.5..2.0): comparing the unclamped target against the current
     * speed would re-fire forever for a pair whose ideal ratio is out of
     * range.
     */
    fun compute(
        lock: String?,
        mode: SyncRatioMode,
        tolerance: Float,
        bpmA: Float?,
        bpmB: Float?,
        speedA: Float,
        speedB: Float,
    ): Target? {
        if (lock == null || bpmA == null || bpmB == null) return null
        if (bpmA <= 0f || bpmB <= 0f) return null
        val followerIsA = when (lock) {
            B_FOLLOWS_A -> false
            A_FOLLOWS_B -> true
            else -> return null
        }
        val leaderEffective = if (followerIsA) bpmB * speedB else bpmA * speedA
        val followerBase = if (followerIsA) bpmA else bpmB
        val followerSpeed = if (followerIsA) speedA else speedB
        val ratio = resolveRatio(mode, tolerance, leaderEffective, followerBase * followerSpeed)
        val target = TempoSyncMath.targetPlaybackSpeed(leaderEffective, followerBase, ratio)
            .coerceIn(DeckSpeedRange.MIN, DeckSpeedRange.MAX)
        return if (abs(target - followerSpeed) > SPEED_EPSILON) Target(followerIsA, target) else null
    }

    fun resolveRatio(
        mode: SyncRatioMode,
        tolerance: Float,
        leaderEffectiveBpm: Float,
        followerEffectiveBpm: Float,
    ): TempoSyncMath.SyncRatio = when (mode) {
        SyncRatioMode.AUTO ->
            TempoSyncMath.detectRatio(leaderEffectiveBpm, followerEffectiveBpm, tolerance)?.ratio
                ?: TempoSyncMath.SyncRatio.UNITY
        SyncRatioMode.UNITY -> TempoSyncMath.SyncRatio.UNITY
        SyncRatioMode.DOUBLE_TIME -> TempoSyncMath.SyncRatio.DOUBLE_TIME
        SyncRatioMode.HALF_TIME -> TempoSyncMath.SyncRatio.HALF_TIME
    }
}

/** The playback-speed range DeckViewModel.setPlaybackSpeed accepts. One
 * definition so the sync maths and the deck can never disagree about it. */
object DeckSpeedRange {
    const val MIN = 0.5f
    const val MAX = 2.0f
}
