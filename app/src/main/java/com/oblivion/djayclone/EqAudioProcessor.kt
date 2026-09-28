package com.oblivion.djayclone

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3-band EQ (Low/Mid/High) - the per-band kill/boost trim a real mixer
 * channel strip has ALONGSIDE its sweep filter, not instead of it.
 * FilterAudioProcessor is untouched; this is a new, separate processor
 * sitting next to it in the chain.
 *
 * Three cascaded biquads (low-shelf, peaking/bell, high-shelf), Direct Form
 * 2 Transposed - the numerically-stable structure real-time audio code
 * uses. Coefficients follow the RBJ Audio EQ Cookbook, the standard public-
 * domain formula set for exactly this filter family.
 *
 * Gain range -26dB..+6dB matches a real DJ mixer 3-band EQ's published spec
 * (a "kill" that's perceptually silent without a separate mute switch, plus
 * a modest boost headroom) - not an arbitrary range.
 *
 * Same cross-thread contract as FilterAudioProcessor: setLowDb/setMidDb/
 * setHighDb are UI-thread writes to @Volatile targets; queueInput (audio
 * thread) slews its own live copy toward each target once per buffer to
 * avoid zipper noise from a Slider firing onValueChange rapidly mid-drag.
 */
class EqAudioProcessor : BaseAudioProcessor() {

    @Volatile private var targetLowDb = 0f
    @Volatile private var targetMidDb = 0f
    @Volatile private var targetHighDb = 0f

    fun setLowDb(db: Float) { targetLowDb = db.coerceIn(MIN_DB, MAX_DB) }
    fun setMidDb(db: Float) { targetMidDb = db.coerceIn(MIN_DB, MAX_DB) }
    fun setHighDb(db: Float) { targetHighDb = db.coerceIn(MIN_DB, MAX_DB) }

    // Audio-thread-owned smoothed gains and derived coefficients.
    private var liveLowDb = 0f
    private var liveMidDb = 0f
    private var liveHighDb = 0f

    private var sampleRateHz = 44100
    private var channelCount = 0

    // Per-channel Direct Form 2 Transposed state, two taps per band.
    private lateinit var lowZ1: FloatArray
    private lateinit var lowZ2: FloatArray
    private lateinit var midZ1: FloatArray
    private lateinit var midZ2: FloatArray
    private lateinit var highZ1: FloatArray
    private lateinit var highZ2: FloatArray

