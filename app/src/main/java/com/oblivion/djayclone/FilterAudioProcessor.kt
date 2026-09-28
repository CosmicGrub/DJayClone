package com.oblivion.djayclone

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tan

/**
 * Single-knob DJ-style filter: a topology-preserving-transform (TPT) state-
 * variable filter (Simper form), one shared per-sample integrator state
 * producing simultaneous low-pass/high-pass taps - sweeping the knob through
 * center never hits a structural discontinuity the way switching between
 * two independent biquads (with independently-evolving state) would.
 *
 * knob in [-1, 1]; 0 = true bypass. knob<0 sweeps toward a low-pass cutoff
 * (20kHz -> 150Hz), knob>0 sweeps toward a high-pass cutoff (20Hz -> 7kHz),
 * both log-mapped to match how frequency is perceived. Resonance (Q) rises
 * toward the extremes for the "sweeping, slightly honky" character real DJ
 * mixer filters have.
 *
 * ## Activation contract (why there is no isActive() override)
 * Media3 reads isActive() only inside configure()/flush(), and DefaultAudioSink
 * flushes only on a seek, an AudioTrack re-init, or a speed/pitch change. The
 * previous version reported itself inactive at knob 0, which left it OUT of
 * the chain until something unrelated flushed: moving the knob mid-play did
 * nothing until the next cue/loop wrap/nudge. It is now active for as long as
 * it is configured, always consumes its input, and does its "bypass" by
 * output weighting: the SVF runs on every sample so its state is always warm,
 * and a dry/wet weight - exactly 0 inside the dead zone, ramping to 1 just
 * outside it - decides what leaves. At weight 0 the original samples are
 * written back untouched (bit-exact).
 *
 * ## Cross-thread contract
 * [setKnob] is called from the UI thread (Compose slider callback); [queueInput]
 * runs on ExoPlayer's internal audio-processing thread. [target] is the single
 * @Volatile publish point - one writer, one reader, no locks (a lock here
 * risks the audio thread blocking on the UI thread, a direct path to an
 * audible dropout). The audio thread reads it once per buffer and locally
 * slews its *live* knob position toward the target rather than jumping
 * directly - what prevents zipper/stepping noise from a Compose slider firing
 * onValueChange 60-120x/sec during a drag.
 */
class FilterAudioProcessor : BaseAudioProcessor() {

    @Volatile private var target = 0f
    // Stage 8: user-adjustable LPF/HPF cutoff endpoints, published as ONE
    // @Volatile reference (not two independent primitives) so the audio
    // thread can never observe a torn/mismatched pair mid-buffer - same
    // discipline as `target` above, applied to a config value instead of a
    // live performance parameter.
    @Volatile private var rangeConfig = FilterRangeConfig()

    fun setKnob(knob: Float) {
        target = knob.coerceIn(-1f, 1f)
    }

    /** Re-coerces defensively inside the processor itself, not trusting
     * SettingsRepository's own clamping alone - this is the last line of
     * defense against an audible instability if prefs were ever hand-edited
     * or corrupted. */
    fun setRangeConfig(config: FilterRangeConfig) {
        rangeConfig = FilterRangeConfig(
            lpfMinHz = config.lpfMinHz.coerceIn(60f, 1000f),
            hpfMaxHz = config.hpfMaxHz.coerceIn(2000f, 15000f),
        )
    }

    // Audio-thread-owned smoothed knob position and derived coefficients -
    // never touched from the UI thread.
    private var liveKnob = 0f
    private var wetWeight = 0f   // dry/wet weight applied at the end of the previous buffer
    private var lowSide = true   // which side of centre the shared state was last running for
    private var sampleRateHz = 44100
    private var channelCount = 0

    // Per-channel TPT-SVF integrator state.
    private var ic1eq = FloatArray(0)
    private var ic2eq = FloatArray(0)

    private var g = 0f
    private var invQ = 1f / 0.7f
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        ic1eq = FloatArray(channelCount)
        ic2eq = FloatArray(channelCount)
        wetWeight = 0f
        recomputeCoefficients(liveKnob)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0 || channelCount == 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // Slew toward the latest published target once per buffer - no
        // trig or allocation inside the per-sample loop below.
        liveKnob += (target - liveKnob) * SMOOTHING
        if (abs(liveKnob - target) < 0.0005f) liveKnob = target

        // Crossing centre swaps which tap is read (LP <-> HP) on ONE shared
        // state whose meaning is tied to the cutoff it was running at. Doing
        // that swap at a buffer boundary while the filter is still audible is
        // an instant jump between two unrelated signals (up to ~30% of full
        // scale on a fast sweep). So: while still audibly filtering, keep
        // running the OLD side's taps and coefficients and fade to dry; only
        // once dry, restart the state from rest on the new side.
        val sideNow = if (liveKnob == 0f) lowSide else liveKnob < 0f
        var newWet = wetWeightFor(abs(liveKnob))
        // Exactly at centre cutoffForKnob() would pick the low-pass branch even
        // when the state is running on the high-pass side; stay on our side.
        var coefKnob = if (liveKnob == 0f && !lowSide) 1e-6f else liveKnob
        if (sideNow != lowSide) {
            if (wetWeight > 0f) {
                newWet = 0f
                coefKnob = if (lowSide) -abs(liveKnob) else abs(liveKnob)
            } else {
                ic1eq.fill(0f)
                ic2eq.fill(0f)
                lowSide = sideNow
            }
        }
        recomputeCoefficients(coefKnob)
        val useLowpass = lowSide

