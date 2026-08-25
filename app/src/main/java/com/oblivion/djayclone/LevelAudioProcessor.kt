package com.oblivion.djayclone

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Pure passthrough - never mutates samples, just measures them. Computes a
 * per-block RMS level (0..1) and publishes it for a Compose VU meter to
 * poll. Same shape as FilterAudioProcessor/EchoAudioProcessor's per-deck
 * BaseAudioProcessor pattern, but with nothing to configure and nothing to
 * write back to the buffer - the audio that leaves this processor is
 * byte-identical to what entered it, same passthrough contract the earlier
 * PhaseAudioProcessor design used.
 */
class LevelAudioProcessor : BaseAudioProcessor() {

    // Audio thread writes every block; Compose (main thread) reads on a
    // polling timer - same cross-thread contract as every other @Volatile
    // field in this codebase (single writer, single reader, no lock needed
    // for a plain Float read/write).
    @Volatile
    var currentLevel: Float = 0f
        private set

    private var channelCount = 1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat // pure passthrough: output format == input format
    }

    override fun isActive(): Boolean = channelCount > 0 // always active once configured; never gates the signal

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        // Real bug, caught on real hardware: an empty/end-of-stream marker
        // buffer has remaining()==0, and replaceOutputBuffer(0) returns the
        // same shared empty-buffer singleton the framework can also hand in
        // as `inputBuffer` itself - outputBuffer.put(inputBuffer) then
        // throws IllegalArgumentException("The source buffer is this
        // buffer") because they're literally the same object. Guard exactly
        // like FilterAudioProcessor already does for the same reason.
        if (remaining == 0 || channelCount == 0) return

        val frameSizeBytes = 2 * channelCount
        val frameCount = remaining / frameSizeBytes
        if (frameCount > 0) {
            var sumSquares = 0.0
            var readPos = inputBuffer.position()
            repeat(frameCount) {
                // Absolute gets - doesn't move position, so the single bulk
                // put() below still consumes the whole buffer correctly.
                val left = inputBuffer.getShort(readPos).toInt()
                val right = if (channelCount > 1) inputBuffer.getShort(readPos + 2).toInt() else left
                val mono = (left + right) / 2
                val normalized = mono / 32768.0
                sumSquares += normalized * normalized
                readPos += frameSizeBytes
            }
            currentLevel = sqrt(sumSquares / frameCount).toFloat().coerceIn(0f, 1f)
        }
        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.put(inputBuffer) // single relative put: consumes input exactly once
        outputBuffer.flip()
    }

    override fun onFlush() {
        currentLevel = 0f
    }
}
