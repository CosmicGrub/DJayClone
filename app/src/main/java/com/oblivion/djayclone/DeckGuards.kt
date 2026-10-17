package com.oblivion.djayclone

import androidx.media3.common.PlaybackException

/**
 * Load protection: the classic catastrophic live-set mistake is loading a
 * track onto the deck the audience is hearing. Pure decision logic (JVM
 * tested); MainActivity turns the decision into a toast or a confirm dialog.
 *
 * Every load path (library list, library sidebar, per-deck file picker,
 * "Open with") goes through this; before, only the file-picker BUTTON
 * honoured the deck lock, so a "locked" deck could still be replaced from the
 * Library.
 */
object LoadGuard {
    enum class Decision {
        /** Load straight away. */
        LOAD,
        /** The deck is input-locked: refuse, and say why. */
        REFUSE_LOCKED,
        /** The deck is playing: ask before replacing what is audible. */
        CONFIRM_PLAYING,
    }

    fun decide(deckLocked: Boolean, deckPlaying: Boolean, confirmOnPlaying: Boolean): Decision = when {
        deckLocked -> Decision.REFUSE_LOCKED
        deckPlaying && confirmOnPlaying -> Decision.CONFIRM_PLAYING
        else -> Decision.LOAD
    }
}

/**
 * Plain-language text for a Media3 playback error. The raw
 * `PlaybackException.message` ("Source error", "MediaCodecAudioRenderer error,
 * index=1, format=...") means nothing to someone mid-set; this says what is
 * wrong and what to do. Pure (the error codes are compile-time constants), so
 * it is JVM-tested without a player.
 */
object PlaybackErrorText {

    fun describe(errorCode: Int): String = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Can't find this file - it may have been moved, renamed or deleted."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "No permission to read this file. Add it again from Library > Add files."
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
            "The file ended unexpectedly - it may be truncated or still copying."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "This file is damaged or in a format DJayClone can't read."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This device can't decode this audio format."
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
            "The audio decoder was taken by another app. Try loading the track again."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
            "Audio output failed - another app may be using the audio device."
        else -> "Couldn't play this file (error $errorCode)."
    }
}
