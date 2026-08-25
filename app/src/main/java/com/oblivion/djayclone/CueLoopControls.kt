package com.oblivion.djayclone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Fixed 8-entry hot cue palette, indexed by pad slot - pad 1 is always this
 * color regardless of what's cued there, matching Serato/Traktor convention.
 * Deliberately not stored in DeckUiState: color is a UI-layer concern, kept
 * out of the (otherwise UI-toolkit-agnostic) ViewModel state. */
val HotCueColors = listOf(
    Color(0xFFE53935), Color(0xFFFF8F00), Color(0xFFFDD835), Color(0xFF43A047),
    Color(0xFF00ACC1), Color(0xFF3949AB), Color(0xFF8E24AA), Color(0xFFD81B60),
)

/**
 * CUE / IN / OUT / LOOP-toggle row.
 *
 * CUE uses an explicit press/release gesture rather than detectTapGestures'
 * tap/double-tap/long-press family, which delays every single tap by the
 * double-tap timeout window waiting to see if a second tap follows -
 * unacceptable latency on a button meant to feel instant.
 *
 * The gesture handler is keyed only on [deck] (a stable identity that never
 * changes across recompositions), not on the reactive isPlaying/cuePointMs
 * state - keying on those would restart (cancel) the gesture the instant
 * previewCueStart() flips isPlaying to true, cutting the stutter-preview
 * short before it even starts. Fresh state is read via rememberUpdatedState
 * instead, so the branch decision at press-time is still current without
 * that self-cancellation hazard.
 *
 * All four controls use a shared ~48dp-ish touch target height (40dp) even
 * though the visible chip is smaller - a review pass caught the original
 * 32dp boxes sitting under Android's touch-target guideline.
 */
@Composable
fun CueLoopRow(
    state: DeckUiState,
    deck: DeckViewModel,
    accent: Color,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    val currentState by rememberUpdatedState(state)
    // Fixed compact widths (44+36+36+44=160dp, no reliance on default
    // TextButton padding) so all four controls are guaranteed to fit a
    // half-width deck column without clipping - the same class of overflow
    // bug the nudge-button row hit in Stage 4, fixed the same way here from
    // the start rather than discovered after the fact.
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        val cueSet = state.cuePointMs != null
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 40.dp)
                .background(
                    if (cueSet) Color(0xFFFFC107).copy(alpha = 0.30f) else Color(0xFF232328),
                    RoundedCornerShape(4.dp)
                )
                // Deck-locked: the pointerInput below is never attached at
                // all (not merely gated inside), same technique BeatLoopChips
                // already uses for its own bpm-gated chips - an unattached
                // gesture detector can't half-consume a touch on its way to
                // whatever the ancestor verticalScroll wants to do with it.
                .then(if (locked) Modifier else Modifier.pointerInput(deck) {
                    awaitEachGesture {
                        awaitFirstDown()
                        when {
                            // "No cue yet" is checked BEFORE isPlaying, not
                            // after: the old order checked isPlaying first,
                            // which meant "playing + no cue yet" always fell
                            // into jumpToCueAndPause() - a no-op, since it
                            // immediately returns with no cue to jump to. That
                            // made "set a cue while playing" unreachable,
                            // contradicting the documented contract (cue can
                            // be set "paused or playing"). This order lets a
                            // cue be set regardless of play state, then only
                            // branches on isPlaying once a cue already exists.
                            currentState.cuePointMs == null -> {
                                // Wait for a confirmed release before
                                // mutating - a touch that turns into a scroll
                                // (ancestor verticalScroll intercepting it)
                                // must not still set a cue point.
                                if (waitForUpOrCancellation() != null) deck.setCueAtPlayhead()
                            }
                            currentState.isPlaying -> {
                                if (waitForUpOrCancellation() != null) deck.jumpToCueAndPause()
                            }
                            else -> {
                                // Press-and-hold preview: must start
                                // immediately on down (that's the entire
                                // point of hold-to-preview) - release or
                                // cancellation always runs the cleanup below,
                                // via finally, so a scroll-cancelled hold
                                // still snaps back instead of leaving the
                                // deck stuck playing the preview forever.
                                deck.previewCueStart()
                                try {
                                    waitForUpOrCancellation()
                                } finally {
                                    deck.previewCueRelease()
                                }
                            }
                        }
                    }
                }),
            contentAlignment = Alignment.Center
        ) {
            Text("CUE", color = Color(0xFFFFC107), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }

        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 40.dp)
                .background(Color(0xFF232328), RoundedCornerShape(4.dp))
                .then(if (locked) Modifier else Modifier.pointerInput(deck) {
                    awaitEachGesture {
                        awaitFirstDown()
                        // Only fire on a confirmed release, not a cancelled
                        // gesture (waitForUpOrCancellation() returns null on
                        // cancellation, e.g. the deck column's verticalScroll
                        // claiming the pointer mid-drag) - previously this
                        // result was discarded and setLoopIn() fired either way.
                        if (waitForUpOrCancellation() != null) deck.setLoopIn()
                    }
                }),
            contentAlignment = Alignment.Center
        ) {
            Text("IN", color = accent, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 40.dp)
                .background(Color(0xFF232328), RoundedCornerShape(4.dp))
                .then(if (locked) Modifier else Modifier.pointerInput(deck) {
                    awaitEachGesture {
                        awaitFirstDown()
                        // Same cancellation check as IN - a spurious
                        // setLoopOut() also force-activates the loop
                        // (loopActive=true), so a scroll-cancelled gesture
                        // here used to silently start an unintended loop.
                        if (waitForUpOrCancellation() != null) deck.setLoopOut()
                    }
                }),
            contentAlignment = Alignment.Center
        ) {
            Text("OUT", color = accent, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }

        val canToggle = state.loopInMs != null && state.loopOutMs != null
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 40.dp)
                .background(
                    if (state.loopActive) accent else Color(0xFF232328),
                    RoundedCornerShape(4.dp)
                )
                // detectTapGestures, not a hand-rolled withTimeoutOrNull race:
                // AwaitPointerEventScope (what awaitEachGesture gives you) is
                // a @RestrictsSuspension scope, so generic coroutine builders
                // like withTimeoutOrNull can't wrap its suspend calls
                // (compiler-enforced) - detectTapGestures already implements
                // correct tap/long-press disambiguation within that
                // restriction, so use it instead of fighting the restriction.
                .then(if (locked) Modifier else Modifier.pointerInput(deck, canToggle) {
                    detectTapGestures(
                        onTap = { if (canToggle) deck.toggleLoop() },
                        onLongPress = { deck.clearLoop() }
                    )
                }),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "LOOP", color = if (state.loopActive) Color.Black else Color.Gray,
                style = MaterialTheme.typography.labelSmall, maxLines = 1
            )
        }
    }
}

