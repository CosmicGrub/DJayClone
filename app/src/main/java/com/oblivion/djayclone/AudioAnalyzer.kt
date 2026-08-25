package com.oblivion.djayclone

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class AnalysisResult(
    val waveform: FloatArray,   // fixed-size amplitude buckets, 0..1, for drawing
    val bpm: Float?,            // null if detection failed
    val spectral: SpectralWaveform,   // per-bucket bass/mid/treble split, for spectral-colored drawing
)

/**
 * Per-bucket relative frequency-band energy, aligned 1:1 with
 * [AnalysisResult.waveform]'s buckets (same [WAVEFORM_BUCKETS] length). Each
 * bucket's three values are normalized to sum to ~1 - a relative-dominance
 * split ready to weight-blend three anchor colors directly, not an absolute
 * loudness measure.
 */
data class SpectralWaveform(
    val bass: FloatArray,
    val mid: FloatArray,
    val treble: FloatArray,
)

/**
 * Decodes an arbitrary audio file (whatever container/codec MediaExtractor +
 * the platform's MediaCodec support - which covers the same broad codec set
 * Media3 plays back) into mono PCM, then derives:
 *  - a downsampled waveform for drawing
 *  - a BPM estimate via onset-energy autocorrelation
 *
 * This is intentionally dependency-free (no FFmpeg/aubio) so it works with
 * whatever the OS decoder already handles; the FFmpeg extension mentioned in
 * Stage 1 only widens *playback* codec coverage, this analyzer rides on top
 * of the same platform decoders Media3 uses.
 */
object AudioAnalyzer {

    private const val WAVEFORM_BUCKETS = 300
    private const val ANALYSIS_TIMEOUT_US = 10_000L

    suspend fun analyze(context: Context, uri: Uri): AnalysisResult? = withContext(Dispatchers.Default) {
        try {
            decodeToMonoPcm(context, uri)?.let { (pcm, sampleRate) ->
                val waveform = buildWaveform(pcm)
                val bpm = estimateBpm(pcm, sampleRate)
                val spectral = buildSpectralWaveform(pcm, sampleRate)
                AnalysisResult(waveform, bpm, spectral)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Returns (mono PCM as normalized floats -1..1, sample rate) or null on failure. */
    private fun decodeToMonoPcm(context: Context, uri: Uri): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex == -1 || format == null) {
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

        // Cap analysis to the first ~3 minutes of audio - plenty for waveform
        // shape and BPM estimation, keeps decode time bounded for long files.
        val maxSamplesPerChannel = sampleRate * 180
        val pcmOut = ArrayList<Float>(min(maxSamplesPerChannel, 1_000_000))

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(ANALYSIS_TIMEOUT_US)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, ANALYSIS_TIMEOUT_US)
            if (outIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)!!
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val shortBuf = outputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frameCount = shortBuf.remaining() / channelCount
                    for (f in 0 until frameCount) {
                        var sum = 0
                        for (c in 0 until channelCount) sum += shortBuf.get(f * channelCount + c)
                        val monoSample = (sum / channelCount) / 32768f
                        pcmOut.add(monoSample)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                }
            }

            if (pcmOut.size >= maxSamplesPerChannel) outputDone = true
        }

        codec.stop()
        codec.release()
        extractor.release()