    private var lowB0 = 1f; private var lowB1 = 0f; private var lowB2 = 0f; private var lowA1 = 0f; private var lowA2 = 0f
    private var midB0 = 1f; private var midB1 = 0f; private var midB2 = 0f; private var midA1 = 0f; private var midA2 = 0f
    private var highB0 = 1f; private var highB1 = 0f; private var highB2 = 0f; private var highA1 = 0f; private var highA2 = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        lowZ1 = FloatArray(channelCount); lowZ2 = FloatArray(channelCount)
        midZ1 = FloatArray(channelCount); midZ2 = FloatArray(channelCount)
        highZ1 = FloatArray(channelCount); highZ2 = FloatArray(channelCount)
        recomputeCoefficients()
        return inputAudioFormat
    }

    // No isActive() override, on purpose. Media3 reads isActive() only inside
    // configure()/flush(), and DefaultAudioSink flushes only on a seek, an
    // AudioTrack re-init, or a speed/pitch change - so a processor that
    // reports itself inactive at 0 dB stays OUT of the chain when the DJ
    // moves an EQ knob mid-play, until something unrelated flushes it. This
    // class is active for as long as it is configured, always consumes its
    // input, and does "flat" by copying the input through untouched.

    private fun isIdle(): Boolean =
        abs(targetLowDb) < DEAD_ZONE_DB && abs(liveLowDb) < DEAD_ZONE_DB &&
            abs(targetMidDb) < DEAD_ZONE_DB && abs(liveMidDb) < DEAD_ZONE_DB &&
            abs(targetHighDb) < DEAD_ZONE_DB && abs(liveHighDb) < DEAD_ZONE_DB

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        // Same empty-buffer guard LevelAudioProcessor needed - caught once
        // on real hardware, applied everywhere a passthrough-style
        // processor touches replaceOutputBuffer().
        if (remaining == 0 || channelCount == 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        liveLowDb += (targetLowDb - liveLowDb) * SMOOTHING
        liveMidDb += (targetMidDb - liveMidDb) * SMOOTHING
        liveHighDb += (targetHighDb - liveHighDb) * SMOOTHING
        if (abs(liveLowDb - targetLowDb) < 0.01f) liveLowDb = targetLowDb
        if (abs(liveMidDb - targetMidDb) < 0.01f) liveMidDb = targetMidDb
        if (abs(liveHighDb - targetHighDb) < 0.01f) liveHighDb = targetHighDb

        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        if (isIdle()) {
            // Flat: bit-exact copy. A flat biquad chain is the identity, whose
            // DF2T state is zero, so keeping the state at rest here means the
            // first non-flat buffer starts from exactly the right state.
            clearState()
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }
        recomputeCoefficients()

        while (inputBuffer.hasRemaining()) {
            for (ch in 0 until channelCount) {
                val sampleShort = inputBuffer.short
                var x = sampleShort / 32768f

                var y = lowB0 * x + lowZ1[ch]
                lowZ1[ch] = lowB1 * x - lowA1 * y + lowZ2[ch]
                lowZ2[ch] = lowB2 * x - lowA2 * y
                x = y

                y = midB0 * x + midZ1[ch]
                midZ1[ch] = midB1 * x - midA1 * y + midZ2[ch]
                midZ2[ch] = midB2 * x - midA2 * y
                x = y

                y = highB0 * x + highZ1[ch]
                highZ1[ch] = highB1 * x - highA1 * y + highZ2[ch]
                highZ2[ch] = highB2 * x - highA2 * y
                x = y

                // Symmetric with the /32768 on the way in.
                outputBuffer.putShort(Math.round(x * 32768f).coerceIn(-32768, 32767).toShort())
            }
        }
        sanitizeState()
        outputBuffer.flip()
    }

    private fun recomputeCoefficients() {
        // Keep every corner safely under Nyquist for low-rate sources.
        val maxHz = MAX_CORNER_FRACTION_OF_FS * sampleRateHz
        computeLowShelf(minOf(LOW_FREQ_HZ, maxHz), liveLowDb)
        computePeaking(minOf(MID_FREQ_HZ, maxHz), MID_Q, liveMidDb)
        computeHighShelf(minOf(HIGH_FREQ_HZ, maxHz), liveHighDb)
    }

    /** RBJ Audio EQ Cookbook shelf alpha for slope S=1 (the cookbook's own
     * "as steep as it can be without overshoot" setting):
     *   alpha = sin(w0)/2 * sqrt((A + 1/A) * (1/S - 1) + 2)
     * and at S=1 the (1/S - 1) term is ZERO, leaving sin(w0)/2 * sqrt(2).
     * This used to multiply that term by 1 instead of 0, which is S = 0.5:
     * a shelf that reached only -22.7 dB at 60 Hz for a -26 dB kill (correct:
     * -25.7) while leaking -3.5 dB into 1 kHz. */
    private fun shelfAlpha(sinw0: Float): Float = sinw0 / 2f * sqrt(2f)

    /** Non-finite or denormal-sized state -> rest. */
    private fun sanitizeState() {
        sanitize(lowZ1); sanitize(lowZ2)
        sanitize(midZ1); sanitize(midZ2)
        sanitize(highZ1); sanitize(highZ2)
    }

    private fun sanitize(arr: FloatArray) {
        for (i in arr.indices) {
            val v = arr[i]
            if (!(abs(v) < STATE_LIMIT) || abs(v) < DENORMAL_FLOOR) arr[i] = 0f
        }
    }

    private fun clearState() {
        if (::lowZ1.isInitialized) {
            lowZ1.fill(0f); lowZ2.fill(0f)
            midZ1.fill(0f); midZ2.fill(0f)
            highZ1.fill(0f); highZ2.fill(0f)
        }
    }

    /** RBJ Audio EQ Cookbook - low shelf, shelf slope S=1. */
    private fun computeLowShelf(freqHz: Float, gainDb: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * freqHz / sampleRateHz
        val cosw0 = cos(w0); val sinw0 = sin(w0)
        val alpha = shelfAlpha(sinw0)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha

        val b0 = a * ((a + 1f) - (a - 1f) * cosw0 + twoSqrtAAlpha)
        val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosw0)
        val b2 = a * ((a + 1f) - (a - 1f) * cosw0 - twoSqrtAAlpha)
        val a0 = (a + 1f) + (a - 1f) * cosw0 + twoSqrtAAlpha
        val a1 = -2f * ((a - 1f) + (a + 1f) * cosw0)
        val a2 = (a + 1f) + (a - 1f) * cosw0 - twoSqrtAAlpha

        lowB0 = b0 / a0; lowB1 = b1 / a0; lowB2 = b2 / a0
        lowA1 = a1 / a0; lowA2 = a2 / a0
    }

    /** RBJ Audio EQ Cookbook - high shelf, shelf slope S=1. */
    private fun computeHighShelf(freqHz: Float, gainDb: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * freqHz / sampleRateHz
        val cosw0 = cos(w0); val sinw0 = sin(w0)
        val alpha = shelfAlpha(sinw0)
        val twoSqrtAAlpha = 2f * sqrt(a) * alpha

        val b0 = a * ((a + 1f) + (a - 1f) * cosw0 + twoSqrtAAlpha)
        val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosw0)
        val b2 = a * ((a + 1f) + (a - 1f) * cosw0 - twoSqrtAAlpha)
        val a0 = (a + 1f) - (a - 1f) * cosw0 + twoSqrtAAlpha
        val a1 = 2f * ((a - 1f) - (a + 1f) * cosw0)
        val a2 = (a + 1f) - (a - 1f) * cosw0 - twoSqrtAAlpha

        highB0 = b0 / a0; highB1 = b1 / a0; highB2 = b2 / a0
        highA1 = a1 / a0; highA2 = a2 / a0
    }

    /** RBJ Audio EQ Cookbook - peaking/bell EQ. */
    private fun computePeaking(freqHz: Float, q: Float, gainDb: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * freqHz / sampleRateHz
        val cosw0 = cos(w0); val sinw0 = sin(w0)
        val alpha = sinw0 / (2f * q)

        val b0 = 1f + alpha * a
        val b1 = -2f * cosw0
        val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a
        val a1 = -2f * cosw0
        val a2 = 1f - alpha / a

        midB0 = b0 / a0; midB1 = b1 / a0; midB2 = b2 / a0
        midA1 = a1 / a0; midA2 = a2 / a0
    }

    // Deliberately NO onFlush override: Media3 flushes on every seek, AudioTrack
    // re-init and speed change, and zeroing the biquad state there puts a
    // step into the output on every tempo nudge.

    override fun onReset() {
        // ExoPlayer resets the sink when the audio renderer is disabled or
        // reset (stop(), release, some stream changes). A plain same-format
        // track reload only flushes it - measured on a real device - so this
        // is defensive rather than a fix for a reported drop. Only audio-
        // thread state goes: the EQ targets are UI state ("Keep FX across
        // track load"), and the deck UI keeps showing them, so a reset must
        // not silently zero what the audio is doing.
        clearState()
    }

    companion object {
        const val MIN_DB = -26f
        const val MAX_DB = 6f
        private const val LOW_FREQ_HZ = 250f
        private const val MID_FREQ_HZ = 1000f
        private const val MID_Q = 0.7f
        private const val HIGH_FREQ_HZ = 4000f
        private const val DEAD_ZONE_DB = 0.1f
        private const val SMOOTHING = 0.35f
        private const val MAX_CORNER_FRACTION_OF_FS = 0.45f
        private const val STATE_LIMIT = 1e6f
        private const val DENORMAL_FLOOR = 1e-15f
    }
}
