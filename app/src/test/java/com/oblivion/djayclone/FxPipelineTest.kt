package com.oblivion.djayclone

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pipeline-level tests: the app's real Level/Eq/Filter/Echo processors driven
 * through Media3's real AudioProcessingPipeline (see [PipelineRig]) the way
 * DefaultAudioSink drives them.
 *
 * These exist because the defects they pin are invisible to a processor-in-
 * isolation test AND to a listening session: Media3 only re-reads
 * AudioProcessor.isActive() inside configure()/flush(), and DefaultAudioSink
 * only flushes on a seek, an AudioTrack re-init, or a speed/pitch change. A
 * processor whose isActive() follows its own live knob therefore stays out of
 * the chain until something unrelated happens to flush it.
 */
class FxPipelineTest {

    private val fs = 44100

    private fun chain(
        level: LevelAudioProcessor = LevelAudioProcessor(),
        eq: EqAudioProcessor = EqAudioProcessor(),
        filter: FilterAudioProcessor = FilterAudioProcessor(),
        echo: EchoAudioProcessor = EchoAudioProcessor(),
    ): List<AudioProcessor> = listOf(level, eq, filter, echo)

    // ------------------------------------------------------------------
    // The activation contract
    // ------------------------------------------------------------------

    @Test
    fun filterLowpassEngagesWithoutAnyFlush() {
        val filter = FilterAudioProcessor()
        val rig = PipelineRig(chain(filter = filter), fs, 2)
        val tones = arrayOf(200.0 to 0.25, 8000.0 to 0.25)

        val pre = rig.process(Sig.tones(fs, 2, 0, fs, *tones))
        filter.setKnob(-0.9f) // the DJ moves the knob mid-play; nothing else happens
        val post = rig.process(Sig.tones(fs, 2, fs, fs, *tones))

        val before = Sig.toneDb(pre, 2, 0, 8000.0, fs, fs / 2, fs)
        val after = Sig.toneDb(post, 2, 0, 8000.0, fs, fs / 2, fs)
        assertTrue(
            "8 kHz must fall >= 20 dB once the LPF knob is at -0.9, with no seek/cue/nudge to flush the " +
                "chain; measured change = ${"%.1f".format(after - before)} dB",
            before - after >= 20.0
        )
        val lowBefore = Sig.toneDb(pre, 2, 0, 200.0, fs, fs / 2, fs)
        val lowAfter = Sig.toneDb(post, 2, 0, 200.0, fs, fs / 2, fs)
        assertTrue("the 200 Hz tone must stay within ~3 dB (cutoff is ~244 Hz)", abs(lowAfter - lowBefore) < 3.5)
    }

    @Test
    fun filterHighpassEngagesWithoutAnyFlush() {
        val filter = FilterAudioProcessor()
        val rig = PipelineRig(chain(filter = filter), fs, 2)
        val tones = arrayOf(200.0 to 0.25, 8000.0 to 0.25)

        val pre = rig.process(Sig.tones(fs, 2, 0, fs, *tones))
        filter.setKnob(0.9f)
        val post = rig.process(Sig.tones(fs, 2, fs, fs, *tones))

        val drop = Sig.toneDb(pre, 2, 0, 200.0, fs, fs / 2, fs) - Sig.toneDb(post, 2, 0, 200.0, fs, fs / 2, fs)
        assertTrue("200 Hz must fall >= 20 dB under the HPF at +0.9; drop = ${"%.1f".format(drop)} dB", drop >= 20.0)
    }

    @Test
    fun eqKillEngagesWithoutAnyFlush() {
        val eq = EqAudioProcessor()
        val rig = PipelineRig(chain(eq = eq), fs, 2)
        val tones = arrayOf(60.0 to 0.25, 1000.0 to 0.25)

        val pre = rig.process(Sig.tones(fs, 2, 0, fs, *tones))
        eq.setLowDb(-26f)
        val post = rig.process(Sig.tones(fs, 2, fs, fs, *tones))

        val drop = Sig.toneDb(pre, 2, 0, 60.0, fs, fs / 2, fs) - Sig.toneDb(post, 2, 0, 60.0, fs, fs / 2, fs)
        assertTrue("LOW kill must take 60 Hz down >= 20 dB mid-play; drop = ${"%.1f".format(drop)} dB", drop >= 20.0)
        val midChange = Sig.toneDb(post, 2, 0, 1000.0, fs, fs / 2, fs) - Sig.toneDb(pre, 2, 0, 1000.0, fs, fs / 2, fs)
        assertTrue("1 kHz must be left alone by a LOW kill (within 1.5 dB); changed ${"%.2f".format(midChange)} dB", abs(midChange) < 1.5)
    }

