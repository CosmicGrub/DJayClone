package com.oblivion.djayclone

import android.app.Application
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle

/**
 * The deck clock: "elapsed / total", or "-remaining / total" - tap it to
 * switch, and the choice is persisted (Settings.showTimeRemaining) and shared
 * by both decks, the same way djay remembers it. In the last 30 seconds of a
 * track that is actually playing it turns the warning colour and pulses, so a
 * DJ glancing at the deck sees "this track is about to end" without reading
 * a number.
 *
 * One shared composable replaces three copy-pasted Text() calls (Compact,
 * Expanded and TableTop each had their own). Toggling the DISPLAY is allowed
 * even on a locked deck: the lock exists to stop accidental changes to the
 * audio, and this changes nothing audible.
 */
@Composable
fun DeckTimeReadout(
    state: DeckUiState,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
) {
    val app = LocalContext.current.applicationContext as Application
    val repo = remember { SettingsRepository.get(app) }
    val settings by repo.settings.collectAsState()
    val showRemaining = settings.showTimeRemaining

    val nearEnd = state.isPlaying && TransportMath.isNearEnd(state.positionMs, state.durationMs)
    val pulse = if (nearEnd) {
        val t = rememberInfiniteTransition(label = "endWarning")
        val a by t.animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
            label = "endWarningAlpha",
        )
        a
    } else 1f

    val left = if (showRemaining) TransportMath.formatRemaining(state.positionMs, state.durationMs)
    else TransportMath.formatClock(state.positionMs)
    Text(
        text = "$left / ${TransportMath.formatClock(state.durationMs)}",
        color = if (nearEnd) Color(0xFFFF5A7A) else Color.Gray,
        style = style,
        maxLines = 1,
        modifier = modifier
            .alpha(pulse)
            .clickable(
                onClickLabel = if (showRemaining) "Show elapsed time" else "Show time remaining",
                role = Role.Button,
            ) { repo.setShowTimeRemaining(!showRemaining) },
    )
}

/**
 * A playback failure, in words, on the deck that failed. DeckUiState.error was
 * already being set when a file could not be played but nothing ever drew it,
 * so a bad file just looked like "nothing happened". Announced to screen
 * readers (live region), dismissable, and cleared by the next load.
 */
@Composable
fun DeckErrorBanner(state: DeckUiState, deck: DeckViewModel, modifier: Modifier = Modifier) {
    val message = state.error ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF3A1218), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = Color(0xFFFF8A9A),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            "✕",
            color = Color.Gray,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .clickable(onClickLabel = "Dismiss error", role = Role.Button) { deck.dismissError() }
                .padding(8.dp),
        )
    }
}

/**
 * "A  CROSSFADER  B" - with the ends swapped (and recoloured) when the
 * crossfader is reversed, so the label under each end always names the deck
 * that end actually plays. The audio reversal lives in MixerMath; this keeps
 * the picture honest. Must be placed inside a Row with SpaceBetween.
 */
@Composable
fun CrossfaderLabels() {
    val app = LocalContext.current.applicationContext as Application
    val repo = remember { SettingsRepository.get(app) }
    val reversed by repo.settings.collectAsState()
    val rev = reversed.crossfaderReversed
    Text(if (rev) "B" else "A", color = if (rev) AccentB else AccentA, style = MaterialTheme.typography.labelLarge)
    Text("CROSSFADER", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
    Text(if (rev) "A" else "B", color = if (rev) AccentA else AccentB, style = MaterialTheme.typography.labelLarge)
}
