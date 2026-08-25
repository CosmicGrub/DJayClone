package com.oblivion.djayclone

import android.app.Application
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Best-effort HTML-entity decode for MediaStore title/artist tags - see
 * the call site comment in queryMediaStore() for why this is needed. */
private fun decodeHtmlEntities(s: String): String =
    Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()

/**
 * Identifies a track by whatever its source can actually offer: a real
 * MediaStore row id, or (for a manually-SAF-imported file MediaStore never
 * indexed) the URI string itself - there's no integer id to key off there.
 */
sealed class TrackId {
    data class MediaStoreId(val id: Long) : TrackId()
    data class ManualUri(val uriString: String) : TrackId()

    /** Stable string form for use as a SharedPreferences key suffix
     * (TrackCacheRepository) and as a LazyColumn item key - distinct
     * prefixes so a MediaStore id and a manual URI's hash can never
     * collide with each other. */
    fun cacheKey(): String = when (this) {
        is MediaStoreId -> "m$id"
        is ManualUri -> "u${uriString.hashCode()}"
    }

    companion object {
        /** MediaStore audio content URIs always have authority "media";
         * anything else (a SAF document URI) is treated as manual. */
        fun from(uri: Uri): TrackId =
            if (uri.authority == MediaStore.AUTHORITY) {
                MediaStoreId(ContentUris.parseId(uri))
            } else {
                ManualUri(uri.toString())
            }
    }
}

enum class LibrarySortOrder { TITLE, ARTIST, DATE_ADDED, DURATION, BPM }
enum class Deck { A, B }

data class LibraryTrack(
    val id: TrackId,
    val uri: Uri,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val dateAddedMs: Long,
    val bpm: Float?,             // from TrackCacheRepository, not a MediaStore column
    val isManual: Boolean,       // true = SAF-imported supplement, false = MediaStore row
    val isAccessible: Boolean = true, // only ever false for a manual row w/ a revoked SAF grant
)

/**
 * Read path only - never writes to MediaStore itself. MediaStore already IS
 * the persistent, indexed, queryable store for on-device audio; this class
 * just projects it into LibraryTrack and merges in the small manual/SAF-
 * imported supplement (content MediaStore genuinely can't see: other
 * document providers, unindexed removable storage). No Room - see the
 * Stage 9 design spec: mirroring MediaStore into a second local database
 * would just invite two copies of "what tracks exist" to drift apart, for
 * no benefit.
 *
 * Not a singleton (unlike TrackCacheRepository/SettingsRepository) - it
 * holds no state of its own beyond a Context reference, so constructing it
 * wherever it's needed is cheap and safe; the actual persisted state it
 * reads/writes through lives in TrackCacheRepository's own singleton.
 */
class TrackLibraryRepository(private val app: Application) {

    private val cache = TrackCacheRepository.get(app)

    suspend fun loadLibrary(sort: LibrarySortOrder = LibrarySortOrder.TITLE): List<LibraryTrack> =
        withContext(Dispatchers.IO) {
            val mediaStoreTracks = queryMediaStore()
            val manualTracks = loadManualTracks()
            (mediaStoreTracks + manualTracks).sortedWith(comparatorFor(sort))
        }

    /** Registers a SAF-picked uri into the manual-import cache so it shows
     * up as a normal library row from now on. Idempotent. Called from
     * MainActivity's existing per-deck import launcher (fire-and-forget,
     * unchanged deck-load behavior) and from the Library screen's own
     * "Add from files" bulk picker. */
    fun registerManualImport(uri: Uri) {
        cache.addManualUri(uri.toString())
    }

    fun removeManualImport(uri: Uri) {
        cache.removeManualUri(uri.toString())
    }

    private fun queryMediaStore(): List<LibraryTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val tracks = mutableListOf<LibraryTrack>()
        try {
            app.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, null
            )?.use { cursor: Cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val trackId = TrackId.MediaStoreId(id)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val displayName = cursor.getString(nameIdx) ?: "Unknown"
                    // Real on-device libraries confirmed this is needed: some
                    // poorly-tagged files (commonly from web-scraping rippers)
                    // carry literal HTML-escaped text in their ID3 TITLE/
                    // ARTIST tags (e.g. "&quot;New..." instead of a real
                    // quote character) - decode defensively. A no-op for
                    // normal tags, which never contain literal entities.
                    val title = cursor.getString(titleIdx)?.takeIf { it.isNotBlank() }
                        ?.let(::decodeHtmlEntities) ?: displayName
                    val artist = cursor.getString(artistIdx)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?.let(::decodeHtmlEntities)
                    tracks.add(
                        LibraryTrack(
                            id = trackId,
                            uri = uri,
                            title = title,
                            artist = artist,
                            durationMs = cursor.getLong(durationIdx),
                            dateAddedMs = cursor.getLong(dateIdx) * 1000L, // DATE_ADDED is seconds, not ms
                            bpm = cache.getCachedBpm(trackId),
                            isManual = false,
                            isAccessible = true,
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission not granted - LibraryViewModel/MainActivity own the
            // empty-state/rationale UI via their own permission check; this
            // just degrades to "no MediaStore tracks" rather than crashing.
        }
        return tracks
    }

    private fun loadManualTracks(): List<LibraryTrack> {
        val granted = try {
            app.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }
        return cache.getManualUris().map { uriString ->
            val uri = Uri.parse(uriString)
            val trackId = TrackId.ManualUri(uriString)
            val accessible = uriString in granted
            val displayName =
                (if (accessible) queryDisplayName(uri) else null) ?: uri.lastPathSegment ?: "Unknown"
            LibraryTrack(
                id = trackId,
                uri = uri,
                title = displayName,
                artist = null,
                durationMs = 0L,
                dateAddedMs = cache.getManualAddedAt(uriString) ?: 0L,
                bpm = cache.getCachedBpm(trackId),
                isManual = true,
                isAccessible = accessible,
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun comparatorFor(sort: LibrarySortOrder): Comparator<LibraryTrack> = when (sort) {
        LibrarySortOrder.TITLE -> compareBy { it.title.lowercase() }
        LibrarySortOrder.ARTIST -> compareBy(nullsLast<String>()) { it.artist?.lowercase() } // unknown artist sorts last
        LibrarySortOrder.DATE_ADDED -> compareByDescending { it.dateAddedMs }
        LibrarySortOrder.DURATION -> compareBy { it.durationMs }
        // Null BPM (not yet analyzed) sorts LAST as a group, never treated as 0.
        LibrarySortOrder.BPM -> compareBy { it.bpm ?: Float.MAX_VALUE }
    }
}