    @Test
    fun echoEngagesWhenToggledOnMidPlay() {
        val echo = EchoAudioProcessor()
        echo.targetDelaySeconds = 0.25f
        val rig = PipelineRig(chain(echo = echo), fs, 1)

        rig.process(ShortArray(fs / 5)) // 0.2 s of silence with echo still off
        echo.enabled = true
        rig.process(ShortArray(fs / 20)) // 50 ms: the 5 ms engage ramps are complete
        val burst = ShortArray(fs)
        burst[0] = 16384 // an impulse at 0.5 FS
        val out = rig.process(burst)

        val at = (0.25 * fs).toInt()
        val echoPeak = (at - 3..at + 3).maxOf { abs(out[it].toInt()) }
        assertTrue(
            "an echo tap must appear 250 ms after the impulse (expected ~0.5*0.35*32768 = 5700); peak = $echoPeak",
            echoPeak > 2500
        )
    }

    @Test
    fun echoTurnedOffAfterBeingOnNeverStallsThePlaybackThread() {
        val echo = EchoAudioProcessor()
        echo.enabled = true // active when the chain is configured/flushed
        echo.targetDelaySeconds = 0.25f
        val rig = PipelineRig(chain(echo = echo), fs, 2)
        val tones = arrayOf(440.0 to 0.25)

        rig.process(Sig.tones(fs, 2, 0, fs / 2, *tones))
        echo.enabled = false // DJ taps ECHO off; no flush follows
        val out = try {
            rig.process(Sig.tones(fs, 2, fs / 2, fs / 2, *tones))
        } catch (e: SinkStallException) {
            throw AssertionError("ECHO off wedged the sink loop: ${e.message}")
        }
        assertEquals("every input frame must come out", fs / 2 * 2, out.size)
    }

    // ------------------------------------------------------------------
    // Neutrality
    // ------------------------------------------------------------------

    @Test
    fun anIdleChainIsBitExact() {
        for (channels in intArrayOf(1, 2)) {
            val input = Sig.fullScaleNoise(fs, channels)
            val out = PipelineRig(chain(), fs, channels).process(input, blockFrames = 777)
            assertTrue("idle Level/EQ/Filter/Echo must not change a single sample ($channels ch)", input.contentEquals(out))
        }
    }

    @Test
    fun filterAndEqReturnToBitExactNeutralOnceCentered() {
        val filter = FilterAudioProcessor()
        val eq = EqAudioProcessor()
        val rig = PipelineRig(chain(eq = eq, filter = filter), fs, 2)
        filter.setKnob(-0.8f)
        eq.setLowDb(-20f); eq.setHighDb(4f)
        rig.process(Sig.fullScaleNoise(fs / 2, 2, seed = 1))
        filter.setKnob(0f)
        eq.setLowDb(0f); eq.setHighDb(0f)
        rig.process(Sig.fullScaleNoise(fs, 2, seed = 2)) // let both slew home
        val probe = Sig.fullScaleNoise(fs / 2, 2, seed = 3)
        val out = rig.process(probe)
        assertTrue("centered FILTER and flat EQ must be bit-exact again after being used", probe.contentEquals(out))
    }

    // ------------------------------------------------------------------
    // Echo engage / tail / idle
    // ------------------------------------------------------------------

    @Test
    fun echoRingsOutAfterBeingTurnedOffThenGoesBitExactIdle() {
        val echo = EchoAudioProcessor()
        echo.targetDelaySeconds = 0.1f
        val rig = PipelineRig(chain(echo = echo), fs, 1)
        echo.enabled = true
        val lead = ShortArray(fs / 2)
        lead[fs / 2 - 2000] = 20000 // impulse ~45 ms before ECHO is turned off
        rig.process(lead)

        echo.enabled = false
        val after = ShortArray(3 * fs)
        after[fs] = 20000 // a NEW hit, 1 s after disabling: must pass through, un-echoed
        val out = rig.process(after)

        // SILENT input, so nothing but the tail can produce this: the first
        // repeat of the pre-disable impulse is due 4410 - 2000 = 2410 frames in.
        val repeat = (2410 - 3..2410 + 3).maxOf { abs(out[it].toInt()) }
        assertTrue("the tail must still ring right after ECHO off, not be chopped (first repeat peak=$repeat)", repeat > 3000)
        assertEquals("the later hit passes through untouched", 20000, out[fs].toInt())
        assertEquals("...and is not echoed", 0, (fs + 4410 - 5..fs + 4410 + 5).maxOf { abs(out[it].toInt()) })
        val strays = out.indices.filter { it > fs / 2 && it != fs }.count { out[it].toInt() != 0 }
        assertEquals("once the tail is over, echo is an exact pass-through of silence", 0, strays)
    }

