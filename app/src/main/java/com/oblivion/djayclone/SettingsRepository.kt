package com.oblivion.djayclone

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bundles the two filter cutoff endpoints that share a joint invariant (a
 * matched pair the audio thread must always see consistently together) -
 * published as ONE @Volatile reference, same discipline Stage 7 already
 * established for FILTER/ECHO live parameters. The bypass-side endpoints
 * (20000Hz for LPF-open, 20Hz for HPF-open) stay hardcoded in
 * FilterAudioProcessor, not exposed here - exposing them risks a
 * degenerate near-zero-range filter for no stated user benefit. */
data class FilterRangeConfig(
    val lpfMinHz: Float = 150f,   // how low the LPF can sweep to
    val hpfMaxHz: Float = 7000f,  // how high the HPF can sweep to
)

data class EchoRangeConfig(
    val maxFeedbackGain: Float = 0.4f,
    val maxWetMix: Float = 1f,
)

/** Tempo-Sync v1 - see TempoSyncMath.kt. ratioToleranceFraction is the one
 * genuinely tunable knob (an engineering default, not a sourced constant -
 * see TempoSyncMath.DEFAULT_RATIO_TOLERANCE's own doc for why). */
data class TempoSyncConfig(
    val ratioToleranceFraction: Float = TempoSyncMath.DEFAULT_RATIO_TOLERANCE,
)

data class AppSettings(
    // Category 1: FX ranges/limits
    val filterRange: FilterRangeConfig = FilterRangeConfig(),
    val echoRange: EchoRangeConfig = EchoRangeConfig(),
    val extendedLoopLengths: Boolean = false, // false = current 6 chips (1/4 1/2 1 2 4 8); true adds 1/8 and 16

    // Category 2: hot cue count / pad layout
    val hotCueCount: Int = 8, // one of {4, 8, 16} - all cleanly divisible by 4, HotCueGrid's chunked(4) needs no change

    // Category 3: playback/behavior defaults
    val defaultCrossfaderPosition: Float = 0.5f, // seeds MixerScreen's crossfader once at composition, not retroactive
    val persistLoopsAndCuesAcrossLoad: Boolean = false, // false = current behavior (always reset on loadTrack)
    val persistFxAcrossLoad: Boolean = true,            // true = current Stage 7 behavior (never reset)
    val nudgeCoarsePercent: Float = 0.01f,
    val nudgeFinePercent: Float = 0.001f,
    val tempoSyncConfig: TempoSyncConfig = TempoSyncConfig(),
    // Auto Gain: on by default, matching real DJ software's convention -
    // see AudioAnalyzer.estimateAutoGain's own doc for what it computes and
    // why it can only ever attenuate, never boost.
    val autoGainEnabled: Boolean = true,
    // Crossfader law and orientation (see MixerMath). SMOOTH is what the app
    // has always done; "reversed" is hamster mode (A on the right).
    val crossfaderCurve: CrossfaderCurve = CrossfaderCurve.SMOOTH,
    val crossfaderReversed: Boolean = false,
    // Deck clock shows time REMAINING ("-1:55") instead of elapsed.
    // Toggled by tapping the clock itself; persisted so it sticks.
    val showTimeRemaining: Boolean = false,
    // Hold the screen awake while a deck is playing or a recording is
    // running - a DJ set that blanks the screen mid-mix is a disaster.
    val keepScreenOn: Boolean = true,
    // Pause both decks when headphones/Bluetooth/USB output disconnects, so the
    // cue feed never jumps to the speaker at full volume.
    val pauseOnOutputLoss: Boolean = true,
    // Tempo fader: full-scale range in percent (TempoFaderMath.ALLOWED_RANGES) and direction.
    val tempoRangePercent: Int = 8,
    val tempoFaderInverted: Boolean = false,
    // Ask before loading a track onto a deck that is currently playing
    // (see LoadGuard). A locked deck refuses loads regardless.
    val confirmLoadOnPlayingDeck: Boolean = true,
)

