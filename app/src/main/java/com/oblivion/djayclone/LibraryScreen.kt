package com.oblivion.djayclone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Stage 9: browsable track library. MediaStore-primary (auto-discovers
 * on-device audio via the already-declared READ_MEDIA_AUDIO/
 * READ_EXTERNAL_STORAGE permissions, requested at runtime for the first
 * time here - see MainActivity.ensureLibraryPermission()) plus a small
 * manual/SAF-imported supplement for content MediaStore can't see. Full-
 * screen swap (matching Settings' pattern), not a dialog - see
 * MainActivity's AppScreen enum.
 */
@Composable
fun LibraryScreen(
    library: LibraryViewModel,
    onLoadTrack: (LibraryTrack, Deck) -> Unit,
    onAddFromFiles: () -> Unit,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("LIBRARY", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onAddFromFiles) {
                Icon(Icons.Filled.UploadFile, contentDescription = "Add from files", tint = AccentA)
            }
        }
        LibraryListContent(
            library = library,
            onLoadTrack = onLoadTrack,
            onRequestPermission = onRequestPermission,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Stage 10b: the search field, sort row, and track-list body - extracted
 * verbatim out of [LibraryScreen] so [LibrarySidebar] (the Expanded mixer's
 * persistent browse rail) can reuse the exact same list, search, and sort
 * behavior instead of re-implementing a second, drift-prone copy. Only the
 * surrounding chrome differs between the two hosts: LibraryScreen's full-
 * screen back button/title/add-from-files header stays in LibraryScreen;
 * LibrarySidebar supplies its own lighter header with no back button, since
 * the sidebar isn't a navigable destination you enter and leave.
 *
 * The permission-request-on-appear behavior lives HERE, not in either host,
 * because it's identical in both cases (request once per first composition)
 * and this is the one place both hosts actually share.
 */
@Composable
private fun LibraryListContent(
    library: LibraryViewModel,
    onLoadTrack: (LibraryTrack, Deck) -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracks by library.visibleTracks.collectAsState()
    val query by library.query.collectAsState()
    val sort by library.sort.collectAsState()
    val isLoading by library.isLoading.collectAsState()
    val permissionDenied by library.permissionDenied.collectAsState()

    // Runs on this composable's first entry into composition - for
    // LibraryScreen that's exactly on screen-navigation-in (unchanged from
    // before this extraction); for LibrarySidebar it's the Expanded
    // arrangement's own first composition, i.e. once per app-in-Expanded
    // session, not re-fired on every recomposition.
    LaunchedEffect(Unit) { onRequestPermission() }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { library.setQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("Search title, artist, or key (e.g. 8B)", color = Color.DarkGray) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Color.Gray) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AccentA,
                unfocusedBorderColor = Color.DarkGray,
                cursorColor = AccentA,
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LibrarySortOrder.values().forEach { option ->
                val selected = sort == option
                TextButton(
                    onClick = { library.setSort(option) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        sortLabel(option),
                        color = if (selected) AccentA else Color.Gray,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        when {
            permissionDenied && tracks.isEmpty() && !isLoading -> {
                PermissionRationale(onRetry = onRequestPermission)
            }
            isLoading && tracks.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentA)
                }
            }
            tracks.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "No tracks found. Add some from Files." else "No matches for \"$query\".",
                        color = Color.DarkGray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(tracks, key = { it.id.cacheKey() }) { track ->
                        LibraryRow(
                            track = track,
                            onLoadA = { library.markPlayed(track); onLoadTrack(track, Deck.A) },
                            onLoadB = { library.markPlayed(track); onLoadTrack(track, Deck.B) },
                            onRemove = if (track.isManual) ({ library.removeManualTrack(track) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stage 10b: the Expanded mixer's persistent browse rail - "the real answer
 * to the 40% dead space" the Stage 10 pitch identified on the tablet, per
 * its own framing: browsing and queuing the next track without leaving the
 * mixer, not bigger controls for their own sake. Fixed-width by design
 * (280dp) rather than weight-based - unlike the mixer's own deck/center-
 * column panels, which must stay weight-based to adapt between the tablet's
 * ~1.6:1 landscape and the Fold's squarer ~1.2:1 opened screen (see the
 * pitch's risk list), a browse rail's row content doesn't benefit from
 * stretching wider on a bigger screen the way deck controls would - a fixed
 * rail width is the same pattern real master-detail/browser-sidebar UIs use
 * regardless of overall window width.
 */
@Composable
fun LibrarySidebar(
    library: LibraryViewModel,
    onLoadTrack: (LibraryTrack, Deck) -> Unit,
    onAddFromFiles: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(Color(0xFF0E0E12))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "LIBRARY", color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(start = 6.dp)
            )
            IconButton(onClick = onAddFromFiles, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.UploadFile, contentDescription = "Add from files", tint = AccentA, modifier = Modifier.size(18.dp))
            }
        }
        LibraryListContent(
            library = library,
            onLoadTrack = onLoadTrack,
            onRequestPermission = onRequestPermission,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PermissionRationale(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.LibraryMusic, contentDescription = null, tint = Color.DarkGray,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "DJayClone needs permission to browse audio already on your device.",
            color = Color.Gray,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "You can still add individual files below without it.",
            color = Color.DarkGray,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text("GRANT ACCESS", color = AccentA, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LibraryRow(
    track: LibraryTrack,
    onLoadA: () -> Unit,
    onLoadB: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackThumbnail(track = track, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (track.isAccessible) Color.White else Color.DarkGray,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            Text(
                if (!track.isAccessible) "Unavailable — re-add from Files" else (track.artist ?: "Unknown artist"),
                color = if (!track.isAccessible) Color(0xFFFF5A7A) else Color.Gray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(track.durationMs), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Text(
                track.bpm?.let { "%.0f BPM".format(it) } ?: "—",
                color = Color.DarkGray,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.width(8.dp))
        // KEY column - Camelot notation from KeyDetector (see
        // TrackCacheRepository), cached the same way BPM is: computed once
        // when a track is first loaded/analyzed, never proactively scanned
        // across the whole library. "—" for a track never yet loaded, same
        // convention as the BPM column just above.
        Text(
            track.camelotKey ?: "—",
            color = Color.DarkGray,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        DeckLoadButton("A", AccentA, enabled = track.isAccessible, onClick = onLoadA)
        Spacer(Modifier.width(4.dp))
        DeckLoadButton("B", AccentB, enabled = track.isAccessible, onClick = onLoadB)
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close, contentDescription = "Remove", tint = Color.DarkGray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun DeckLoadButton(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(
                if (enabled) color.copy(alpha = 0.15f) else Color.DarkGray.copy(alpha = 0.1f),
                shape = CircleShape
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) color else Color.DarkGray, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TrackThumbnail(track: LibraryTrack, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(track.id) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(track.id) {
        bitmap = ThumbnailCache.load(context, track)
    }

    Box(
        modifier = modifier.background(Color(0xFF232328), shape = RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(20.dp))
        }
    }
}

private fun sortLabel(sort: LibrarySortOrder): String = when (sort) {
    LibrarySortOrder.TITLE -> "TITLE"
    LibrarySortOrder.ARTIST -> "ARTIST"
    LibrarySortOrder.DATE_ADDED -> "RECENT"
    LibrarySortOrder.DURATION -> "LENGTH"
    LibrarySortOrder.BPM -> "BPM"
    LibrarySortOrder.KEY -> "KEY"
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