    @Test
    fun aHitAfterEchoIsOffIsNotEchoed() {
        val echo = EchoAudioProcessor()
        echo.targetDelaySeconds = 0.1f
        val rig = PipelineRig(chain(echo = echo), fs, 1)
        echo.enabled = true
        rig.process(ShortArray(fs / 4))
        echo.enabled = false
        rig.process(ShortArray(fs)) // let the (empty) tail finish
        val hit = ShortArray(fs)
        hit[500] = 20000
        val out = rig.process(hit)
        val at = 500 + 4410
        assertEquals("no echo tap for a hit made while echo is off", 0, (at - 5..at + 5).maxOf { abs(out[it].toInt()) })
        assertEquals("the hit itself passes untouched", 20000, out[500].toInt())
    }

    // ------------------------------------------------------------------
    // Flush semantics: seek vs tempo change
    // ------------------------------------------------------------------

    /** The sink runs the processors up to the AudioTrack buffer (250-750 ms)
     * AHEAD of the playhead. A seek (loop wrap, hot cue, scrub) flushes the
     * sink and throws that queued audio away unheard - so anything an echo
     * kept from it would replay audio the listener never heard. */
    @Test
    fun aSeekFlushDoesNotReplayUnheardAudioThroughTheEcho() {
        val echo = EchoAudioProcessor()
        echo.enabled = true
        echo.targetDelaySeconds = 0.25f
        val rig = PipelineRig(chain(echo = echo), fs, 1)
        rig.process(Sig.tones(fs, 1, 0, fs, 400.0 to 0.5)) // loud audio the echo has swallowed
        rig.flush()                                        // seek: no drain first
        val out = rig.process(ShortArray(fs / 2))          // silence after the jump
        val loudest = out.maxOf { abs(it.toInt()) }
        assertEquals("nothing may come back out of the echo after a seek into silence", 0, loudest)
    }

    /** A speed/pitch change drains the pipeline into the still-playing
     * AudioTrack before flushing, so the echo's contents WILL be heard and a
     * nudge must not chop a ringing tail. */
    @Test
    fun aTempoChangeFlushKeepsTheEchoTailRinging() {
        val echo = EchoAudioProcessor()
        echo.enabled = true
        echo.targetDelaySeconds = 0.25f
        val rig = PipelineRig(chain(echo = echo), fs, 1)
        val lead = ShortArray(fs / 2)
        lead[fs / 2 - 4410] = 20000 // impulse 100 ms before the "nudge"
        rig.process(lead)
        rig.drainThenFlush()
        val out = rig.process(ShortArray(fs / 2))
        val at = 0.15 * fs // 250 ms tap minus the 100 ms already elapsed
        val peak = (at.toInt() - 4..at.toInt() + 4).maxOf { abs(out[it].toInt()) }
        assertTrue("the tap due 150 ms after a tempo nudge must still sound (peak=$peak)", peak > 2500)
    }

    /** The processed-but-unplayed audio a seek discards can include the tail
     * of the PREVIOUS track: clearBuffer() only sets a flag that the next
     * buffer services, so old-track audio processed after the request and
     * before the sink flush lands in the ring. */
    @Test
    fun aTrackChangeCannotLeakTheOldTrackThroughTheEcho() {
        val echo = EchoAudioProcessor()
        echo.enabled = true
        echo.targetDelaySeconds = 0.1f
        val rig = PipelineRig(chain(echo = echo), fs, 1)
        rig.process(Sig.tones(fs, 1, 0, fs / 2, 300.0 to 0.4))
        echo.clearBuffer()                                              // loadTrack() asks for a clear...
        rig.process(Sig.tones(fs, 1, fs / 2, fs / 20, 300.0 to 0.4))    // ...old-track audio is still in flight...
        rig.flush()                                                      // ...then the sink flushes for the new item
        val out = rig.process(ShortArray(fs / 2))
        assertEquals("no trace of the old track after the new one starts", 0, out.maxOf { abs(it.toInt()) })
    }

