package com.oblivion.djayclone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Stage 7 FX controls, mirroring CueLoopControls.kt's compact fixed-width
 * Box style and cancellation-safe gesture patterns.
 *
 * FILTER is a continuously-ridden mixing control (like GAIN) and lives
 * always-visible next to it in DeckColumn, not behind a tap. ECHO is
 * toggle-then-glance (like the beat-loop chips it sits next to) and lives
 * inside the existing collapsible CUE·LOOP·PADS·FX panel.
 */
@Composable
fun FilterSlider(
    state: DeckUiState,
    deck: DeckViewModel,
    accent: Color,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LPF", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Text("FILTER", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Text("HPF", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = state.filterValue,
            valueRange = -1f..1f,
            onValueChange = { deck.setFilter(it) },
            enabled = !locked,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.DarkGray,
            )
        )
    }
}

/**
 * 3-band EQ trim - LOW/MID/HIGH, alongside (not instead of) FILTER above.
 * Three compact vertical drag-bars in one row, not three Slider rows: a
 * real hardware measurement forced this - Material3's Slider enforces a
 * real minimum touch height regardless of what `.height()` asks for, and
 * three stacked Slider rows measured taller than DeckColumnExpanded's real
 * remaining vertical budget (confirmed via `uiautomator dump`'s exact pixel
 * bounds, not a guess) even after trimming everything else in that
 * function. A custom Canvas + drag gesture sidesteps Slider's enforced
 * minimum entirely, at the cost of writing the gesture handling by hand -
 * done here with `detectDragGestures`, the same Compose Foundation
 * primitive Slider itself is built on, not a hand-rolled substitute.
 */
@Composable
fun EqControls(
    state: DeckUiState,
    deck: DeckViewModel,
    accent: Color,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    Row(modifier = modifier.height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VerticalEqBar("LOW", state.eqLowDb, accent, !locked, modifier = Modifier.weight(1f)) { deck.setEqLow(it) }
        VerticalEqBar("MID", state.eqMidDb, accent, !locked, modifier = Modifier.weight(1f)) { deck.setEqMid(it) }
        VerticalEqBar("HIGH", state.eqHighDb, accent, !locked, modifier = Modifier.weight(1f)) { deck.setEqHigh(it) }
    }
}

@Composable
private fun VerticalEqBar(
    label: String,
    valueDb: Float,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    val range = EqAudioProcessor.MAX_DB - EqAudioProcessor.MIN_DB
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    fun updateFromY(y: Float) {
                        val frac = (1f - (y / size.height)).coerceIn(0f, 1f)
                        onChange(EqAudioProcessor.MIN_DB + frac * range)
                    }
                    detectDragGestures(
                        onDragStart = { offset -> updateFromY(offset.y) }
                    ) { change, _ ->
                        change.consume()
                        updateFromY(change.position.y)
                    }
                }
        ) {
            drawRoundRect(
                color = Color(0xFF1A1A1F),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
            // 0dB reference line - the bar fills FROM here, not from the
            // bottom, so cut and boost read as opposite directions from a
            // shared center, matching a real EQ trim control.
            val zeroFrac = (0f - EqAudioProcessor.MIN_DB) / range
            val zeroY = size.height * (1f - zeroFrac)
            drawLine(
                color = Color.Gray.copy(alpha = 0.6f),
                start = Offset(0f, zeroY), end = Offset(size.width, zeroY),
                strokeWidth = 1.dp.toPx(),
            )
            val valueFrac = ((valueDb - EqAudioProcessor.MIN_DB) / range).coerceIn(0f, 1f)
            val valueY = size.height * (1f - valueFrac)
            val top = minOf(valueY, zeroY)
            val bottom = maxOf(valueY, zeroY)
            if (bottom > top) {
                drawRect(
                    color = if (enabled) accent else Color.DarkGray,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, bottom - top),
                )
            }
        }
        Text(
            label,
            color = if (enabled) Color.Gray else Color.DarkGray,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** ECHO on/off toggle + ¼/½/1-beat division chips. Gated on `state.bpm !=
 * null` throughout, identical convention to BeatLoopChips - echo has
 * nothing to sync to without a detected tempo. */
@Composable
fun EchoRow(
    state: DeckUiState,
    deck: DeckViewModel,
    accent: Color,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    val enabled = state.bpm != null && !locked
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 40.dp)
                .background(
                    when {
                        state.echoActive -> accent
                        enabled -> Color(0xFF232328)
                        else -> Color(0xFF17171A)
                    },
                    RoundedCornerShape(4.dp)
                )
                .then(
                    if (enabled) {
                        Modifier.pointerInput(deck) {
                            detectTapGestures(onTap = { deck.toggleEcho() })
                        }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "ECHO",
                color = when {
                    state.echoActive -> Color.Black
                    enabled -> Color.Gray
                    else -> Color.DarkGray
                },
                style = MaterialTheme.typography.labelSmall, maxLines = 1
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(0.25f to "¼", 0.5f to "½", 1f to "1").forEach { (beats, label) ->
                val active = state.echoDivisionBeats == beats
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 28.dp)
                        .background(
                            when {
                                active && enabled -> accent
                                enabled -> Color(0xFF232328)
                                else -> Color(0xFF17171A)
                            },
                            RoundedCornerShape(4.dp)
                        )
                        .then(
                            if (enabled) {
                                Modifier.pointerInput(deck, beats) {
                                    awaitEachGesture {
                                        awaitFirstDown()
                                        // Cancellation-safe, same fix as the
                                        // other chip rows: a scroll-cancelled
                                        // touch must not still change the
                                        // division.
                                        if (waitForUpOrCancellation() != null) deck.setEchoDivision(beats)
                                    }
                                }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active && enabled) Color.Black else if (enabled) Color.White else Color.DarkGray,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
