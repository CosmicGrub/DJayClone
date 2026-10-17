package com.oblivion.djayclone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * Draws the real decoded waveform (from [AudioAnalyzer]) once analysis
 * finishes. Falls back to a deterministic placeholder pattern while a track
 * is analyzing or has no data yet, so the strip is never blank/jarring.
 *
 * Also overlays the cue point and loop region (Stage 5) directly on the
 * waveform - this costs zero extra vertical space in the deck column and is
 * where users look first for cue/loop state on every real DJ app.
 */
// Anchor colors for spectral-band blending - deep blue for bass, green for
// mid, warm orange for treble. A bucket's rendered color is these three
// linearly blended by that bucket's real (bass,mid,treble) energy split
// (see AudioAnalyzer.SpectralWaveform - the three values already sum to
// ~1, so this is a plain weighted average, not a re-normalized one).
private val SpectralBassAnchor = Color(0xFF4361EE)
private val SpectralMidAnchor = Color(0xFF4CD964)
private val SpectralTrebleAnchor = Color(0xFFFF6B4A)

private fun spectralBarColor(spectral: SpectralWaveform, index: Int, playedAlpha: Float): Color {
    val b = spectral.bass[index]
    val m = spectral.mid[index]
    val t = spectral.treble[index]
    return Color(
        red = SpectralBassAnchor.red * b + SpectralMidAnchor.red * m + SpectralTrebleAnchor.red * t,
        green = SpectralBassAnchor.green * b + SpectralMidAnchor.green * m + SpectralTrebleAnchor.green * t,
        blue = SpectralBassAnchor.blue * b + SpectralMidAnchor.blue * m + SpectralTrebleAnchor.blue * t,
        alpha = playedAlpha,
    )
}

@Composable
fun WaveformStrip(
    trackName: String,
    progress: Float,
    accentColor: Color,
    waveform: FloatArray?,
    isAnalyzing: Boolean,
    durationMs: Long,
    cuePointMs: Long?,
    loopInMs: Long?,
    loopOutMs: Long?,
    loopActive: Boolean,
    modifier: Modifier = Modifier,
    // Real per-bucket frequency-band split from AudioAnalyzer, aligned 1:1
    // with `waveform`'s buckets - null until analysis completes (or for a
    // track loaded before this feature shipped and never re-analyzed), in
    // which case bars fall back to the original flat accent/gray coloring
    // below exactly as before. This is additive, never a required input.
    spectral: SpectralWaveform? = null,
    // Stage 10a: Expanded/Medium's deck column has the vertical budget a
    // Compact column doesn't - DeckColumnExpanded passes a taller value here
    // so the waveform reads as the deck's dominant visual element on a big
    // screen instead of just being stretched wider at the same 64dp height.
    // Default is the exact figure Compact has always used, so every existing
    // caller (DeckColumnCompact) renders byte-for-byte unchanged.
    heightDp: Dp = 64.dp,
    // Tap anywhere on the strip, or drag and release, to seek there (the
    // overview-waveform behaviour every DJ app has). Null (the default)
    // keeps the strip purely visual, exactly as before. A drag only moves a
    // preview cursor and seeks ONCE on release: seeking continuously while
    // dragging would rebuild ExoPlayer's AudioTrack on every move.
    onSeekFraction: ((Float) -> Unit)? = null,
    // Deck lock: when locked the gesture detectors are not attached at all
    // (an unattached detector cannot half-consume a touch that an ancestor
    // scroll wants), same technique the cue/loop controls use.
    locked: Boolean = false,
) {
    val seed = trackName.hashCode()
    val seek by rememberUpdatedState(onSeekFraction)
    var scrub by remember { mutableStateOf<Float?>(null) }
    val seekable = onSeekFraction != null && !locked && durationMs > 0
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
            .background(Color(0xFF0E0E12))
            .then(
                if (seekable) Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            seek?.invoke((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(Unit) {
                        try {
                            detectHorizontalDragGestures(
                                onDragStart = { offset -> scrub = (offset.x / size.width).coerceIn(0f, 1f) },
                                onDragEnd = { scrub?.let { seek?.invoke(it) }; scrub = null },
                                onDragCancel = { scrub = null },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                                },
                            )
                        } finally {
                            // The detector never calls onDragCancel when its
                            // coroutine is cancelled (detached mid-drag, lock
                            // engaged): clear the pending cursor ourselves.
                            scrub = null
                        }
                    }
                else Modifier
            )
    ) {
        val barCount = waveform?.size ?: 120
        val barWidth = size.width / barCount
        val midY = size.height / 2f
        for (i in 0 until barCount) {
            val amp = if (waveform != null) {
                waveform[i].coerceIn(0.03f, 1f)
            } else {
                val n = sin((i * 12.9898 + seed) % 6.283) * 43758.5453
                (abs(n - n.toLong()) * 0.85f + 0.1f).toFloat()
            }
            val played = i.toFloat() / barCount <= progress
            val baseColor = when {
                waveform == null && isAnalyzing -> Color.White.copy(alpha = 0.15f)
                // Spectral coloring covers both played and unplayed bars -
                // real frequency content, not just a progress indicator -
                // with played/unplayed distinguished by alpha instead of a
                // completely different color scheme, same role the old
                // accent-vs-gray split played.
                spectral != null && i < spectral.bass.size ->
                    spectralBarColor(spectral, i, playedAlpha = if (played) 1f else 0.55f)
                played -> accentColor
                else -> Color.White.copy(alpha = 0.25f)
            }
            val h = midY * amp
            drawLine(
                color = baseColor,
                start = Offset(i * barWidth, midY - h),
                end = Offset(i * barWidth, midY + h),
                strokeWidth = barWidth * 0.6f
            )
        }

        // Loop region band, drawn under the cue marker/playhead so those
        // stay visually on top.
        if (durationMs > 0 && loopInMs != null && loopOutMs != null) {
            val xIn = size.width * (loopInMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val xOut = size.width * (loopOutMs.toFloat() / durationMs).coerceIn(0f, 1f)
            drawRect(
                color = Color.White.copy(alpha = if (loopActive) 0.18f else 0.08f),
                topLeft = Offset(xIn, 0f),
                size = androidx.compose.ui.geometry.Size(xOut - xIn, size.height)
            )
            // Bright edge ticks so in/out are legible even when the band is faint.
            drawLine(Color.White, Offset(xIn, 0f), Offset(xIn, size.height), strokeWidth = 2f)
            drawLine(Color.White, Offset(xOut, 0f), Offset(xOut, size.height), strokeWidth = 2f)
        }

        // Cue point marker - small flag at its x-fraction.
        if (durationMs > 0 && cuePointMs != null) {
            val x = size.width * (cuePointMs.toFloat() / durationMs).coerceIn(0f, 1f)
            drawLine(
                color = Color(0xFFFFC107),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.5f
            )
        }

        // Playhead
        drawLine(
            color = Color.White,
            start = Offset(size.width * progress, 0f),
            end = Offset(size.width * progress, size.height),
            strokeWidth = 2f
        )

        // Scrub cursor while a drag is in flight: where the seek will land
        // on release, in the deck's accent so it reads as "pending".
        scrub?.let { f ->
            val x = size.width * f
            drawLine(accentColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
        }
    }
}