/** Beat-multiple quick loops. Horizontally scrollable: 6 chips don't
 * reliably fit a ~150-165dp half-screen deck column at a legible tap size,
 * so this trades horizontal swipe for vertical space rather than wrapping
 * to a second row. Disabled/grayed while no BPM has been detected yet.
 * [extendedLengths] (Settings > FX ranges) adds 1/8 and 16-beat chips for
 * users who want tighter or much longer loops than the default set. */
@Composable
fun BeatLoopChips(
    state: DeckUiState,
    deck: DeckViewModel,
    accent: Color,
    extendedLengths: Boolean = false,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    val enabled = state.bpm != null && !locked
    val beatOptions = remember(extendedLengths) {
        if (extendedLengths) {
            listOf(0.125f to "⅛", 0.25f to "¼", 0.5f to "½", 1f to "1", 2f to "2", 4f to "4", 8f to "8", 16f to "16")
        } else {
            listOf(0.25f to "¼", 0.5f to "½", 1f to "1", 2f to "2", 4f to "4", 8f to "8")
        }
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        beatOptions.forEach { (beats, label) ->
            val active = state.loopBeats == beats
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 28.dp)
                    .background(
                        when {
                            active -> accent
                            enabled -> Color(0xFF232328)
                            else -> Color(0xFF17171A)
                        },
                        RoundedCornerShape(4.dp)
                    )
                    .then(
                        if (enabled) Modifier.pointerInput(deck, beats, active) {
                            awaitEachGesture {
                                awaitFirstDown()
                                // Cancellation check, same fix as IN/OUT above:
                                // a scroll-cancelled touch on a chip must not
                                // still arm or clear a loop.
                                if (waitForUpOrCancellation() != null) {
                                    if (active) deck.clearLoop() else deck.setBeatLoop(beats)
                                }
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) Color.Black else if (enabled) Color.White else Color.DarkGray,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

/** 4x2 grid of hot cue pads. Each pad is wrapped in a larger invisible
 * touch-target box (44dp) around a smaller visual chip (34dp) so density
 * stays high without shrinking the tappable area below a usable size. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HotCueGrid(
    hotCues: List<Long?>,
    deck: DeckViewModel,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        hotCues.chunked(4).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                chunk.forEachIndexed { col, cue ->
                    val index = row * 4 + col
                    // Solid fill, not alpha-over-black: an empty pad blended
                    // via alpha onto the app's pure-black background is
                    // nearly invisible (this is exactly what happened before
                    // this fix - the whole second row of pads effectively
                    // disappeared). A solid dark tone plus a visible border
                    // reads clearly as "empty pad" instead of "nothing here".
                    val filled = cue != null
                    val color = if (filled) HotCueColors[index % HotCueColors.size] else Color(0xFF232328)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .combinedClickable(
                                enabled = !locked,
                                onClick = { deck.hotCueTap(index) },
                                onLongClick = { if (filled) deck.clearHotCue(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(color, RoundedCornerShape(4.dp))
                                .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            // Filled pads show their real saved position
                            // (mm:ss), not just the pad number - the same
                            // convention djay's own hot-cue grid uses, and
                            // more useful in practice: at a glance you see
                            // WHERE each pad jumps to, not just which slot
                            // it occupies. Empty pads still show the plain
                            // slot number so the grid stays readable before
                            // anything's been cued.
                            Text(
                                if (filled) formatHotCueTimestamp(cue!!) else (index + 1).toString(),
                                color = if (filled) Color.White else Color.Gray,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatHotCueTimestamp(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
