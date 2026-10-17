package com.oblivion.djayclone

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Called when the DJ moves a deck's tempo by hand; the mixer releases a
 * sync lock that deck was following. */
val LocalManualTempo = androidx.compose.runtime.staticCompositionLocalOf<(DeckViewModel) -> Unit> { {} }

/**
 * Per-deck tempo fader: drag for a tempo change within the configured range,
 * double-tap to return to the original tempo, with a signed % readout.
 * Dragging a deck that is following a sync lock releases the lock (a manual
 * move beats the follower) via [LocalManualTempo].
 */
@Composable
fun TempoControls(
    state: DeckUiState,
    deck: DeckViewModel,
    settings: AppSettings,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val onManualTempo = LocalManualTempo.current
    val range = settings.tempoRangePercent
    val inverted = settings.tempoFaderInverted
    val locked = state.isLocked
    var dragPos by remember { mutableStateOf<Float?>(null) }
    val governor = remember { ParamGovernor() }
    val fp = TempoFaderMath.positionForSpeed(state.playbackSpeed, range, inverted)
    val shownPos = dragPos ?: fp.pos
    val track = if (locked) Color.DarkGray else Color.Gray

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                TempoFaderMath.formatDelta(state.playbackSpeed),
                color = if (fp.beyondRange) Color(0xFFFFB74D) else accent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "±$range%" + if (fp.beyondRange) "  beyond range" else "",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(locked, range, inverted) {
                    detectTapGestures(onDoubleTap = {
                        if (!locked) {
                            onManualTempo(deck)
                            deck.setPlaybackSpeed(1f)
                        }
                    })
                }
                .pointerInput(locked, range, inverted) {
                    if (locked) return@pointerInput
                    try {
                        detectHorizontalDragGestures(
                            onDragStart = { o ->
                                onManualTempo(deck)
                                governor.reset()
                                val p = (o.x / size.width * 2f - 1f).coerceIn(-1f, 1f)
                                dragPos = p
                                deck.setPlaybackSpeed(TempoFaderMath.speedForPosition(p, range, inverted))
                            },
                            onDragEnd = {
                                dragPos?.let { deck.setPlaybackSpeed(TempoFaderMath.speedForPosition(it, range, inverted)) }
                                dragPos = null
                            },
                            onDragCancel = { dragPos = null },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val p = (change.position.x / size.width * 2f - 1f).coerceIn(-1f, 1f)
                                dragPos = p
                                governor.offer(SystemClock.uptimeMillis(), TempoFaderMath.speedForPosition(p, range, inverted))
                                    ?.let { deck.setPlaybackSpeed(it) }
                            },
                        )
                    } finally {
                        dragPos = null
                    }
                }
        ) {
            val cy = size.height / 2f
            drawLine(track, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 4f)
            drawLine(Color.White, Offset(size.width / 2f, cy - 12f), Offset(size.width / 2f, cy + 12f), strokeWidth = 3f)
            val x = (shownPos + 1f) / 2f * size.width
            drawLine(if (locked) Color.DarkGray else accent, Offset(x, cy - 16f), Offset(x, cy + 16f), strokeWidth = 10f)
        }
    }
}

/** BPM correction: half / double / 3:2, typed value, tap tempo, restore. */
@Composable
fun BpmDialog(state: DeckUiState, deck: DeckViewModel, accent: Color, onDismiss: () -> Unit) {
    val tap = remember { TapTempo() }
    var tapResult by remember { mutableStateOf<TapResult?>(null) }
    var text by remember { mutableStateOf(state.bpm?.let { "%.2f".format(it) }?.trimEnd('0')?.trimEnd('.') ?: "") }
    val parsed = BpmMath.parse(text)
    val locked = state.isLocked

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BPM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Analysed ${state.analyzedBpm?.let { "%.1f".format(it) } ?: "—"}" +
                        if (state.userBpm != null) "   •   Yours ${"%.2f".format(state.userBpm)}" else "",
                    color = Color.Gray, style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("÷2" to 0.5f, "×2" to 2f, "×1.5" to 1.5f, "×2/3" to (2f / 3f)).forEach { (label, factor) ->
                        TextButton(
                            enabled = parsed != null && BpmMath.times(parsed, factor) != null,
                            onClick = { BpmMath.times(parsed!!, factor)?.let { text = "%.2f".format(it).trimEnd('0').trimEnd('.') } },
                        ) { Text(label) }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = text.isNotBlank() && parsed == null,
                    label = { Text("BPM (${BpmMath.MIN_BPM.toInt()}–${BpmMath.MAX_BPM.toInt()})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        val r = tap.tap(SystemClock.elapsedRealtime())
                        tapResult = r
                        r.bpm?.let { text = "%.1f".format(it) }
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    val r = tapResult
                    Text(
                        if (r?.bpm != null) "TAP  •  %.1f%s".format(r.bpm, if (r.stable) "  ✓" else "") else "TAP",
                        color = accent, style = MaterialTheme.typography.titleMedium
                    )
                }
                if (locked) Text("Deck is locked.", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && !locked, onClick = { deck.setUserBpm(parsed); onDismiss() }) { Text("APPLY") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = state.userBpm != null && !locked,
                    onClick = { deck.setUserBpm(null); onDismiss() },
                ) { Text("RESTORE") }
                TextButton(onClick = onDismiss) { Text("CANCEL") }
            }
        },
    )
}