    // ------------------------------------------------------------------
    // Click guards: engaging or disengaging must not put a step in the signal
    // ------------------------------------------------------------------

    private fun maxStep(x: ShortArray, from: Int = 0, to: Int = x.size): Int {
        var m = 0
        for (i in from + 1 until to) m = maxOf(m, abs(x[i] - x[i - 1]))
        return m
    }

    @Test
    fun engagingAndDisengagingEchoDoesNotClick() {
        // 0.12 s is exactly 36 cycles of 300 Hz: the repeat is IN phase, the
        // worst case for a level step. Toggle at 8 different phases of the wave.
        val period = fs / 300
        var worst = 0.0
        var where = -1
        for (k in 0 until 8) {
            val echo = EchoAudioProcessor()
            echo.targetDelaySeconds = 0.12f
            val rig = PipelineRig(chain(echo = echo), fs, 1)
            val sig = Sig.tones(fs, 1, 0, 3 * fs / 2, 300.0 to 0.3)
            val onAt = fs / 2 + k * period / 8
            val offAt = fs + k * period / 8
            val a = rig.process(sig.copyOfRange(0, onAt))
            echo.enabled = true
            val b = rig.process(sig.copyOfRange(onAt, offAt))
            echo.enabled = false
            val c = rig.process(sig.copyOfRange(offAt, sig.size))
            val ratio = maxStep(a + b + c).toDouble() / maxStep(sig)
            if (ratio > worst) { worst = ratio; where = k }
        }
        assertTrue("echo on/off stepped the signal to ${"%.2f".format(worst)}x its own max step (toggle phase $where/8)", worst <= 1.5)
    }

    @Test
    fun engagingFilterFromCentreDoesNotClick() {
        for (knob in floatArrayOf(-0.5f, 0.5f)) {
            val filter = FilterAudioProcessor()
            val rig = PipelineRig(chain(filter = filter), fs, 1)
            val sig = Sig.tones(fs, 1, 0, fs, 1000.0 to 0.3)
            val a = rig.process(sig.copyOfRange(0, fs / 2))
            filter.setKnob(knob)
            val b = rig.process(sig.copyOfRange(fs / 2, sig.size))
            val steady = maxStep(sig)
            val worst = maxStep(a + b)
            assertTrue("FILTER $knob put a step of $worst in a signal whose own max step is $steady", worst <= steady * 2)
        }
    }

    /**
     * A quick flick of the FILTER knob straight through centre: the knob is
     * slewed once per buffer, and at this speed one buffer's move is wider than
     * the dead zone, so the sign flips while the filter is still audibly in.
     * The LP<->HP tap swap used to happen right there, at a buffer boundary:
     * an instant jump between two unrelated signals.
     *
     * Two deliberate limits, so this pins the tap swap and nothing else:
     *  - the flick stays within +/-0.3 and the tone (1 kHz) is far from every
     *    cutoff it visits, and
     *  - it is NOT an instantaneous full-range jump. The knob is slewed once
     *    per BUFFER, so a single huge move steps the cutoff by octaves in one
     *    go (measured ~3.4x the signal's own max step at 1 kHz). That is a
     *    separate, pre-existing limitation (per-buffer rather than per-sample
     *    coefficient smoothing), tracked as its own improvement.
     * A gentle drag would NOT do: it slews through the dead zone, where the
     * filter is already fully dry, and passes even with the bug (found by
     * mutation testing).
     */
    @Test
    fun flickingTheFilterAcrossCentreDoesNotClick() {
        val block = 1024
        val steps = 8
        for ((from, to) in listOf(-0.3f to 0.3f, 0.3f to -0.3f)) {
            val filter = FilterAudioProcessor()
            filter.setKnob(from)
            val rig = PipelineRig(chain(filter = filter), fs, 1)
            val total = fs / 2 + steps * block + fs / 2
            val sig = Sig.tones(fs, 1, 0, total, 1000.0 to 0.3)
            val out = ArrayList<Short>()
            out += rig.process(sig.copyOfRange(0, fs / 2), blockFrames = block).toList()
            var pos = fs / 2
            for (i in 1..steps) {
                filter.setKnob(from + (to - from) * i / steps)
                out += rig.process(sig.copyOfRange(pos, pos + block), blockFrames = block).toList()
                pos += block
            }
            out += rig.process(sig.copyOfRange(pos, total), blockFrames = block).toList()
            val ratio = maxStep(out.toShortArray()).toDouble() / maxStep(sig)
            assertTrue("flicking FILTER $from -> $to stepped the signal to ${"%.2f".format(ratio)}x its own max step", ratio <= 2.0)
        }
    }

