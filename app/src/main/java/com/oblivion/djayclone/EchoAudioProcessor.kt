package com.oblivion.djayclone

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tempo-synced echo: a per-channel circular delay line with feedback. The
 * delay length is set from the deck's ANALYZED tempo (see DeckViewModel.
 * setEchoDivision and [BeatTimeMath]) - a 1/4, 1/2, or 1-beat tap spacing, not
 * a fixed time. Feedback is hard-coded well under 1.0 in this file (never
 * exposed to a UI value that could reach/exceed it) so repeats always decay.
 *
 * ## Activation contract (why this class never reports itself inactive)
 * Media3 reads [isActive] only inside configure()/flush(), and DefaultAudioSink
 * flushes only on a seek, an AudioTrack re-init, or a speed/pitch change. A
 * processor whose isActive() follows its own on/off switch is therefore
 * either missing from the chain when the DJ turns it on, or - worse - still
 * IN the chain when they turn it off: a processor that returns from
 * queueInput() without consuming its input makes DefaultAudioSink's drive
 * loop spin forever on the playback thread (no progress guard there). So this
 * class is active for as long as it is configured, always consumes all of its
 * input, and expresses "off" by passing audio through - bit-exactly when
 * fully idle - instead of by leaving the chain.
 *
 * ## Engage / disengage
 * Turning echo ON ramps both the wet level and the feed into the delay line
 * in over [ENGAGE_RAMP_SECONDS] (ramping only the wet level leaves the first
 * repeat, one tap later, starting with a hard step). Turning it OFF ramps the
 * feed out and lets what is already in the delay line ring out as a tail that
 * fades over ~2 taps, capped - the way hardware echo ends - rather than
 * chopping it (a click) or letting it linger for seconds. Once the tail is
 * gone the delay line is cleared and the class returns to pure pass-through.
 *
 * ## Flush semantics (seek vs. tempo change)
 * The sink runs the processors up to the AudioTrack buffer (250-750 ms)
 * AHEAD of the playhead. A seek (loop wrap, hot cue, scrub) reaches [onFlush]
 * with no end-of-stream drain first, and the sink then throws that queued
 * audio away UNHEARD - so the delay line must be emptied or it would replay
 * audio the listener never heard. A speed/pitch change instead drains the
 * pipeline into the live AudioTrack (queueEndOfStream) before flushing, so the
 * ring's contents WILL be heard and a nudge must not chop a ringing tail.
 * [onQueueEndOfStream] is how the two are told apart; Media3 1.4.1 only ever
 * calls queueEndOfStream() from DefaultAudioSink.drainToEndOfStream().
 *
 * ## Cross-thread contract
 * Identical shape to FilterAudioProcessor: the UI thread publishes @Volatile
 * targets, the audio thread reads them once per buffer and glides its own
 * local `currentDelaySamples` toward the target rather than jumping, so a
 * tempo change (nudge, sync engaging) doesn't click - see [GLIDE_RATE]. The
 * delay-line contents are audio-thread-owned; the UI thread may only *ask*
 * for a clear via [clearBuffer], which the audio thread performs.
 */
class EchoAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false

    /** Length of one echo tap in SOURCE-time seconds. The processor converts
     * to samples with its own configured sample rate, so the caller never
     * has to know (or be wrong about) the current track's rate. Sonic, which
     * sits after this processor in the chain, applies the playback speed to
     * the echo taps along with everything else - see [BeatTimeMath]. */
    @Volatile var targetDelaySeconds: Float = 0f

    @Volatile var wetMix: Float = 0.35f
    // Stage 8: user-adjustable feedback/wet-mix ceilings, published as ONE
    // @Volatile reference - same single-object-swap discipline as
    // FilterAudioProcessor.rangeConfig, so the audio thread never observes
    // a torn pair mid-buffer.
    @Volatile private var rangeConfig = EchoRangeConfig()
    @Volatile private var clearRequested = false

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
    private var inputGate = 0f      // 0..1: how much live input is being fed into the delay line
    private var wet = 0f            // 0..mix: current wet level (also the tail's fade envelope)
    private var ringIsClear = true
    private var drainedBeforeFlush = false // set by onQueueEndOfStream, consumed by onFlush

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        // The delay line has a left and a right ring. More channels than
        // that would silently share the right ring; NOT_SET makes this
        // processor inactive for such a stream (the pipeline skips it)
        // instead of failing playback.
        if (inputAudioFormat.channelCount !in 1..2) return AudioFormat.NOT_SET
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
        inputGate = 0f
        wet = 0f
        ringIsClear = true
        drainedBeforeFlush = false
        currentDelaySamples = (targetDelaySeconds * sampleRateHz).coerceIn(1f, (capacity - 1).toFloat())
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0 || channelCount == 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        if (clearRequested) {
            clearRequested = false
            clearState()
        }

        val on = enabled // one volatile read, pinned for the whole buffer
        if (!on && wet <= 0f && inputGate <= 0f) {
            // Fully idle (or the tail just finished): bit-exact pass-through.
            if (!ringIsClear) clearState()
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        // Single volatile read, pinned to a local val for the rest of this
        // buffer - same reasoning as FilterAudioProcessor.recomputeCoefficients.
        val cfg = rangeConfig
        val target = (targetDelaySeconds * sampleRateHz).coerceIn(1f, (capacitySamples - 1).toFloat())
        val mix = wetMix.coerceIn(0f, cfg.maxWetMix)
        val feedback = cfg.maxFeedbackGain
        val rampUp = mix / (sampleRateHz * ENGAGE_RAMP_SECONDS)
        val gateStep = 1f / (sampleRateHz * ENGAGE_RAMP_SECONDS)
        val tailSamples = (2f * currentDelaySamples)
            .coerceIn(sampleRateHz * MIN_TAIL_SECONDS, sampleRateHz * MAX_TAIL_SECONDS)
        val rampDown = mix / tailSamples

        while (inputBuffer.hasRemaining()) {
            currentDelaySamples += (target - currentDelaySamples) * GLIDE_RATE
            if (on) {
                wet = min(mix, wet + rampUp)
                inputGate = min(1f, inputGate + gateStep)
            } else {
                wet = max(0f, wet - rampDown)
                inputGate = max(0f, inputGate - gateStep)
            }

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

                val input = inputBuffer.short / 32768f
                val fed = input * inputGate + delayed * feedback
                // Decaying feedback would otherwise walk into denormal range
                // during long silences, which is slow on some ARM cores.
                ring[wrappedWrite] = if (abs(fed) < DENORMAL_FLOOR) 0f else fed

                val out = input * (1f - wet) + delayed * wet
                outputBuffer.putShort(quantize(out))
            }
            writeIndex++
        }
        ringIsClear = false
        outputBuffer.flip()
    }

    override fun onQueueEndOfStream() {
        drainedBeforeFlush = true
    }

    override fun onFlush() {
        // Drained first = a speed/pitch change: what is in the ring is about
        // to be heard, keep it (a nudge must not chop a ringing tail). No
        // drain = a seek or an AudioTrack re-init: the sink is discarding the
        // audio it had queued ahead of the playhead, so the ring holds audio
        // that will never be heard - empty it. Only the ring: wet/inputGate
        // are the DJ's on/off state and carry straight through a loop wrap.
        if (!drainedBeforeFlush) clearRing()
        drainedBeforeFlush = false
    }

    override fun onReset() {
        ringL = FloatArray(0)
        ringR = FloatArray(0)
        writeIndex = 0
        currentDelaySamples = 0f
        inputGate = 0f
        wet = 0f
        ringIsClear = true
        drainedBeforeFlush = false
    }

    /** Asks the audio thread to silence the delay line (and drop any tail) on
     * its next buffer - called from DeckViewModel.loadTrack() so a track
     * change can never leak the previous track's audio through the feedback
     * path. Deliberately a request, not a direct fill: the ring belongs to
     * the audio thread, and filling it from the UI thread mid-buffer is a
     * data race. Echo's on/off + division SETTING is unaffected. */
    fun clearBuffer() {
        clearRequested = true
    }

    private fun clearRing() {
        ringL.fill(0f)
        ringR.fill(0f)
        writeIndex = 0
        ringIsClear = true
    }

    private fun clearState() {
        clearRing()
        inputGate = 0f
        wet = 0f
    }

    /** Symmetric with the /32768 on the way in: a neutral path is bit-exact. */
    private fun quantize(x: Float): Short =
        Math.round(x * 32768f).coerceIn(-32768, 32767).toShort()

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
        // Wet level in, and input feed out, on engage/disengage.
        private const val ENGAGE_RAMP_SECONDS = 0.005f
        // The tail after turning echo off fades over two taps, bounded so a
        // very short tap still rings audibly and a very long one doesn't
        // linger.
        private const val MIN_TAIL_SECONDS = 0.25f
        private const val MAX_TAIL_SECONDS = 1.5f
        private const val DENORMAL_FLOOR = 1e-20f
    }
}