        val frames = remaining / (2 * channelCount)
        val step = if (frames > 0) (newWet - wetWeight) / frames else 0f
        var w = wetWeight

        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        while (inputBuffer.hasRemaining()) {
            w += step
            for (ch in 0 until channelCount) {
                val sampleShort = inputBuffer.short
                val input = sampleShort / 32768f
                // Always advance the state so it is warm the instant the
                // knob leaves the dead zone.
                val v3 = input - ic2eq[ch]
                val v1 = a1 * ic1eq[ch] + a2 * v3
                val v2 = ic2eq[ch] + a2 * ic1eq[ch] + a3 * v3
                ic1eq[ch] = 2f * v1 - ic1eq[ch]
                ic2eq[ch] = 2f * v2 - ic2eq[ch]
                if (w <= 0f) {
                    outputBuffer.putShort(sampleShort) // exactly the original sample
                } else {
                    val wetOut = if (useLowpass) v2 else (input - invQ * v1 - v2)
                    val out = input + (wetOut - input) * w
                    outputBuffer.putShort(Math.round(out * 32768f).coerceIn(-32768, 32767).toShort())
                }
            }
        }
        wetWeight = newWet
        sanitizeState()
        outputBuffer.flip()
    }

    /** 0 inside the dead zone (exact bypass), ramping to 1 by [WET_FULL_KNOB]
     * so leaving the dead zone is a short crossfade, not a step. */
    private fun wetWeightFor(absKnob: Float): Float =
        ((absKnob - DEAD_ZONE) / (WET_FULL_KNOB - DEAD_ZONE)).coerceIn(0f, 1f)

    private fun recomputeCoefficients(knob: Float) {
        // Single volatile read, pinned to a local val for the rest of this
        // buffer - guarantees lpfMinHz/hpfMaxHz stay internally consistent
        // even if the UI thread republishes rangeConfig mid-buffer.
        val cfg = rangeConfig
        // The open end of the low-pass sweep is a fixed 20 kHz, which is
        // ABOVE Nyquist at 22.05/24/32 kHz sources - tan() then goes
        // negative and the filter collapses to NaN/silence. Clamp below it.
        val cutoffHz = cutoffForKnob(knob, cfg).coerceAtMost(MAX_CUTOFF_FRACTION_OF_FS * sampleRateHz)
        val q = (0.7f + 0.8f * (knob * knob)).coerceAtLeast(0.1f)
        g = tan((Math.PI * cutoffHz / sampleRateHz).toFloat())
        invQ = 1f / q
        a1 = 1f / (1f + g * (g + invQ))
        a2 = g * a1
        a3 = g * a2
    }

    private fun cutoffForKnob(knob: Float, cfg: FilterRangeConfig): Float = if (knob <= 0f) {
        // Low-pass: 20000Hz (knob=0, always open) down to the user-
        // configured lpfMinHz (knob=-1), log-mapped. The open-side endpoint
        // (20000Hz) stays hardcoded, not exposed - see the class doc comment.
        20000f * (cfg.lpfMinHz / 20000f).pow(-knob)
    } else {
        // High-pass: 20Hz (knob=0, always open) up to the user-configured
        // hpfMaxHz (knob=1), log-mapped.
        20f * (cfg.hpfMaxHz / 20f).pow(knob)
    }

    /** Non-finite or runaway state -> rest; denormal-sized state -> 0 (slow on
     * some ARM cores, and it accumulates during long digital silence). */
    private fun sanitizeState() {
        for (c in 0 until channelCount) {
            val s1 = ic1eq[c]
            val s2 = ic2eq[c]
            ic1eq[c] = if (!(abs(s1) < STATE_LIMIT)) 0f else if (abs(s1) < DENORMAL_FLOOR) 0f else s1
            ic2eq[c] = if (!(abs(s2) < STATE_LIMIT)) 0f else if (abs(s2) < DENORMAL_FLOOR) 0f else s2
        }
    }

    // Deliberately NO onFlush override: Media3 flushes on every seek, AudioTrack
    // re-init and speed change. Zeroing the integrators there put a step of
    // roughly the signal's own amplitude into the output on every tempo nudge.

    override fun onReset() {
        // ExoPlayer resets the sink when the audio renderer is disabled or
        // reset (stop(), release, some stream changes). A plain same-format
        // track reload only flushes it - measured on a real device - so this
        // is defensive rather than a fix for a reported drop. Only audio-
        // thread state goes: the user's knob position ([target]) is UI state
        // ("Keep FX across track load"), and the deck UI keeps showing it, so
        // a reset must not silently zero what the audio is doing.
        ic1eq = FloatArray(0)
        ic2eq = FloatArray(0)
        wetWeight = 0f
    }

    companion object {
        // Per-buffer slew factor toward the target knob position - tuned so
        // a step change reaches ~95% of the way there in roughly 15-20ms at
        // typical ExoPlayer audio buffer sizes, fast enough to feel live,
        // slow enough that no single buffer-to-buffer jump is audible.
        private const val SMOOTHING = 0.35f
        private const val DEAD_ZONE = 0.02f
        // Knob distance from centre at which the filter is fully "in".
        private const val WET_FULL_KNOB = 0.06f
        private const val MAX_CUTOFF_FRACTION_OF_FS = 0.45f
        private const val STATE_LIMIT = 1e6f
        private const val DENORMAL_FLOOR = 1e-15f
    }
}