    // ------------------------------------------------------------------
    // Coverage the reviewers asked for: channels, blocks, other EQ bands
    // ------------------------------------------------------------------

    @Test
    fun leftAndRightAreProcessedIndependently() {
        fun stereo(start: Int, frames: Int): ShortArray {
            val left = Sig.tones(fs, 1, start, frames, 200.0 to 0.3)
            val right = Sig.tones(fs, 1, start, frames, 8000.0 to 0.3)
            return ShortArray(frames * 2) { i -> if (i % 2 == 0) left[i / 2] else right[i / 2] }
        }
        val filter = FilterAudioProcessor()
        val rig = PipelineRig(chain(filter = filter), fs, 2)
        val pre = rig.process(stereo(0, fs))
        filter.setKnob(-0.9f)
        val post = rig.process(stereo(fs, fs))
        val rightDrop = Sig.toneDb(pre, 2, 1, 8000.0, fs, fs / 2, fs) - Sig.toneDb(post, 2, 1, 8000.0, fs, fs / 2, fs)
        val leftChange = Sig.toneDb(post, 2, 0, 200.0, fs, fs / 2, fs) - Sig.toneDb(pre, 2, 0, 200.0, fs, fs / 2, fs)
        assertTrue("the RIGHT channel's 8 kHz must fall >= 20 dB; fell ${"%.1f".format(rightDrop)}", rightDrop >= 20.0)
        assertTrue("the LEFT channel's 200 Hz must stay within ~3.5 dB; changed ${"%.1f".format(leftChange)}", abs(leftChange) < 3.5)
        val leak = Sig.toneDb(post, 2, 0, 8000.0, fs, fs / 2, fs)
        assertTrue("no 8 kHz may appear in the left channel (${"%.0f".format(leak)} dB)", leak < -90.0)
    }

    @Test
    fun theFilterEngagesWithAnyBufferSize() {
        for (block in intArrayOf(97, 512, 4096)) {
            val filter = FilterAudioProcessor()
            val rig = PipelineRig(chain(filter = filter), fs, 1)
            val tones = arrayOf(200.0 to 0.25, 8000.0 to 0.25)
            val pre = rig.process(Sig.tones(fs, 1, 0, fs, *tones), blockFrames = block)
            filter.setKnob(-0.9f)
            val post = rig.process(Sig.tones(fs, 1, fs, fs, *tones), blockFrames = block)
            val drop = Sig.toneDb(pre, 1, 0, 8000.0, fs, fs / 2, fs) - Sig.toneDb(post, 1, 0, 8000.0, fs, fs / 2, fs)
            assertTrue("8 kHz must fall >= 20 dB with $block-frame buffers; fell ${"%.1f".format(drop)}", drop >= 20.0)
        }
    }

    @Test
    fun eqMidKillEngagesWithoutAnyFlush() {
        val eq = EqAudioProcessor()
        val rig = PipelineRig(chain(eq = eq), fs, 1)
        val tones = arrayOf(1000.0 to 0.25, 8000.0 to 0.25)
        val pre = rig.process(Sig.tones(fs, 1, 0, fs, *tones))
        eq.setMidDb(-26f)
        val post = rig.process(Sig.tones(fs, 1, fs, fs, *tones))
        val drop = Sig.toneDb(pre, 1, 0, 1000.0, fs, fs / 2, fs) - Sig.toneDb(post, 1, 0, 1000.0, fs, fs / 2, fs)
        assertTrue("MID kill must take 1 kHz down >= 20 dB mid-play; dropped ${"%.1f".format(drop)}", drop >= 20.0)
    }

    // ------------------------------------------------------------------
    // EQ shape
    // ------------------------------------------------------------------

