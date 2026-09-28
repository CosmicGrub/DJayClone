package com.oblivion.djayclone

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thrown when the sink-style drive loop makes no progress: input is still
 * pending, the pipeline produced no output, and calling queueInput() again
 * consumed nothing. The real DefaultAudioSink.processBuffers() has no such
 * guard - in production this condition is an infinite loop on the playback
 * thread - so the rig turns it into a test failure instead of a hang.
 */
class SinkStallException(message: String) : RuntimeException(message)

/**
 * Drives real processors through Media3's real [AudioProcessingPipeline] the
 * way DefaultAudioSink does: configure(), then flush() (which is the ONLY
 * place besides configure() where the pipeline re-reads each processor's
 * isActive()), then the processBuffers() loop of getOutput()/queueInput().
 *
 * Plain JVM, no device: the point is to make audio-path claims falsifiable
 * with numbers instead of asserting them from reading the code.
 */
class PipelineRig(
    processors: List<AudioProcessor>,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
) {
    val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(processors))

    init {
        pipeline.configure(AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        pipeline.flush()
    }

    /** What ExoPlayer does on a seek, an AudioTrack re-init, or a
     * PlaybackParameters (speed/pitch) change. */
    fun flush() = pipeline.flush()

    /** What DefaultAudioSink does for a speed/pitch (PlaybackParameters)
     * change: drainToEndOfStream() - queueEndOfStream() on the pipeline, then
     * read everything out until it reports ended - and THEN the pipeline
     * flush. The audio in the processors at that moment was written to the
     * live AudioTrack and will be heard. A seek, by contrast, is a plain
     * [flush] with no drain: the sink drops whatever it had queued ahead of
     * the playhead. Returns what came out during the drain. */
    fun drainThenFlush(): ShortArray {
        val out = ArrayList<Short>()
        pipeline.queueEndOfStream()
        var guard = 0
        while (!pipeline.isEnded) {
            val o = pipeline.output
            o.order(ByteOrder.LITTLE_ENDIAN)
            while (o.hasRemaining()) out.add(o.short)
            if (++guard > 1000) throw SinkStallException("pipeline never reported ended after queueEndOfStream()")
        }
        pipeline.flush()
        return out.toShortArray()
    }

    /** What DefaultAudioSink.reset() followed by the next configure does when
     * ExoPlayer disables/resets the audio renderer (stop(), release, some
     * stream changes). A same-format track reload does NOT do this - it only
     * flushes (verified on a real device) - so use [flush] to model that. */
    fun resetAndReconfigure() {
        pipeline.reset()
        pipeline.configure(AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        pipeline.flush()
    }

    /** Interleaved PCM16 in, interleaved PCM16 out (same length unless a
     * processor changes the rate, e.g. Sonic). */
    fun process(input: ShortArray, blockFrames: Int = 1024, maxIdleIterations: Int = 200): ShortArray {
        val out = ArrayList<Short>(input.size)
        val blockSamples = blockFrames * channels
        var pos = 0
        while (pos < input.size) {
            val n = min(blockSamples, input.size - pos)
            val buf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) buf.putShort(input[pos + i])
            buf.flip()
            drive(buf, out, maxIdleIterations)
            pos += n
        }
        return out.toShortArray()
    }

    /** Copy of DefaultAudioSink.processBuffers() (Media3 1.4.1) with an
     * idle-iteration cap standing in for "spins forever". */
    private fun drive(input: ByteBuffer, out: MutableList<Short>, maxIdle: Int) {
        if (!pipeline.isOperational) {
            input.order(ByteOrder.LITTLE_ENDIAN)
            while (input.hasRemaining()) out.add(input.short)
            return
        }
        var idle = 0
        while (!pipeline.isEnded) {
            var produced = false
            while (true) {
                val o = pipeline.output
                if (!o.hasRemaining()) break
                produced = true
                o.order(ByteOrder.LITTLE_ENDIAN)
                while (o.hasRemaining()) out.add(o.short)
            }
            if (!input.hasRemaining()) return
            val before = input.remaining()
            pipeline.queueInput(input)
            if (input.remaining() == before && !produced) {
                if (++idle > maxIdle) {
                    throw SinkStallException(
                        "pipeline made no progress for $maxIdle iterations with ${input.remaining()} bytes pending " +
                            "(a processor returned from queueInput() without consuming input)"
                    )
                }
            } else {
                idle = 0
            }
        }
    }
}

/** Test-signal generation and measurement helpers. */
object Sig {

    /** Interleaved PCM16, the same tone mix on every channel, phase-continuous
     * across calls when [startFrame] advances (so a signal can be fed in
     * pieces around a knob move without a phase seam). tones = (Hz, peak 0..1). */
    fun tones(
        fs: Int, channels: Int, startFrame: Int, frames: Int, vararg tones: Pair<Double, Double>,
    ): ShortArray {
        val out = ShortArray(frames * channels)
        for (i in 0 until frames) {
            val t = (startFrame + i).toDouble() / fs
            var v = 0.0
            for ((f, a) in tones) v += a * sin(2.0 * PI * f * t)
            val s = Math.round(v * 32768.0).coerceIn(-32768L, 32767L).toShort()
            for (ch in 0 until channels) out[i * channels + ch] = s
        }
        return out
    }

    /** Deterministic full-scale noise that also hits both PCM16 extremes. */
    fun fullScaleNoise(frames: Int, channels: Int, seed: Long = 12345L): ShortArray {
        var x = seed
        val out = ShortArray(frames * channels)
        for (i in out.indices) {
            x = x * 6364136223846793005L + 1442695040888963407L
            out[i] = (x ushr 48).toInt().toShort()
        }
        out[0] = Short.MIN_VALUE
        if (out.size > 1) out[1] = Short.MAX_VALUE
        return out
    }

    /** Peak amplitude of the [freq] component in dB re full scale, Hann-
     * windowed single-bin DFT over frames [from, to) of one channel. */
    fun toneDb(
        pcm: ShortArray, channels: Int, ch: Int, freq: Double, fs: Int, from: Int, to: Int,
    ): Double {
        var re = 0.0
        var im = 0.0
        var wsum = 0.0
        val n = to - from
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            val x = pcm[(from + i) * channels + ch] / 32768.0 * w
            val ph = 2.0 * PI * freq * (from + i) / fs
            re += x * cos(ph)
            im -= x * sin(ph)
            wsum += w
        }
        val amp = 2.0 * sqrt(re * re + im * im) / wsum
        return 20.0 * log10(amp.coerceAtLeast(1e-12))
    }

    fun rmsDb(pcm: ShortArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) {
            val x = pcm[i] / 32768.0
            s += x * x
        }
        return 20.0 * log10(sqrt(s / (to - from)).coerceAtLeast(1e-12))
    }
}
