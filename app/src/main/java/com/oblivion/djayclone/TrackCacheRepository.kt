package com.oblivion.djayclone

import android.app.Application
import android.content.Context

/**
 * SharedPreferences-backed cache for the few things about a track that
 * MediaStore doesn't know and this app computes/tracks itself: analyzed
 * BPM, analyzed key (Camelot notation, from KeyDetector), Auto Gain's
 * suggested level (from AudioAnalyzer.estimateAutoGain), last-played
 * timestamp, the manual/SAF-imported supplement URI set (plus each manual
 * entry's added-at timestamp), and a thumbnail file-path fallback for
 * tracks ContentResolver.loadThumbnail() can't serve (API 26-28 MediaStore
 * rows, and every manual/SAF row at any API level, since loadThumbnail only
 * accepts MediaStore-backed content:// uris).
 *
 * Own SharedPreferences file ("djayclone_track_cache"), deliberately
 * separate from SettingsRepository's "djayclone_settings" file so a future
 * change to one can never accidentally wipe the other. Same companion-
 * object double-checked-locking singleton pattern as SettingsRepository.
 *
 * Flat per-track keys (not one big JSON blob) so caching one track's
 * freshly-analyzed BPM only rewrites that one key via apply(), not a full
 * re-serialize of every other cached track on every write - and avoids
 * pulling in a JSON dependency (kotlinx.serialization isn't a Gradle
 * dependency of this project) just for a feature this shape doesn't need.
 * At this app's realistic personal-library scale (hundreds of tracks), a
 * few hundred small SharedPreferences keys loaded once at startup is cheap
 * - well short of the multi-thousand-key regime where per-apply() whole-
 * file rewrites would start to hurt.
 */
class TrackCacheRepository private constructor(app: Application) {

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCachedBpm(id: TrackId): Float? {
        val key = KEY_BPM_PREFIX + id.cacheKey()
        return if (prefs.contains(key)) prefs.getFloat(key, 0f) else null
    }

    fun setCachedBpm(id: TrackId, bpm: Float) {
        prefs.edit().putFloat(KEY_BPM_PREFIX + id.cacheKey(), bpm).apply()
    }

    /** Camelot notation ("8B" etc), from KeyDetector - same cache-once-
     * never-recompute treatment as BPM. */
    fun getCachedKey(id: TrackId): String? =
        prefs.getString(KEY_CAMELOT_PREFIX + id.cacheKey(), null)

    fun setCachedKey(id: TrackId, camelotKey: String) {
        prefs.edit().putString(KEY_CAMELOT_PREFIX + id.cacheKey(), camelotKey).apply()
    }

    /** Auto Gain's suggested GAIN slider value (0f..1f), from
     * AudioAnalyzer.estimateAutoGain - same cache-once-recomputed-on-every-
     * load-anyway treatment as BPM/key (the cache backs the library list;
     * the deck itself re-runs the whole analysis pass on every load
     * regardless, this just rides along for free). */
    fun getCachedAutoGain(id: TrackId): Float? {
        val key = KEY_AUTO_GAIN_PREFIX + id.cacheKey()
        return if (prefs.contains(key)) prefs.getFloat(key, 1f) else null
    }

    fun setCachedAutoGain(id: TrackId, gain: Float) {
        prefs.edit().putFloat(KEY_AUTO_GAIN_PREFIX + id.cacheKey(), gain).apply()
    }

    fun getLastPlayed(id: TrackId): Long? {
        val key = KEY_LAST_PLAYED_PREFIX + id.cacheKey()
        return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    }

    fun setLastPlayed(id: TrackId, whenMs: Long) {
        prefs.edit().putLong(KEY_LAST_PLAYED_PREFIX + id.cacheKey(), whenMs).apply()
    }

    /** API 26-28 MediaStore rows + every manual/SAF row at any API level -
     * see the class doc comment for why this split isn't purely by API
     * level. Stores a path into context.cacheDir, never the bitmap bytes
     * themselves, so it's automatically reclaimed under storage pressure
     * like any other cache-dir content. */
    fun getCachedThumbPath(id: TrackId): String? =
        prefs.getString(KEY_THUMB_PREFIX + id.cacheKey(), null)

    fun setCachedThumbPath(id: TrackId, path: String) {
        prefs.edit().putString(KEY_THUMB_PREFIX + id.cacheKey(), path).apply()
    }

    fun getManualUris(): Set<String> =
        prefs.getStringSet(KEY_MANUAL_URIS, emptySet())?.toSet() ?: emptySet()

    fun addManualUri(uriString: String) {
        val current = getManualUris()
        if (uriString in current) return
        prefs.edit()
            .putStringSet(KEY_MANUAL_URIS, current + uriString)
            .putLong(KEY_MANUAL_ADDED_PREFIX + uriString.hashCode(), System.currentTimeMillis())
            .apply()
    }

    fun removeManualUri(uriString: String) {
        val current = getManualUris()
        if (uriString !in current) return
        prefs.edit()
            .putStringSet(KEY_MANUAL_URIS, current - uriString)
            .remove(KEY_MANUAL_ADDED_PREFIX + uriString.hashCode())
            .apply()
    }

    fun getManualAddedAt(uriString: String): Long? {
        val key = KEY_MANUAL_ADDED_PREFIX + uriString.hashCode()
        return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    }

    companion object {
        private const val PREFS_NAME = "djayclone_track_cache"
        private const val KEY_BPM_PREFIX = "bpm_"
        private const val KEY_CAMELOT_PREFIX = "camelot_"
        private const val KEY_AUTO_GAIN_PREFIX = "auto_gain_"
        private const val KEY_LAST_PLAYED_PREFIX = "last_played_"
        private const val KEY_THUMB_PREFIX = "thumb_"
        private const val KEY_MANUAL_URIS = "manual_uris"
        private const val KEY_MANUAL_ADDED_PREFIX = "manual_added_"

        @Volatile private var INSTANCE: TrackCacheRepository? = null
        fun get(app: Application): TrackCacheRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TrackCacheRepository(app).also { INSTANCE = it }
            }
    }
}
