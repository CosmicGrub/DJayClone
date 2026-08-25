package com.oblivion.djayclone

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tempo-synced echo: a per-channel circular delay line with feedback. Delay
 * length is driven by the deck's detected BPM (see DeckViewModel.
 * setEchoDivision) - a 1/4, 1/2, or 1-beat tap spacing, not a fixed time.
 * Feedback is hard-coded well under 1.0 in this file (never exposed to a UI
 * value that could reach/exceed it) so repeats always decay.
 *
 * Cross-thread contract: identical shape to FilterAudioProcessor - the UI
 * thread publishes @Volatile targets, the audio thread reads them once per
 * buffer and glides its own local `currentDelaySamples` toward the target
 * rather than jumping, so a tempo change (pitch nudge, sync-lock engaging)
 * doesn't click - see [GLIDE_RATE].
 */
class EchoAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false
    @Volatile var targetDelaySamples: Float = 0f
    @Volatile var wetMix: Float = 0.35f
    // Stage 8: user-adjustable feedback/wet-mix ceilings, published as ONE
    // @Volatile reference - same single-object-swap discipline as
    // FilterAudioProcessor.rangeConfig, so the audio thread never observes
    // a torn pair mid-buffer.
    @Volatile private var rangeConfig = EchoRangeConfig()

    /** Re-coerces to MAX_SAFE_FEEDBACK regardless of what's persisted - an
     * absolute ceiling that can never be raised past 0.7 by Settings, a
     * hand-edited prefs file, or any future bug in SettingsRepository's own
     * clamping. Repeats must always decay. */
    fun setRangeConfig(config: EchoRangeConfig) {
        rangeConfig = EchoRangeConfig(
            maxFeedbackGain = config.maxFeedbackGain.coerceIn(0.1f, MAX_SAFE_FEEDBACK),
            maxWetMix = config.maxWetMix.coerceIn(0.5f, 1f),
        )
    }

    var sampleRateHz: Int = 44100
        private set
    var capacitySamples: Int = 0
        private set

    // Audio-thread-owned - never touched from the UI thread.
    private var currentDelaySamples = 0f
    private var ringL = FloatArray(0)
    private var ringR = FloatArray(0) // channel 1 ring; unused when channelCount == 1
    private var writeIndex = 0
    private var mask = 0
    private var channelCount = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        val minCapacity = (MAX_DELAY_SECONDS * sampleRateHz).toInt()
        var capacity = 1
        while (capacity < minCapacity) capacity = capacity shl 1
        capacitySamples = capacity
        mask = capacity - 1
        ringL = FloatArray(capacity)
        ringR = FloatArray(capacity)
        writeIndex = 0
        currentDelaySamples = targetDelaySamples.coerceIn(1f, (capacity - 1).toFloat())
        return inputAudioFormat
    }

    override fun isActive(): Boolean = enabled

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!enabled || channelCount == 0) return
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // Single volatile read, pinned to a local val for the rest of this
        // buffer - same reasoning as FilterAudioProcessor.recomputeCoefficients.
        val cfg = rangeConfig
        val target = targetDelaySamples.coerceIn(1f, (capacitySamples - 1).toFloat())
        val mix = wetMix.coerceIn(0f, cfg.maxWetMix)
        val feedback = cfg.maxFeedbackGain

        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        while (inputBuffer.hasRemaining()) {
            currentDelaySamples += (target - currentDelaySamples) * GLIDE_RATE

            // Position math done entirely in terms of the already-wrapped
            // write index (always in [0, capacitySamples)), NOT the raw
            // ever-growing `writeIndex` counter - Float loses exact-integer
            // precision above 2^24 (~16.7M), which a single several-minute
            // track easily exceeds at 44.1kHz+. Using the wrapped, bounded
            // value keeps this precise for arbitrarily long playback.
            val wrappedWrite = writeIndex and mask
            var readPos = wrappedWrite - currentDelaySamples
            if (readPos < 0f) readPos += capacitySamples
            val i0 = readPos.toInt()
            val frac = readPos - i0

            for (ch in 0 until channelCount) {
                val ring = if (ch == 0) ringL else ringR
                val s0 = ring[i0 and mask]
                val s1 = ring[(i0 + 1) and mask]
                val delayed = s0 + (s1 - s0) * frac

                val sampleShort = inputBuffer.short
                val input = sampleShort / 32768f
                ring[wrappedWrite] = input + delayed * feedback

                val out = input * (1f - mix) + delayed * mix
                val clamped = out.coerceIn(-1f, 1f)
                outputBuffer.putShort(Math.round(clamped * 32767f).toShort())
            }
            writeIndex++
        }
        outputBuffer.flip()
    }

    override fun onFlush() {
        ringL.fill(0f)
        ringR.fill(0f)
        writeIndex = 0
    }

    override fun onReset() {
        ringL = FloatArray(0)
        ringR = FloatArray(0)
        writeIndex = 0
        currentDelaySamples = 0f
    }

    /** Explicit, defense-in-depth silence of the delay line's audible
     * content - called directly from DeckViewModel.loadTrack() in addition
     * to whatever Media3's own seek/media-item-transition flush() lifecycle
     * triggers, so a track change can never leak the previous track's audio
     * through the feedback path even if that lifecycle doesn't fire exactly
     * when expected (this codebase already found one Media3 API -
     * createMessage() - silently not behaving as documented; don't stake
     * correctness on an assumption alone here either). */
    fun clearBuffer() {
        ringL.fill(0f)
        ringR.fill(0f)
    }

    companion object {
        // 1 beat at a defensive 40 BPM floor is 1500ms; 2.5s covers that
        // with margin for the widest supported division. NOT exposed as a
        // live Stage 8 setting: it sizes the ring buffer arrays allocated
        // in onConfigure() - live-resizing them while the audio thread is
        // concurrently indexing into them isn't a safe volatile-swap
        // operation, unlike the scalar feedback/wetMix ceilings above. If a
        // future stage wants this configurable, it must take effect only at
        // the next onConfigure() (next loadTrack()), not mid-track.
        private const val MAX_DELAY_SECONDS = 2.5f
        // Absolute ceiling on maxFeedbackGain (see setRangeConfig) - well
        // under the 1.0 sustain/growth threshold, and can never be raised
        // past this by Settings/a corrupted prefs file/a future bug, so
        // repeats always decay regardless of what the user picks.
        private const val MAX_SAFE_FEEDBACK = 0.7f
        // Per-sample glide toward the target delay length, so a tempo
        // change re-targets smoothly instead of jumping the read pointer.
        private const val GLIDE_RATE = 0.02f
    }
}
