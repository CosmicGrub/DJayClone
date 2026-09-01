package com.oblivion.djayclone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Stage 8: adjustable FX ranges, hot cue count, and playback/behavior
 * defaults, backed by SettingsRepository. A full-screen swap (not a dialog/
 * bottom sheet, not Navigation Compose) - this is the app's second and only
 * other screen, so neither a modal overlay nor a navigation framework is
 * warranted for one destination. Both decks keep playing underneath
 * regardless, since they live in Activity-scoped ViewModels independent of
 * what's on screen.
 */
@Composable
fun SettingsScreen(
    settingsRepo: SettingsRepository,
    deckAHotCues: List<Long?>,
    deckBHotCues: List<Long?>,
    onBack: () -> Unit,
) {
    val settings by settingsRepo.settings.collectAsState()

    // Non-null while a hot-cue-count change is pending user confirmation
    // because it would discard at least one filled pad on either deck.
    var pendingHotCueCount by remember { mutableStateOf<Int?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    fun wouldLoseHotCues(newCount: Int): Boolean =
        deckAHotCues.drop(newCount).any { it != null } || deckBHotCues.drop(newCount).any { it != null }

    fun requestHotCueCount(newCount: Int) {
        if (newCount == settings.hotCueCount) return
        if (wouldLoseHotCues(newCount)) pendingHotCueCount = newCount else settingsRepo.setHotCueCount(newCount)
    }

    // Stage 10b: "width-capped & centered" - the pitch's own "1.5" tier, not
    // a real second arrangement. A settings list has no natural second zone
    // to fill the way the mixer's center column/library sidebar do, so this
    // is the whole of Medium/Expanded's treatment here: cap the reading
    // width instead of letting a form of labels+sliders stretch edge-to-edge
    // across a tablet. widthIn(max=...) is a no-op on Compact's narrower
    // screens (always well under the cap), so no LocalDjLayoutClass branch
    // is needed - one Box unconditionally does the whole job.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        // widthIn BEFORE fillMaxWidth, not after: fillMaxWidth() first would
        // pin min=max=the Box's full screen-width constraint, and a later
        // widthIn(max=640.dp) can't shrink a max below an already-fixed min
        // - it would silently no-op, which is exactly the bug this comment
        // is here to stop from regressing. widthIn first narrows the
        // incoming max to min(640dp, available); fillMaxWidth then fills
        // exactly up to that already-capped ceiling.
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("SETTINGS", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }

        SettingsSection("FX RANGES · LIMITS")
        LabeledSlider(
            label = "Filter LPF min cutoff",
            value = settings.filterRange.lpfMinHz,
            valueRange = 60f..1000f,
            format = { "%.0f Hz".format(it) },
            onValueChangeFinished = { settingsRepo.setFilterRange(settings.filterRange.copy(lpfMinHz = it)) }
        )
        LabeledSlider(
            label = "Filter HPF max cutoff",
            value = settings.filterRange.hpfMaxHz,
            valueRange = 2000f..15000f,
            format = { "%.0f Hz".format(it) },
            onValueChangeFinished = { settingsRepo.setFilterRange(settings.filterRange.copy(hpfMaxHz = it)) }
        )
        LabeledSlider(
            label = "Max echo feedback",
            value = settings.echoRange.maxFeedbackGain,
            valueRange = 0.1f..0.7f,
            format = { "%.0f%%".format(it * 100) },
            onValueChangeFinished = { settingsRepo.setEchoRange(settings.echoRange.copy(maxFeedbackGain = it)) }
        )
        LabeledSlider(
            label = "Max echo wet mix",
            value = settings.echoRange.maxWetMix,
            valueRange = 0.5f..1f,
            format = { "%.0f%%".format(it * 100) },
            onValueChangeFinished = { settingsRepo.setEchoRange(settings.echoRange.copy(maxWetMix = it)) }
        )
        SwitchRow(
            label = "Extended loop lengths (adds 1/8, 16 beat)",
            checked = settings.extendedLoopLengths,
            onCheckedChange = { settingsRepo.setExtendedLoopLengths(it) }
        )

        SettingsSection("HOT CUES")
        Text("Pad count", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(4, 8, 16).forEach { count ->
                val selected = settings.hotCueCount == count
                TextButton(onClick = { requestHotCueCount(count) }) {
                    Text(
                        "$count",
                        color = if (selected) AccentA else Color.Gray,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        SettingsSection("PLAYBACK · BEHAVIOR DEFAULTS")
        LabeledSlider(
            label = "Default crossfader position",
            value = settings.defaultCrossfaderPosition,
            valueRange = 0f..1f,
            format = { "A ${"%.0f".format((1 - it) * 100)}% / B ${"%.0f".format(it * 100)}%" },
            onValueChangeFinished = { settingsRepo.setDefaultCrossfaderPosition(it) }
        )
        SwitchRow(
            label = "Keep loops/cues across track load",
            checked = settings.persistLoopsAndCuesAcrossLoad,
            onCheckedChange = { settingsRepo.setPersistLoopsAndCuesAcrossLoad(it) }
        )
        SwitchRow(
            label = "Keep FX across track load",
            checked = settings.persistFxAcrossLoad,
            onCheckedChange = { settingsRepo.setPersistFxAcrossLoad(it) }
        )
        LabeledSlider(
            label = "Coarse nudge step",
            value = settings.nudgeCoarsePercent * 100,
            valueRange = 0.1f..5f,
            format = { "%.2f%%".format(it) },
            onValueChangeFinished = { settingsRepo.setNudgeSteps(it / 100f, settings.nudgeFinePercent) }
        )
        LabeledSlider(
            label = "Fine nudge step",
            value = settings.nudgeFinePercent * 100,
            valueRange = 0.01f..1f,
            format = { "%.3f%%".format(it) },
            onValueChangeFinished = { settingsRepo.setNudgeSteps(settings.nudgeCoarsePercent, it / 100f) }
        )

        SettingsSection("TEMPO SYNC")
        LabeledSlider(
            label = "Half/double-time match tolerance",
            value = settings.tempoSyncConfig.ratioToleranceFraction * 100,
            valueRange = 1f..25f,
            format = { "%.0f%%".format(it) },
            onValueChangeFinished = {
                settingsRepo.setTempoSyncConfig(settings.tempoSyncConfig.copy(ratioToleranceFraction = it / 100f))
            }
        )

        TextButton(onClick = { showResetConfirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("RESET TO DEFAULTS", color = Color(0xFFFF5A7A), style = MaterialTheme.typography.labelMedium)
        }

        Text(
            "Settings persist across app updates but are cleared if you uninstall the app or clear its storage.",
            color = Color.DarkGray,
            style = MaterialTheme.typography.labelSmall
        )
    }
    } // Box (width cap)

    // Dialogs stay outside the width-capped Box on purpose - AlertDialog
    // renders into its own platform Dialog window regardless of where it
    // sits in the composition, so nesting it inside the cap would constrain
    // nothing real while adding a misleading indent.
    pendingHotCueCount?.let { newCount ->
        AlertDialog(
            onDismissRequest = { pendingHotCueCount = null },
            title = { Text("Shrink hot cue pads to $newCount?") },
            text = {
                Text(
                    "Deck A and/or Deck B have hot cues saved on pads beyond slot $newCount. " +
                        "Shrinking will permanently remove them."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settingsRepo.setHotCueCount(newCount)
                    pendingHotCueCount = null
                }) { Text("Remove and shrink", color = Color(0xFFFF5A7A)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingHotCueCount = null }) { Text("Cancel") }
            }
        )
    }

    if (showResetConfirm) {
        val defaultHotCueCount = AppSettings().hotCueCount
        val wouldLoseOnReset = wouldLoseHotCues(defaultHotCueCount)
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset all settings to defaults?") },
            text = {
                Text(
                    if (wouldLoseOnReset) {
                        "This also shrinks hot cue pads back to $defaultHotCueCount, which will permanently " +
                            "remove cues saved on higher pads on Deck A and/or Deck B."
                    } else {
                        "This resets FX ranges, hot cue count, playback defaults, and tempo sync tolerance to their original values."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settingsRepo.resetToDefaults()
                    showResetConfirm = false
                }) { Text("Reset", color = Color(0xFFFF5A7A)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        color = AccentA,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onValueChangeFinished: (Float) -> Unit,
) {
    // Local draft so the slider feels responsive while dragging; the
    // repository (and its disk write) only gets touched once per completed
    // drag via onValueChangeFinished, not on every intermediate frame.
    var draft by remember(value) { mutableStateOf(value) }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Text(format(draft), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = draft,
            valueRange = valueRange,
            onValueChange = { draft = it },
            onValueChangeFinished = { onValueChangeFinished(draft) },
            colors = SliderDefaults.colors(
                thumbColor = AccentA,
                activeTrackColor = AccentA,
                inactiveTrackColor = Color.DarkGray,
            )
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // weight(1f), not an unconstrained Text: a long label (e.g.
        // "Extended loop lengths (adds 1/8, 16 beat)") would otherwise push
        // the Switch itself past the screen's right edge instead of
        // wrapping - this is what actually reserves room for the switch.
        Text(
            label, color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = AccentA, checkedTrackColor = AccentA.copy(alpha = 0.5f))
        )
    }
}
