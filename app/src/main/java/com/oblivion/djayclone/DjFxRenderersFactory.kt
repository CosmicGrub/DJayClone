package com.oblivion.djayclone

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Inserts this deck's FILTER and ECHO processors into ExoPlayer's audio
 * pipeline. Overriding only [buildAudioSink] - not touching codec/extractor
 * selection or any other renderer - means video/audio codec coverage,
 * SeekParameters.EXACT (a seek-resolution concept, unrelated to sink
 * construction), and the loop-boundary watcher's plain player.seekTo()
 * calls are all unaffected by this class existing at all.
 */
class DjFxRenderersFactory(
    context: Context,
    private val filterProcessor: AudioProcessor,
    private val echoProcessor: AudioProcessor,
    // VU meter: a pure observer, ordered first so it reads audio before
    // Filter/Echo touch it - the meter should read the deck's real source
    // level, not a level already shaped by this deck's own FX.
    private val levelProcessor: AudioProcessor,
    // 3-band EQ - real mixer channel-strip order: trim (EQ) before the
    // creative sweep (Filter), same as a Pioneer/Rane-style strip wires it.
    private val eqProcessor: AudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(levelProcessor, eqProcessor, filterProcessor, echoProcessor))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
}
