package com.oblivion.djayclone

/**
 * Time-base arithmetic for beat-length things (beat loops, tempo-synced echo).
 * Plain Kotlin, no Android imports, so it is unit-testable on the JVM.
 *
 * Everything here is in SOURCE time - the time base of the file itself - not
 * wall-clock time, and it is derived from the track's ANALYZED (base) BPM, never
 * the speed-adjusted "effective" BPM:
 *
 *  - Loop positions are ExoPlayer media positions, which advance at file
 *    speed regardless of the playback-speed setting.
 *  - The echo processor sits BEFORE Media3's Sonic (speed/pitch) stage in the
 *    audio chain, so its delay is counted in source samples and Sonic then
 *    stretches or shrinks the whole thing, echo taps included, by the speed.
 *
 * Because Sonic already applies the speed, a beat measured in source time is
 * exactly one beat of the *effective* tempo once it comes out of the speaker.
 * Feeding the effective BPM in here as well would apply the speed a second
 * time (a 12% error at 88% speed) - which is what this replaced.
 */
object BeatTimeMath {

    /** Length of [beats] beats in source-time milliseconds. */
    fun beatsToSourceMs(baseBpm: Float, beats: Float): Double =
        60_000.0 / baseBpm.toDouble() * beats.toDouble()

    /** Same length in seconds, for processors that count time and derive
     * their own sample counts from their own configured sample rate. */
    fun beatsToSourceSeconds(baseBpm: Float, beats: Float): Double =
        beatsToSourceMs(baseBpm, beats) / 1000.0

    /** What that source-time length becomes at the speaker after the speed
     * stage. Not used by the app; the tests use it to state the invariant. */
    fun sourceToWallMs(sourceMs: Double, speed: Float): Double = sourceMs / speed.toDouble()
}
