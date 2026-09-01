package com.oblivion.djayclone

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Activity-scoped, same lifecycle shape as DeckViewModel/RecordingViewModel -
 * survives the Mixer<->Library screen swap so re-entering Library doesn't
 * lose search/sort state or force a re-query on every single navigation.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TrackLibraryRepository(application)
    private val cache = TrackCacheRepository.get(application)

    private val _allTracks = MutableStateFlow<List<LibraryTrack>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    private val _permissionDenied = MutableStateFlow(false)
    private val _query = MutableStateFlow("")
    private val _sort = MutableStateFlow(LibrarySortOrder.TITLE)

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val permissionDenied: StateFlow<Boolean> = _permissionDenied.asStateFlow()
    val query: StateFlow<String> = _query.asStateFlow()
    val sort: StateFlow<LibrarySortOrder> = _sort.asStateFlow()

    /** Query-filtered (150ms debounced, client-side substring match on
     * title/artist/key - not a MediaStore requery per keystroke, viable at
     * this app's realistic personal-library scale) view of whatever
     * refresh() last loaded, already sorted by the repository.
     *
     * Key is matched too (not just title/artist): the whole point of Key
     * Detection is harmonic mixing, and typing a Camelot code (e.g. "8B")
     * to pull up every compatible track is a real DJ workflow, not a
     * hypothetical one - exact match only (equalsIgnoreCase, not
     * contains()), since "contains" would make searching "1A" also match
     * "1A" *and* every other single/double-digit code containing "1" as a
     * substring of a longer number (e.g. "11A", "12A"), which isn't what a
     * DJ typing a specific Camelot code means. */
    @OptIn(FlowPreview::class)
    val visibleTracks: StateFlow<List<LibraryTrack>> = combine(
        _allTracks,
        _query.debounce(150),
    ) { tracks, q ->
        if (q.isBlank()) {
            tracks
        } else {
            tracks.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.artist?.contains(q, ignoreCase = true) == true ||
                    it.camelotKey?.equals(q.trim(), ignoreCase = true) == true
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Re-runs the MediaStore query + manual-import accessibility
     * reconciliation. Call on every LIBRARY screen entry, not just once -
     * MediaStore content can change externally (another app adds/removes a
     * file) and a manual row's SAF grant can be externally revoked, neither
     * of which this app gets a push notification for. */
    fun refresh() {
        _isLoading.value = true
        viewModelScope.launch {
            val tracks = repo.loadLibrary(_sort.value)
            _allTracks.value = tracks
            _isLoading.value = false
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setSort(sort: LibrarySortOrder) {
        _sort.value = sort
        refresh()
    }

    fun setPermissionDenied(denied: Boolean) {
        _permissionDenied.value = denied
    }

    /** Library's own bulk "Add from files" picker result - registers every
     * picked uri into the manual-import cache, then refreshes so the new
     * rows appear immediately. Does NOT touch either deck. */
    fun onManualImportPicked(uris: List<Uri>) {
        uris.forEach { repo.registerManualImport(it) }
        refresh()
    }

    fun removeManualTrack(track: LibraryTrack) {
        if (!track.isManual) return
        repo.removeManualImport(track.uri)
        refresh()
    }

    fun markPlayed(track: LibraryTrack) {
        cache.setLastPlayed(track.id, System.currentTimeMillis())
    }
}