        if (pcmOut.isEmpty()) return null
        return pcmOut.toFloatArray() to sampleRate
    }

    private fun buildWaveform(pcm: FloatArray): FloatArray {
        val buckets = FloatArray(WAVEFORM_BUCKETS)
        val perBucket = max(1, pcm.size / WAVEFORM_BUCKETS)
        for (b in 0 until WAVEFORM_BUCKETS) {
            val start = b * perBucket
            val end = min(pcm.size, start + perBucket)
            if (start >= end) continue
            var peak = 0f
            for (i in start until end) peak = max(peak, abs(pcm[i]))
            buckets[b] = peak.coerceIn(0f, 1f)
        }
        return buckets
    }

    private const val SPECTRAL_FFT_SIZE = 1024 // power of 2; ~23ms window @44.1kHz
    private const val SPECTRAL_BASS_MAX_HZ = 250f
    private const val SPECTRAL_TREBLE_MIN_HZ = 4000f

    /**
     * Per-bucket bass/mid/treble split, aligned 1:1 with [buildWaveform]'s
     * buckets - the spectral-coloring counterpart to that amplitude pass,
     * reusing the exact same decoded `pcm` (no second decode).
     *
     * One [SPECTRAL_FFT_SIZE]-point FFT per bucket, taken from a window
     * centered in that bucket, not the whole bucket - each bucket already
     * spans a genre-typical ~0.3-0.6s of a 3-minute track, and spectral
     * balance doesn't usually shift meaningfully faster than that, so a
     * single representative window is enough. 300 buckets x one 1024-point
     * FFT each is cheap - well under the cost of the BPM autocorrelation
     * pass this same function already runs alongside.
     */
    private fun buildSpectralWaveform(pcm: FloatArray, sampleRateHz: Int): SpectralWaveform {
        val bass = FloatArray(WAVEFORM_BUCKETS)
        val mid = FloatArray(WAVEFORM_BUCKETS)
        val treble = FloatArray(WAVEFORM_BUCKETS)
        val perBucket = max(1, pcm.size / WAVEFORM_BUCKETS)

        val re = FloatArray(SPECTRAL_FFT_SIZE)
        val im = FloatArray(SPECTRAL_FFT_SIZE)

        for (b in 0 until WAVEFORM_BUCKETS) {
            val bucketStart = b * perBucket
            val bucketEnd = min(pcm.size, bucketStart + perBucket)
            if (bucketStart >= bucketEnd) {
                mid[b] = 1f // no data - neutral mid-weighted rather than (0,0,0), which blends to black
                continue
            }

            val windowStart = ((bucketStart + bucketEnd) / 2 - SPECTRAL_FFT_SIZE / 2)
                .coerceIn(0, max(0, pcm.size - SPECTRAL_FFT_SIZE))

            for (i in 0 until SPECTRAL_FFT_SIZE) {
                val sampleIdx = windowStart + i
                val sample = if (sampleIdx < pcm.size) pcm[sampleIdx] else 0f
                // Hann window - standard practice before an FFT meant to read
                // band energy, not just find peaks: without it, the window's
                // own hard edges leak energy across bins and smear the split.
                val hann = 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (SPECTRAL_FFT_SIZE - 1)).toFloat())
                re[i] = sample * hann
                im[i] = 0f
            }

            Fft.transform(re, im)

            var bassEnergy = 0f
            var midEnergy = 0f
            var trebleEnergy = 0f
            // Bin 0 is DC; only the first half is meaningful for a real
            // (non-complex) input signal - the second half mirrors it.
            for (k in 1 until SPECTRAL_FFT_SIZE / 2) {
                val freqHz = k.toFloat() * sampleRateHz / SPECTRAL_FFT_SIZE
                val magnitude = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k])
                when {
                    freqHz < SPECTRAL_BASS_MAX_HZ -> bassEnergy += magnitude
                    freqHz < SPECTRAL_TREBLE_MIN_HZ -> midEnergy += magnitude
                    else -> trebleEnergy += magnitude
                }
            }

            val total = bassEnergy + midEnergy + trebleEnergy
            if (total > 1e-6f) {
                bass[b] = bassEnergy / total
                mid[b] = midEnergy / total
                treble[b] = trebleEnergy / total
            } else {
                mid[b] = 1f // near-silence - neutral rather than black
            }
        }

        return SpectralWaveform(bass, mid, treble)
    }

    /**
     * Energy-onset autocorrelation BPM estimate: compute short-time energy,
     * take the positive-diff onset envelope, autocorrelate it over a lag
     * range wide enough to span the full genre spectrum - slow ballads and
     * downtempo/ambient (~55 BPM) through hardcore/drum & bass (~190 BPM) -
     * then refine the winning lag with parabolic interpolation across its
     * neighbors so the result isn't quantized to whole-BPM steps.
     */
    private fun estimateBpm(pcm: FloatArray, sampleRate: Int): Float? {
        val hop = 512
        val windowSize = 1024
        if (pcm.size < windowSize * 4) return null

        val frameCount = (pcm.size - windowSize) / hop
        if (frameCount < 8) return null

        val energy = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            val start = i * hop
            var sum = 0f
            for (j in 0 until windowSize) {
                val s = pcm[start + j]
                sum += s * s
            }
            energy[i] = sum / windowSize
        }

        // Positive-difference onset envelope.
        val onset = FloatArray(frameCount)
        for (i in 1 until frameCount) {
            onset[i] = max(0f, energy[i] - energy[i - 1])
        }

        val framesPerSecond = sampleRate.toFloat() / hop
        // Wide net across genres: downtempo/ambient/ballad up through
        // hardcore/D&B. Octave-folding below picks the musically sane match.
        val minBpm = 55f
        val maxBpm = 200f
        val minLag = (framesPerSecond * 60f / maxBpm).toInt().coerceAtLeast(1)
        val maxLag = (framesPerSecond * 60f / minBpm).toInt().coerceAtMost(frameCount - 2)
        if (minLag >= maxLag) return null

        fun scoreAt(lag: Int): Float {
            var score = 0f
            var n = 0
            for (i in lag until frameCount) {
                score += onset[i] * onset[i - lag]
                n++
            }
            return if (n > 0) score / n else 0f
        }

        var bestLag = -1
        var bestScore = -1f
        for (lag in minLag..maxLag) {
            val score = scoreAt(lag)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return null

        // Parabolic interpolation around the best lag for sub-integer BPM
        // precision ("ultrafine" tuning) instead of snapping to whole lags.
        val refinedLag = if (bestLag > minLag && bestLag < maxLag) {
            val sMinus = scoreAt(bestLag - 1)
            val sCenter = bestScore
            val sPlus = scoreAt(bestLag + 1)
            val denom = sMinus - 2f * sCenter + sPlus
            if (abs(denom) > 1e-9f) {
                val offset = 0.5f * (sMinus - sPlus) / denom
                bestLag + offset.coerceIn(-1f, 1f)
            } else bestLag.toFloat()
        } else bestLag.toFloat()

        val bpm = 60f * framesPerSecond / refinedLag
        // Fold into a sane DJ range (some tracks autocorrelate at half/double
        // tempo - e.g. a 174 BPM D&B track can autocorrelate at 87).
        var folded = bpm
        while (folded < 70f) folded *= 2f
        while (folded > 190f) folded /= 2f
        return folded
    }
}
