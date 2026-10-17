package com.oblivion.djayclone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the mixer-level state that used to live in MixerScreen's `remember {}`
 * blocks: crossfader position, sync lock, sync ratio mode - and the two
 * controllers that act on them (crossfade volume, continuous tempo sync).
 *
 * Why this exists: MainActivity swaps the whole Mixer composable out when
 * Settings or the Library opens, and a configuration change (fold, rotate,
 * DeX resize) recreates it. `remember` state does not survive either, so
 * opening Settings mid-set silently re-centred the crossfader and dropped
 * the sync lock, and (since the controllers were LaunchedEffects inside the
 * same composable) a locked follower stopped tracking its leader for as long
 * as another screen was showing. An Activity-scoped ViewModel survives
 * recreation, and its controllers run in viewModelScope for as long as the
 * mixer exists, independent of which screen is up.
 *
 * The maths lives in [MixerMath] / [SyncController] (pure, JVM-tested); this
 * class is only state plumbing.
 */
class MixerViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository.get(application)

    // Seeded once from the persisted default, not re-applied if the setting
    // changes later - a physical crossfader does not move by itself.
    private val _crossfader = MutableStateFlow(settingsRepository.settings.value.defaultCrossfaderPosition)
    val crossfader: StateFlow<Float> = _crossfader.asStateFlow()

    /** [SyncController.B_FOLLOWS_A] | [SyncController.A_FOLLOWS_B] | null. */
    private val _syncLock = MutableStateFlow<String?>(null)
    val syncLock: StateFlow<String?> = _syncLock.asStateFlow()

    private val _syncRatioMode = MutableStateFlow(SyncRatioMode.AUTO)
    val syncRatioMode: StateFlow<SyncRatioMode> = _syncRatioMode.asStateFlow()

    fun setCrossfader(x: Float) { _crossfader.value = x.coerceIn(0f, 1f) }
    fun setSyncLock(lock: String?) { _syncLock.value = lock }
    fun setSyncRatioMode(mode: SyncRatioMode) { _syncRatioMode.value = mode }

    private var bound = false

    /** Starts the controllers. Idempotent: MainActivity.onCreate runs again
     * on every recreation, but this ViewModel (and its already-running
     * collectors) outlives the Activity. */
    fun bind(deckA: DeckViewModel, deckB: DeckViewModel) {
        if (bound) return
        bound = true

        // Crossfade volume: channel fader x Auto Gain trim x crossfader law.
        viewModelScope.launch {
            combine(
                _crossfader,
                settingsRepository.settings.map { it.crossfaderCurve to it.crossfaderReversed }.distinctUntilChanged(),
                deckA.state.map { it.gain to it.autoGainTrim }.distinctUntilChanged(),
                deckB.state.map { it.gain to it.autoGainTrim }.distinctUntilChanged(),
            ) { x, curve, a, b ->
                val (fa, fb) = MixerMath.crossfadeFactors(x, curve.first, curve.second)
                MixerMath.effectiveVolume(a.first, a.second, fa) to MixerMath.effectiveVolume(b.first, b.second, fb)
            }.collect { (volA, volB) ->
                deckA.setEffectiveVolume(volA)
                deckB.setEffectiveVolume(volB)
            }
        }

        // Continuous tempo sync: once engaged, the follower keeps tracking
        // the leader's EFFECTIVE bpm (analysed tempo x its own speed), not
        // just a one-time snap - so nudging the leader afterwards keeps both
        // decks locked.
        viewModelScope.launch {
            combine(
                _syncLock,
                _syncRatioMode,
                settingsRepository.settings.map { it.tempoSyncConfig.ratioToleranceFraction }.distinctUntilChanged(),
                deckA.state.map { it.bpm to it.playbackSpeed }.distinctUntilChanged(),
                deckB.state.map { it.bpm to it.playbackSpeed }.distinctUntilChanged(),
            ) { lock, mode, tol, a, b ->
                SyncController.compute(lock, mode, tol, a.first, b.first, a.second, b.second)
            }.collect { target ->
                if (target != null) (if (target.followerIsA) deckA else deckB).setPlaybackSpeed(target.speed)
            }
        }
    }
}