/**
 * SharedPreferences-backed settings, exposed as a StateFlow matching this
 * codebase's existing StateFlow-everywhere style (DeckViewModel.state,
 * RecordingViewModel.state). No DataStore: DataStore's Flow/suspend API
 * would insert a coroutine hop between "user drags a slider" and "the
 * @Volatile field updates" that this codebase's existing synchronous
 * setFilter()/toggleEcho()/setGain() pattern doesn't have anywhere else -
 * plain SharedPreferences' synchronous-to-memory apply() matches that style
 * directly, with zero new Gradle dependency.
 *
 * SettingsRepository is the ONLY writer (no other component ever touches
 * these SharedPreferences), so there's no need to round-trip through
 * OnSharedPreferenceChangeListener - every setter updates the in-memory
 * StateFlow and persists to disk in the same call, avoiding that listener
 * API's well-known weak-reference footgun entirely.
 *
 * Persistence note, stated explicitly: this survives normal app UPDATES
 * (new APK installed over old) but is wiped on UNINSTALL or "Clear
 * storage." Android Auto Backup is enabled (allowBackup), so these
 * settings - and only these; see res/xml/backup_rules.xml - can be restored
 * from a Google backup after a reinstall or onto a new device.
 */
class SettingsRepository private constructor(app: Application) {

