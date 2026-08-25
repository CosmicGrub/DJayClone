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
 * Cross-thread contract: [setKnob] is called from the UI thread (Compose
 * slider callback); [queueInput] runs on ExoPlayer's internal audio-
 * processing thread. [target] is the single @Volatile publish point - one
 * writer, one reader, no locks (a lock here risks the audio thread blocking
 * on the UI thread, a direct path to an audible dropout). The audio thread
 * reads it once per buffer and locally slews its *live* knob position
 * toward the target rather than jumping directly - what prevents zipper/
 * stepping noise from a Compose slider firing onValueChange 60-120x/sec
 * during a drag.
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
        recomputeCoefficients(liveKnob)
        return inputAudioFormat
    }

    // Active whenever the published target OR the (still-slewing) live knob
    // is off-center, so the tail end of a slew-back-to-zero still processes
    // instead of snapping silently inactive mid-transition.
    override fun isActive(): Boolean = abs(target) >= DEAD_ZONE || abs(liveKnob) >= DEAD_ZONE

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0 || channelCount == 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // Slew toward the latest published target once per buffer - no
        // trig or allocation inside the per-sample loop below.
        liveKnob += (target - liveKnob) * SMOOTHING
        if (abs(liveKnob - target) < 0.0005f) liveKnob = target
        recomputeCoefficients(liveKnob)
        val bypass = abs(liveKnob) < DEAD_ZONE
        val useLowpass = liveKnob < 0f

        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        while (inputBuffer.hasRemaining()) {
            for (ch in 0 until channelCount) {
                val sampleShort = inputBuffer.short
                if (bypass) {
                    outputBuffer.putShort(sampleShort)
                    continue
                }
                val input = sampleShort / 32768f
                val v3 = input - ic2eq[ch]
                val v1 = a1 * ic1eq[ch] + a2 * v3
                val v2 = ic2eq[ch] + a2 * ic1eq[ch] + a3 * v3
                ic1eq[ch] = 2f * v1 - ic1eq[ch]
                ic2eq[ch] = 2f * v2 - ic2eq[ch]
                val out = if (useLowpass) v2 else (input - invQ * v1 - v2)
                val clamped = out.coerceIn(-1f, 1f)
                outputBuffer.putShort(Math.round(clamped * 32767f).toShort())
            }
        }
        outputBuffer.flip()
    }

    private fun recomputeCoefficients(knob: Float) {
        // Single volatile read, pinned to a local val for the rest of this
        // buffer - guarantees lpfMinHz/hpfMaxHz stay internally consistent
        // even if the UI thread republishes rangeConfig mid-buffer.
        val cfg = rangeConfig
        val cutoffHz = cutoffForKnob(knob, cfg)
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

    override fun onFlush() {
        ic1eq.fill(0f)
        ic2eq.fill(0f)
    }

    override fun onReset() {
        ic1eq = FloatArray(0)
        ic2eq = FloatArray(0)
        liveKnob = 0f
        target = 0f
    }

    companion object {
        // Per-buffer slew factor toward the target knob position - tuned so
        // a step change reaches ~95% of the way there in roughly 15-20ms at
        // typical ExoPlayer audio buffer sizes, fast enough to feel live,
        // slow enough that no single buffer-to-buffer jump is audible.
        private const val SMOOTHING = 0.35f
        private const val DEAD_ZONE = 0.02f
    }
}
