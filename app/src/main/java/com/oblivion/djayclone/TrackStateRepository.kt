package com.oblivion.djayclone

import android.app.Application
import android.content.Context

/**
 * SharedPreferences-backed store for [SavedTrackState] (cue, hot cues, loop),
 * one small text record per track ([TrackStateCodec] owns the format).
 *
 * Own prefs file, separate from the BPM/key cache, so the "forget everything
 * I saved" action ([clearAll]) can never touch analysis caches or settings.
 * Keyed by [TrackId.cacheKey], the same identity the rest of the library uses;
 * the record carries the track's duration so a re-used id pointing at a
 * different file is detected and ignored on load (see
 * [TrackStateCodec.validated]).
 *
 * Excluded from Auto Backup (see backup_rules / data_extraction_rules): an
 * id-keyed record restored onto another device's MediaStore ids would attach
 * cues to the wrong files.
 */
class TrackStateRepository private constructor(app: Application) {

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(id: TrackId): SavedTrackState? = TrackStateCodec.decode(prefs.getString(KEY_PREFIX + id.cacheKey(), null))

    /** Writes [state], or deletes the record if it holds nothing worth keeping. */
    fun put(id: TrackId, state: SavedTrackState) {
        val key = KEY_PREFIX + id.cacheKey()
        if (state.isEmpty) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, TrackStateCodec.encode(state)).apply()
    }

    fun count(): Int = prefs.all.size

    /** Forget every saved cue/loop for every track. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "djayclone_track_state"
        private const val KEY_PREFIX = "ts_"

        @Volatile private var INSTANCE: TrackStateRepository? = null
        fun get(app: Application): TrackStateRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackStateRepository(app).also { INSTANCE = it }
            }
    }
}