    // Own SharedPreferences file, not the default one, so resetToDefaults()'s
    // clear() can never touch unrelated prefs some future feature adds.
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadFromPrefs())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadFromPrefs(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            filterRange = FilterRangeConfig(
                lpfMinHz = prefs.getFloat(KEY_LPF_MIN, d.filterRange.lpfMinHz),
                hpfMaxHz = prefs.getFloat(KEY_HPF_MAX, d.filterRange.hpfMaxHz),
            ),
            echoRange = EchoRangeConfig(
                maxFeedbackGain = prefs.getFloat(KEY_MAX_FEEDBACK, d.echoRange.maxFeedbackGain),
                maxWetMix = prefs.getFloat(KEY_MAX_WET_MIX, d.echoRange.maxWetMix),
            ),
            extendedLoopLengths = prefs.getBoolean(KEY_EXTENDED_LOOPS, d.extendedLoopLengths),
            hotCueCount = prefs.getInt(KEY_HOT_CUE_COUNT, d.hotCueCount),
            defaultCrossfaderPosition = prefs.getFloat(KEY_DEFAULT_CROSSFADER, d.defaultCrossfaderPosition),
            persistLoopsAndCuesAcrossLoad = prefs.getBoolean(KEY_PERSIST_LOOPS, d.persistLoopsAndCuesAcrossLoad),
            persistFxAcrossLoad = prefs.getBoolean(KEY_PERSIST_FX, d.persistFxAcrossLoad),
            nudgeCoarsePercent = prefs.getFloat(KEY_NUDGE_COARSE, d.nudgeCoarsePercent),
            nudgeFinePercent = prefs.getFloat(KEY_NUDGE_FINE, d.nudgeFinePercent),
            tempoSyncConfig = TempoSyncConfig(
                ratioToleranceFraction = prefs.getFloat(KEY_SYNC_TOLERANCE, d.tempoSyncConfig.ratioToleranceFraction),
            ),
            autoGainEnabled = prefs.getBoolean(KEY_AUTO_GAIN_ENABLED, d.autoGainEnabled),
            // Stored by name so reordering the enum can never silently remap
            // a saved choice; an unknown/removed name falls back to default.
            crossfaderCurve = runCatching {
                CrossfaderCurve.valueOf(prefs.getString(KEY_XFADE_CURVE, d.crossfaderCurve.name) ?: d.crossfaderCurve.name)
            }.getOrDefault(d.crossfaderCurve),
            crossfaderReversed = prefs.getBoolean(KEY_XFADE_REVERSED, d.crossfaderReversed),
            showTimeRemaining = prefs.getBoolean(KEY_TIME_REMAINING, d.showTimeRemaining),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, d.keepScreenOn),
            pauseOnOutputLoss = prefs.getBoolean(KEY_PAUSE_ON_OUTPUT_LOSS, d.pauseOnOutputLoss),
            tempoRangePercent = TempoFaderMath.coerceRange(prefs.getInt(KEY_TEMPO_RANGE, d.tempoRangePercent)),
            tempoFaderInverted = prefs.getBoolean(KEY_TEMPO_INVERTED, d.tempoFaderInverted),
            confirmLoadOnPlayingDeck = prefs.getBoolean(KEY_CONFIRM_LOAD, d.confirmLoadOnPlayingDeck),
        )
    }

    fun setFilterRange(config: FilterRangeConfig) {
        val c = FilterRangeConfig(
            lpfMinHz = config.lpfMinHz.coerceIn(60f, 1000f),
            hpfMaxHz = config.hpfMaxHz.coerceIn(2000f, 15000f),
        )
        prefs.edit().putFloat(KEY_LPF_MIN, c.lpfMinHz).putFloat(KEY_HPF_MAX, c.hpfMaxHz).apply()
        _settings.value = _settings.value.copy(filterRange = c)
    }

    fun setEchoRange(config: EchoRangeConfig) {
        // Coerced to 0.7f max here too (matches EchoAudioProcessor's own
        // independent MAX_SAFE_FEEDBACK ceiling) - defense in depth, not
        // trusting a single clamp point against a hand-edited prefs file.
        val c = EchoRangeConfig(
            maxFeedbackGain = config.maxFeedbackGain.coerceIn(0.1f, 0.7f),
            maxWetMix = config.maxWetMix.coerceIn(0.5f, 1f),
        )
        prefs.edit().putFloat(KEY_MAX_FEEDBACK, c.maxFeedbackGain).putFloat(KEY_MAX_WET_MIX, c.maxWetMix).apply()
        _settings.value = _settings.value.copy(echoRange = c)
    }

    fun setExtendedLoopLengths(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXTENDED_LOOPS, enabled).apply()
        _settings.value = _settings.value.copy(extendedLoopLengths = enabled)
    }

    /** Caller (SettingsScreen) is responsible for confirming data loss with
     * the user BEFORE calling this when shrinking would drop filled hot-cue
     * slots - this setter just applies the value unconditionally once
     * called, matching how the rest of this repository has no UI-layer
     * knowledge of its own. */
    fun setHotCueCount(count: Int) {
        val c = count.coerceIn(4, 16)
        prefs.edit().putInt(KEY_HOT_CUE_COUNT, c).apply()
        _settings.value = _settings.value.copy(hotCueCount = c)
    }

    fun setDefaultCrossfaderPosition(position: Float) {
        val c = position.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_DEFAULT_CROSSFADER, c).apply()
        _settings.value = _settings.value.copy(defaultCrossfaderPosition = c)
    }

    fun setPersistLoopsAndCuesAcrossLoad(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSIST_LOOPS, enabled).apply()
        _settings.value = _settings.value.copy(persistLoopsAndCuesAcrossLoad = enabled)
    }

    fun setPersistFxAcrossLoad(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSIST_FX, enabled).apply()
        _settings.value = _settings.value.copy(persistFxAcrossLoad = enabled)
    }

    fun setPauseOnOutputLoss(on: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_ON_OUTPUT_LOSS, on).apply()
        _settings.value = _settings.value.copy(pauseOnOutputLoss = on)
    }

    fun setTempoRangePercent(pct: Int) {
        val v = TempoFaderMath.coerceRange(pct)
        prefs.edit().putInt(KEY_TEMPO_RANGE, v).apply()
        _settings.value = _settings.value.copy(tempoRangePercent = v)
    }

    fun setTempoFaderInverted(on: Boolean) {
        prefs.edit().putBoolean(KEY_TEMPO_INVERTED, on).apply()
        _settings.value = _settings.value.copy(tempoFaderInverted = on)
    }

    fun setNudgeSteps(coarsePercent: Float, finePercent: Float) {
        val coarse = coarsePercent.coerceIn(0.001f, 0.05f)
        val fine = finePercent.coerceIn(0.0001f, 0.01f)
        prefs.edit().putFloat(KEY_NUDGE_COARSE, coarse).putFloat(KEY_NUDGE_FINE, fine).apply()
        _settings.value = _settings.value.copy(nudgeCoarsePercent = coarse, nudgeFinePercent = fine)
    }

    fun setAutoGainEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_GAIN_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(autoGainEnabled = enabled)
    }

    fun setCrossfaderCurve(curve: CrossfaderCurve) {
        prefs.edit().putString(KEY_XFADE_CURVE, curve.name).apply()
        _settings.value = _settings.value.copy(crossfaderCurve = curve)
    }

    fun setKeepScreenOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, on).apply()
        _settings.value = _settings.value.copy(keepScreenOn = on)
    }

    fun setConfirmLoadOnPlayingDeck(on: Boolean) {
        prefs.edit().putBoolean(KEY_CONFIRM_LOAD, on).apply()
        _settings.value = _settings.value.copy(confirmLoadOnPlayingDeck = on)
    }

    fun setShowTimeRemaining(remaining: Boolean) {
        prefs.edit().putBoolean(KEY_TIME_REMAINING, remaining).apply()
        _settings.value = _settings.value.copy(showTimeRemaining = remaining)
    }

    fun setCrossfaderReversed(reversed: Boolean) {
        prefs.edit().putBoolean(KEY_XFADE_REVERSED, reversed).apply()
        _settings.value = _settings.value.copy(crossfaderReversed = reversed)
    }

    fun setTempoSyncConfig(config: TempoSyncConfig) {
        // 1%-25% - below 1% risks false-positive matches from ordinary BPM-
        // detection jitter; above 25% starts overlapping the real spacing
        // between the three ratio candidates (see TempoSyncMath's doc).
        val c = TempoSyncConfig(ratioToleranceFraction = config.ratioToleranceFraction.coerceIn(0.01f, 0.25f))
        prefs.edit().putFloat(KEY_SYNC_TOLERANCE, c.ratioToleranceFraction).apply()
        _settings.value = _settings.value.copy(tempoSyncConfig = c)
    }

    /** Caller is responsible for confirming hot-cue data loss first, same
     * as setHotCueCount() - resetting can also shrink hotCueCount back to
     * its default. */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _settings.value = AppSettings()
    }

    companion object {
        private const val PREFS_NAME = "djayclone_settings"
        private const val KEY_LPF_MIN = "lpf_min_hz"
        private const val KEY_HPF_MAX = "hpf_max_hz"
        private const val KEY_MAX_FEEDBACK = "max_feedback"
        private const val KEY_MAX_WET_MIX = "max_wet_mix"
        private const val KEY_EXTENDED_LOOPS = "extended_loops"
        private const val KEY_HOT_CUE_COUNT = "hot_cue_count"
        private const val KEY_DEFAULT_CROSSFADER = "default_crossfader"
        private const val KEY_PERSIST_LOOPS = "persist_loops"
        private const val KEY_PERSIST_FX = "persist_fx"
        private const val KEY_NUDGE_COARSE = "nudge_coarse"
        private const val KEY_NUDGE_FINE = "nudge_fine"
        private const val KEY_SYNC_TOLERANCE = "sync_ratio_tolerance"
        private const val KEY_AUTO_GAIN_ENABLED = "auto_gain_enabled"
        private const val KEY_XFADE_CURVE = "crossfader_curve"
        private const val KEY_XFADE_REVERSED = "crossfader_reversed"
        private const val KEY_TIME_REMAINING = "show_time_remaining"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_PAUSE_ON_OUTPUT_LOSS = "pause_on_output_loss"
        private const val KEY_TEMPO_RANGE = "tempo_range_percent"
        private const val KEY_TEMPO_INVERTED = "tempo_fader_inverted"
        private const val KEY_CONFIRM_LOAD = "confirm_load_on_playing"

        @Volatile private var INSTANCE: SettingsRepository? = null
        fun get(app: Application): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(app).also { INSTANCE = it }
            }
    }
}