    /** Independent RBJ cookbook low-shelf magnitude, slope S = 1, in double. */
    private fun rbjLowShelfDb(gainDb: Double, f0: Double, f: Double): Double {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2 * PI * f0 / fs
        val alpha = sin(w0) / 2 * sqrt(2.0) // S = 1  =>  (1/S - 1) = 0  =>  sqrt(2)
        val sa = 2 * sqrt(a) * alpha
        val b0 = a * ((a + 1) - (a - 1) * cos(w0) + sa)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cos(w0))
        val b2 = a * ((a + 1) - (a - 1) * cos(w0) - sa)
        val a0 = (a + 1) + (a - 1) * cos(w0) + sa
        val a1 = -2 * ((a - 1) + (a + 1) * cos(w0))
        val a2 = (a + 1) + (a - 1) * cos(w0) - sa
        val w = 2 * PI * f / fs
        fun mag(c0: Double, c1: Double, c2: Double): Double {
            val re = c0 + c1 * cos(w) + c2 * cos(2 * w)
            val im = -(c1 * sin(w) + c2 * sin(2 * w))
            return sqrt(re * re + im * im)
        }
        return 20 * log10(mag(b0, b1, b2) / mag(a0, a1, a2))
    }

    @Test
    fun lowKillMatchesTheCookbookShelfAtSlopeOne() {
        for (f in doubleArrayOf(60.0, 100.0, 250.0, 1000.0)) {
            val eq = EqAudioProcessor()
            eq.setLowDb(-26f) // set before configure so it is in the chain even under the old contract
            val rig = PipelineRig(listOf(eq), fs, 1)
            val sig = Sig.tones(fs, 1, 0, fs, f to 0.1)
            val out = rig.process(sig)
            val measured = Sig.toneDb(out, 1, 0, f, fs, fs / 2, fs) - Sig.toneDb(sig, 1, 0, f, fs, fs / 2, fs)
            val expected = rbjLowShelfDb(-26.0, 250.0, f)
            assertEquals("LOW -26 dB at $f Hz vs RBJ S=1", expected, measured, 0.3)
        }
    }

    // ------------------------------------------------------------------
    // Filter safety
    // ------------------------------------------------------------------

    @Test
    fun filterStaysStableAtLowerSampleRates() {
        for (rate in intArrayOf(11025, 22050, 24000, 32000)) {
            val filter = FilterAudioProcessor()
            filter.setKnob(-0.05f) // near-open LPF: cutoff ~15.6 kHz, ABOVE Nyquist at these rates
            val rig = PipelineRig(listOf(filter), rate, 1)
            val sig = Sig.tones(rate, 1, 0, rate / 2, 1000.0 to 0.25)
            val out = rig.process(sig)
            val inDb = Sig.rmsDb(sig, rate / 4, rate / 2)
            val outDb = Sig.rmsDb(out, rate / 4, rate / 2)
            assertTrue(
                "1 kHz tone through a near-open LPF at $rate Hz must survive (in ${"%.1f".format(inDb)} dB, out ${"%.1f".format(outDb)} dB)",
                abs(outDb - inDb) < 3.0
            )
        }
    }

    // ------------------------------------------------------------------
    // State continuity
    // ------------------------------------------------------------------

    @Test
    fun filterStateSurvivesAFlushWithoutASeam() {
        val filter = FilterAudioProcessor()
        filter.setKnob(-0.5f)
        val rig = PipelineRig(listOf(filter), fs, 1)
        val a = Sig.tones(fs, 1, 0, fs, 1000.0 to 0.5)
        val outA = rig.process(a)
        rig.flush() // e.g. a tempo change: PlaybackParameters flush
        val b = Sig.tones(fs, 1, fs, fs / 4, 1000.0 to 0.5) // phase-continuous continuation
        val outB = rig.process(b)

        fun maxStep(x: ShortArray, from: Int, to: Int): Int {
            var m = 0
            for (i in from + 1 until to) m = maxOf(m, abs(x[i] - x[i - 1]))
            return m
        }
        val steady = maxStep(outA, fs / 2, fs)
        val seam = maxOf(abs(outB[0] - outA.last()).toInt(), maxStep(outB, 0, 400))
        assertTrue(
            "flush() must not restart the filter from zero state: sample step at the seam = $seam vs steady-state $steady",
            seam <= steady * 1.5 + 2
        )
    }

    @Test
    fun aResetKeepsTheUsersKnobPositions() {
        // ExoPlayer resets the audio sink when the audio renderer is disabled
        // or reset (stop(), release, some stream changes) - NOT on a plain
        // same-format track reload, which only flushes it (checked on a real
        // tablet). The deck UI keeps showing the FILTER/EQ position across
        // such a reset, so the audio must keep it too.
        val filter = FilterAudioProcessor()
        val eq = EqAudioProcessor()
        filter.setKnob(-0.9f)
        eq.setLowDb(-26f)
        val rig = PipelineRig(chain(eq = eq, filter = filter), fs, 1)
        rig.resetAndReconfigure()
        val tones = arrayOf(60.0 to 0.2, 8000.0 to 0.2)
        val out = rig.process(Sig.tones(fs, 1, 0, fs, *tones))
        val hi = Sig.toneDb(out, 1, 0, 8000.0, fs, fs / 2, fs)
        val lo = Sig.toneDb(out, 1, 0, 60.0, fs, fs / 2, fs)
        val ref = 20 * log10(0.2)
        assertTrue("FILTER position lost across a reset: 8 kHz is ${"%.1f".format(hi - ref)} dB from input", ref - hi >= 20.0)
        assertTrue("EQ LOW position lost across a reset: 60 Hz is ${"%.1f".format(lo - ref)} dB from input", ref - lo >= 15.0)
    }

    // ------------------------------------------------------------------
    // Time base: beat-length things live in SOURCE time
    // ------------------------------------------------------------------

    private fun measureEchoWallDelaySamples(speed: Float, delaySeconds: Float): Int {
        val echo = EchoAudioProcessor()
        echo.enabled = true
        echo.targetDelaySeconds = delaySeconds
        val processorChain = DefaultAudioSink.DefaultAudioProcessorChain(
            LevelAudioProcessor(), EqAudioProcessor(), FilterAudioProcessor(), echo
        )
        // Key Lock off (pitch == speed): Sonic is then a plain resampler, so
        // the impulse and its echo stay sharp enough to locate.
        processorChain.applyPlaybackParameters(PlaybackParameters(speed, speed))
        val procs = processorChain.audioProcessors.toList()
        assertTrue(
            "the deck's custom processors must sit BEFORE Sonic - the time-base math depends on it",
            procs.indexOf(echo) < procs.indexOfFirst { it is SonicAudioProcessor }
        )
        val rig = PipelineRig(procs, fs, 1)
        val input = ShortArray(2 * fs)
        input[2000] = 26000
        val out = rig.process(input)
        val p0 = (0 until 8000).maxByOrNull { abs(out[it].toInt()) }!!
        val p1 = (p0 + 6000 until p0 + 20000).maxByOrNull { abs(out[it].toInt()) }!!
        return p1 - p0
    }

    @Test
    fun baseBpmSourceTimeGivesOneEffectiveBeatOutOfTheSpeaker() {
        val baseBpm = 120f
        val speed = 0.8f
        val delay = BeatTimeMath.beatsToSourceSeconds(baseBpm, 0.5f).toFloat() // 250 ms of source
        val measured = measureEchoWallDelaySamples(speed, delay)
        // Half a beat at the EFFECTIVE tempo (120 * 0.8 = 96 BPM) = 312.5 ms.
        val expected = (60.0 / (baseBpm * speed) * 0.5 * fs).toInt()
        assertEquals("echo spacing at 80% speed", expected.toDouble(), measured.toDouble(), 120.0)
    }

    @Test
    fun theOldEffectiveBpmFormulaAppliedTheSpeedTwice() {
        val baseBpm = 120f
        val speed = 0.8f
        val oldDelay = (60.0 / (baseBpm * speed) * 0.5).toFloat() // what setEchoDivision used to compute
        val measured = measureEchoWallDelaySamples(speed, oldDelay)
        val wanted = (60.0 / (baseBpm * speed) * 0.5 * fs).toInt()
        assertTrue(
            "the old formula lands ${"%.0f".format((measured - wanted) * 100.0 / wanted)}% off the beat at 80% speed",
            measured > wanted * 1.15
        )
    }

    @Test
    fun beatMathIsPlainArithmetic() {
        assertEquals(500.0, BeatTimeMath.beatsToSourceMs(120f, 1f), 1e-9)
        assertEquals(250.0, BeatTimeMath.beatsToSourceMs(120f, 0.5f), 1e-9)
        assertEquals(0.25, BeatTimeMath.beatsToSourceSeconds(120f, 0.5f), 1e-9)
        // 0.8f is not exactly 0.8 in binary floating point.
        assertEquals(312.5, BeatTimeMath.sourceToWallMs(250.0, 0.8f), 1e-3)
    }
}
