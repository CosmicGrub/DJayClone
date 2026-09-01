package com.oblivion.djayclone

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val MIN_LOOP_MS = 20L
// Fallback used only for the brief instant between DeckUiState's default
// construction and init{} applying the real, current SettingsRepository
// value - not a source of truth (that's AppSettings.hotCueCount now).
private const val DEFAULT_HOT_CUE_COUNT = 8

/**
 * One playback deck. Media3/ExoPlayer gives us broad container/codec coverage
 * out of the box (MP3, AAC, FLAC, WAV, OGG/Vorbis, Opus). Anything Media3
 * can't decode natively can be added later via the FFmpeg extension without
 * changing this class's public surface.
 */
data class DeckUiState(
    val trackName: String = "No track loaded",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val gain: Float = 1f,
    val error: String? = null,
    val waveform: FloatArray? = null,
    val spectral: SpectralWaveform? = null,   // null until analysis completes; see AudioAnalyzer
    val bpm: Float? = null,
    val key: String? = null,      // Camelot notation ("8B" etc), from AudioAnalyzer/KeyDetector
    // Key Lock ("Master Tempo" in most DJ software): on by default, matching
    // that convention. NOT reset on loadTrack (see the reset block below,
    // which deliberately does not touch this field) - like echoActive/
    // filterValue's persistFxAcrossLoad treatment, this is a per-deck engine
    // preference, not per-track data, so it should survive across loads on
    // the same deck the way a DJ would expect a Master Tempo toggle to.
    val keyLockEnabled: Boolean = true,
    val isAnalyzing: Boolean = false,
    val playbackSpeed: Float = 1f,
    val albumArt: android.graphics.Bitmap? = null,

    // Stage 9: which uri is currently loaded - nothing before this recorded
    // it anywhere, but the track library needs it to attribute a completed
    // BPM analysis back to the right TrackId (see loadTrack's analysis
    // coroutine below).
    val uri: Uri? = null,

    // Stage 5: cue point (single markable position, stutter/preview semantics).
    val cuePointMs: Long? = null,

    // Stage 5: loop - manual in/out or a beat-multiple, plus on/off.
    val loopInMs: Long? = null,
    val loopOutMs: Long? = null,
    val loopActive: Boolean = false,
    val loopBeats: Float? = null, // which chip is "lit" (1/4, 1/2, 1, 2, 4, 8); null for manual loops

    // Stage 5: hot cue bank - index -> position, null = empty pad. In-memory
    // only. Count is user-configurable since Stage 8 (see SettingsRepository.
    // AppSettings.hotCueCount) - this default is just the pre-init placeholder.
    val hotCues: List<Long?> = List(DEFAULT_HOT_CUE_COUNT) { null },

    // Stage 7: per-deck FX. Mixer/channel-strip settings, like `gain` above -
    // deliberately NOT reset in loadTrack() (see the comment there). A
    // physical DJ mixer's filter knob position doesn't reset when you cue up
    // a new record, and the same reasoning applies to leaving echo on/off
    // and which division is selected.
    val filterValue: Float = 0f,          // -1 (full LPF) .. 0 (bypass) .. +1 (full HPF)
    val echoActive: Boolean = false,
    val echoDivisionBeats: Float = 0.5f,  // 0.25 / 0.5 / 1.0 - which chip is lit

    // 3-band EQ trim, alongside (not instead of) the filter above - see
    // EqAudioProcessor. dB, EqAudioProcessor.MIN_DB..MAX_DB, 0 = unity.
    val eqLowDb: Float = 0f,
    val eqMidDb: Float = 0f,
    val eqHighDb: Float = 0f,

    // An input lock, not a mix feature - when true, every touch control on
    // this deck (import, transport, turntable tap, GAIN/FILTER, nudge,
    // cue/loop/hot-cue/echo) goes inert so an accidental touch mid-set can't
    // disturb a track that's live. Playback itself is completely unaffected -
    // the deck keeps playing/polling/rendering exactly as before; only input
    // is gated. The lock toggle control itself is deliberately excluded from
    // this gate everywhere it's wired, or there'd be no way back out.
    val isLocked: Boolean = false,
)

class DeckViewModel(application: Application) : AndroidViewModel(application) {

    // USAGE_MEDIA/CONTENT_TYPE_MUSIC is what tells Android (and anything
    // downstream of it - Bluetooth A2DP headphones/speakers, Android Auto,
    // wired headsets) that this is media audio: it's what makes routing to
    // whatever output is actually connected work automatically, and what
    // lets the media volume rocker/car head unit control it correctly.
    // Without explicit AudioAttributes, ExoPlayer falls back to a generic
    // default that some output devices/car systems classify or route wrong.
    //
    // handleAudioFocus is deliberately FALSE here, not the usual `true` a
    // single-player app would want: this app runs TWO ExoPlayer instances
    // simultaneously by design (that's the whole point of a two-deck DJ
    // mixer - both can play at once through the crossfader). If each
    // instance managed its own audio focus, Deck B starting playback would
    // make Android notify Deck A of a focus loss and ExoPlayer would pause
    // or duck it - breaking simultaneous two-deck playback entirely. Audio
    // focus is instead requested ONCE for the whole app in MainActivity,
    // covering both decks together, so they never fight each other.
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // Stage 7: per-deck FX, inserted into this deck's audio pipeline via a
    // custom RenderersFactory (see DjFxRenderersFactory) that overrides only
    // buildAudioSink() - codec selection, SeekParameters.EXACT, and the loop
    // watcher's player.seekTo() calls are all untouched by this existing.
    // One processor pair per deck, matching every other per-deck field here.
    private val filterProcessor = FilterAudioProcessor()
    private val echoProcessor = EchoAudioProcessor()

    // VU meter: a pure passthrough observer, not an FX - see
    // LevelAudioProcessor's own doc. Exposed read-only below as
    // [currentLevel] for a Compose meter to poll.
    private val levelProcessor = LevelAudioProcessor()

    // 3-band EQ trim, alongside filterProcessor - see EqAudioProcessor.
    private val eqProcessor = EqAudioProcessor()

    // Stage 8: user-adjustable FX ranges, hot cue count, playback defaults.
    private val settingsRepository = SettingsRepository.get(application)

    val player: ExoPlayer = ExoPlayer.Builder(
        application,
        DjFxRenderersFactory(application, filterProcessor, echoProcessor, levelProcessor, eqProcessor)
    )
        .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
        .build()

    private val _state = MutableStateFlow(DeckUiState())
    val state: StateFlow<DeckUiState> = _state.asStateFlow()

    init {
        // Exact seeking matters independently of the loop watcher's timing:
        // without it, seekTo() on a compressed stream (MP3/AAC) snaps to the
        // nearest sync frame (tens of ms wide), so even a perfectly-timed
        // trigger would land in the wrong place.
        player.setSeekParameters(SeekParameters.EXACT)

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _state.value = _state.value.copy(error = error.message)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A loop watcher armed against the old track must not survive
                // into the new one - there's a real window between
                // loadTrack()'s setMediaItem() and this callback firing where
                // a stale watcher could otherwise seek against the new item's
                // (unrelated) position. Disarming is still done here
                // (defensively, for any future transition path that doesn't
                // go through loadTrack()).
                //
                // Deliberately NOT re-resetting cuePointMs/loopInMs/loopOutMs/
                // loopActive/loopBeats/hotCues here, even though this used to.
                // loadTrack() already resets all of those synchronously at
                // call time, before setMediaItem() is even invoked. This
                // callback is delivered asynchronously (posted to the
                // application-thread handler, not inline with setMediaItem())
                // - redundantly resetting again here raced with any user
                // action landing in that gap (a fast double-tap, a quick
                // hot-cue tap right after import), silently discarding
                // whatever the user had just set for the *new* track with no
                // indication why. One authoritative reset site (loadTrack)
                // instead of two removes the race entirely.
                disarmLoop()
                _state.value = _state.value.copy(
                    trackName = mediaItem?.mediaMetadata?.title?.toString()
                        ?: mediaItem?.localConfiguration?.uri?.lastPathSegment
                        ?: "Unknown track",
                    durationMs = 0L,
                    positionMs = 0L,
                )
            }
        })

        // Poll position/duration for the UI (waveform/scrub bar) at ~10fps.
        // This is cosmetic only - loop-boundary correctness lives entirely in
        // the dedicated 15ms loop watcher (startLoopWatcher below), not here.
        // At 174 BPM a 1/4-beat loop is ~86ms; a 100ms poll's worst-case
        // detection lag alone would exceed the entire loop length, so this
        // loop must never be the thing that decides when to seek back to
        // loopInMs.
        viewModelScope.launch {
            while (isActive) {
                if (player.duration > 0) {
                    _state.value = _state.value.copy(
                        positionMs = player.currentPosition,
                        durationMs = player.duration,
                    )
                }
                // Stage 8: re-validate a persisted cue/loop position against
                // the current track's now-known duration, if the
                // persist-across-load setting kept them past a track change.
                // A cheap set of comparisons that self-limits (once cleared,
                // the conditions stop matching, so this doesn't keep
                // rewriting state every tick).
                if (settingsRepository.settings.value.persistLoopsAndCuesAcrossLoad) {
                    val st = _state.value
                    if (st.durationMs > 0) {
                        var cue = st.cuePointMs
                        var loopIn = st.loopInMs
                        var loopOut = st.loopOutMs
                        var loopBeats = st.loopBeats
                        var changed = false
                        if (cue != null && cue > st.durationMs) {
                            cue = null; changed = true
                        }
                        if (loopOut != null && loopOut > st.durationMs) {
                            loopIn = null; loopOut = null; loopBeats = null; changed = true
                        }
                        if (changed) {
                            _state.value = st.copy(
                                cuePointMs = cue, loopInMs = loopIn, loopOutMs = loopOut, loopBeats = loopBeats
                            )
                        }
                    }
                }
                kotlinx.coroutines.delay(100)
            }
        }

        // Stage 8: read current settings synchronously FIRST and apply them
        // directly - correctness here doesn't depend on collect{}'s first-
        // emission timing (which would work too via viewModelScope's
        // Main.immediate dispatcher + StateFlow's non-suspending first
        // emission, but the explicit read is easier to verify and immune to
        // a future dispatcher change). Only SUBSEQUENT changes flow through
        // the live collector below (drop(1) skips the current value, which
        // was already just applied here).
        val initialSettings = settingsRepository.settings.value
        _state.value = _state.value.copy(hotCues = List(initialSettings.hotCueCount) { null })
        filterProcessor.setRangeConfig(initialSettings.filterRange)
        echoProcessor.setRangeConfig(initialSettings.echoRange)

        viewModelScope.launch {
            settingsRepository.settings.drop(1).collect { applyConfig(it) }
        }
    }

    private fun applyConfig(settings: AppSettings) {
        filterProcessor.setRangeConfig(settings.filterRange)
        echoProcessor.setRangeConfig(settings.echoRange)
        resizeHotCues(settings.hotCueCount)
    }

    /** Growing: existing entries keep their indices, new trailing slots are
     * null. Shrinking: entries at indices >= newCount are dropped. The
     * caller (SettingsScreen, via SettingsRepository.setHotCueCount) is
     * responsible for confirming data loss with the user BEFORE the setting
     * actually changes - this method just applies the resize unconditionally
     * once the change reaches here. */
    private fun resizeHotCues(newCount: Int) {
        val current = _state.value.hotCues
        if (current.size == newCount) return
        val resized = List(newCount) { i -> current.getOrNull(i) }
        _state.value = _state.value.copy(hotCues = resized)
    }

    fun loadTrack(uri: Uri, displayName: String?) {
        disarmLoop()
        // Explicit, defense-in-depth silence of the echo delay line's
        // audible content on every track change - independent of whatever
        // Media3's own seek/media-item-transition flush() lifecycle does
        // (see the comment on EchoAudioProcessor.clearBuffer() for why this
        // isn't trusted to happen implicitly, given this codebase's history
        // with createMessage() not firing as documented). This runs
        // regardless of persistFxAcrossLoad below - the delay line's
        // CONTENT must never carry over into a new track even when echo's
        // on/off + division SETTING does.
        echoProcessor.clearBuffer()
        val settings = settingsRepository.settings.value
        val persistLoopsAndCues = settings.persistLoopsAndCuesAcrossLoad
        val persistFx = settings.persistFxAcrossLoad
        val current = _state.value
        _state.value = current.copy(
            error = null,
            waveform = null,
            spectral = null,
            bpm = null,
            key = null,
            isAnalyzing = true,
            playbackSpeed = 1f,
            albumArt = null,
            uri = uri,
            // Stage 8: cue/loop positions only reset when
            // persistLoopsAndCuesAcrossLoad is false (the default, matching
            // this app's original behavior exactly). When true, positions
            // are kept but re-validated against the new track's duration
            // once known - see the position-poll loop in init{} - since a
            // stale position past the new track's end would be actively
            // wrong, not just stale.
            cuePointMs = if (persistLoopsAndCues) current.cuePointMs else null,
            loopInMs = if (persistLoopsAndCues) current.loopInMs else null,
            loopOutMs = if (persistLoopsAndCues) current.loopOutMs else null,
            // loopActive is ALWAYS forced false here regardless of the
            // toggle above - resuming an active loop against a brand-new
            // track's unrelated audio would be actively wrong, not just
            // stale, even if the raw in/out millisecond values are kept.
            loopActive = false,
            loopBeats = if (persistLoopsAndCues) current.loopBeats else null,
            hotCues = List(settings.hotCueCount) { null },
            // Stage 7 FX fields: reset only when persistFxAcrossLoad is
            // false. See below for why the live @Volatile processor fields
            // must be reset in the same call, not just this Compose state.
            filterValue = if (persistFx) current.filterValue else 0f,
            echoActive = if (persistFx) current.echoActive else false,
            echoDivisionBeats = if (persistFx) current.echoDivisionBeats else 0.5f,
        )
        if (!persistFx) {
            // Resetting only the Compose-facing state above would leave the
            // audio thread still applying the OLD filter/echo to the new
            // track - both @Volatile fields must be pushed through here too.
            filterProcessor.setKnob(0f)
            echoProcessor.enabled = false
        }
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(displayName)
                    .build()
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playbackParameters = androidx.media3.common.PlaybackParameters(1f)

        viewModelScope.launch {
            val result = AudioAnalyzer.analyze(getApplication(), uri)
            _state.value = _state.value.copy(
                waveform = result?.waveform,
                spectral = result?.spectral,
                bpm = result?.bpm,
                key = result?.key,
                isAnalyzing = false,
            )
            // Stage 9: piggyback the library's BPM cache on this analysis
            // pass that already runs on every load - deliberately NOT a
            // proactive full-library background scan (real battery/thermal/
            // IO spike over hundreds of files); the cache fills in
            // organically as tracks get played instead. Key detection rides
            // the same cache-once policy for the same reason.
            if (result?.bpm != null) {
                TrackCacheRepository.get(getApplication()).setCachedBpm(TrackId.from(uri), result.bpm)
            }
            if (result?.key != null) {
                TrackCacheRepository.get(getApplication()).setCachedKey(TrackId.from(uri), result.key)
            }
            // Echo's on/off + division setting persists across track loads
            // (see field comment), but its sample-domain delay length is
            // tempo-relative like playbackSpeed - re-lock it to the NEW
            // track's tempo now that analysis finished, instead of silently
            // continuing to echo at the old track's spacing. If detection
            // failed (result?.bpm == null), leave the delay line at its last
            // valid length rather than snapping to 0 - killing an effect the
            // DJ deliberately turned on mid-set because analysis hasn't
            // finished yet would be a worse surprise than a stale-but-
            // audible delay time; the UI grays out the controls either way
            // per the bpm-null convention already used for loops.
            if (_state.value.echoActive) {
                setEchoDivision(_state.value.echoDivisionBeats)
            }
        }

        viewModelScope.launch {
            val art = AlbumArt.extract(getApplication(), uri)
            _state.value = _state.value.copy(albumArt = art)
        }
    }

    /** Adjusts playback tempo without touching gain - used for beat-matching
     * against another deck's BPM. 1.0 = original speed.
     *
     * Key Lock support: Media3's ExoPlayer already does real pitch-
     * independent time-stretching via its underlying Sonic audio processor -
     * PlaybackParameters(speed, pitch) with pitch held at 1f is all "Key
     * Lock" needs, no new DSP of our own. With Key Lock off, pitch and
     * tempo stay coupled (classic vinyl-style pitch-bend) by setting
     * pitch = speed, same as the single-arg PlaybackParameters(speed) this
     * replaces. */
    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        val pitch = if (_state.value.keyLockEnabled) 1f else clamped
        player.playbackParameters = androidx.media3.common.PlaybackParameters(clamped, pitch)
        _state.value = _state.value.copy(playbackSpeed = clamped)
        // Echo's tap spacing is tempo-relative (effectiveBpm() already folds
        // in this speed change) - re-target it on every speed change
        // (pitch nudge, sync-lock engaging/tracking) so taps stay beat-
        // locked. The delay line's own glide (EchoAudioProcessor.GLIDE_RATE)
        // absorbs the transition smoothly - no flush needed for a tempo
        // change alone.
        if (_state.value.echoActive) {
            setEchoDivision(_state.value.echoDivisionBeats)
        }
    }

    /** Fine pitch nudge (e.g. +/-0.001 = +/-0.1%) - the manual "ultrafine"
     * tuning knob DJs use to correct sync drift a beat-sync pass can't. */
    fun nudgeSpeed(delta: Float) {
        setPlaybackSpeed(_state.value.playbackSpeed + delta)
    }

    /** Toggles Key Lock and re-applies PlaybackParameters immediately at the
     * CURRENT speed, so pitch snaps to/from original right away rather than
     * waiting for the next speed change to take effect. */
    fun setKeyLock(enabled: Boolean) {
        _state.value = _state.value.copy(keyLockEnabled = enabled)
        val speed = _state.value.playbackSpeed
        val pitch = if (enabled) 1f else speed
        player.playbackParameters = androidx.media3.common.PlaybackParameters(speed, pitch)
    }

    /** The track's true tempo right now, after any speed adjustment -
     * what a beat-matched deck's BPM actually reads as. */
    fun effectiveBpm(): Float? {
        val base = _state.value.bpm ?: return null
        return base * _state.value.playbackSpeed
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Live RMS level (0..1), read straight off the audio thread's
     * [LevelAudioProcessor] on every call - intended for a Compose meter's
     * own polling loop (e.g. every ~66ms), not for [DeckUiState]/StateFlow,
     * which would republish the whole deck state at audio-buffer rate for a
     * single float. */
    val currentLevel: Float
        get() = levelProcessor.currentLevel

    /** Input lock toggle - see [DeckUiState.isLocked]'s doc for scope. A
     * plain state flip, not a gate itself: every call site that could
     * disturb a live deck checks isLocked before acting, this method just
     * flips the flag those checks read. */
    fun setLocked(locked: Boolean) {
        _state.value = _state.value.copy(isLocked = locked)
    }

    fun seekTo(ms: Long) = player.seekTo(ms)

    /** Channel fader value only (0..1). Actual player volume is computed
     * externally as channelFader * crossfaderFactor via [setEffectiveVolume],
     * so the mixer can combine per-deck gain with crossfader position. */
    fun setGain(gain: Float) {
        _state.value = _state.value.copy(gain = gain)
    }

    fun setEffectiveVolume(v: Float) {
        player.volume = v.coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------
    // Stage 7: FX. Real-time DSP runs in FilterAudioProcessor/EchoAudioProcessor
    // (audio thread); these methods are the UI-thread side of that boundary -
    // each one updates the Compose-facing _state AND publishes the same
    // value to the processor's @Volatile field in the same call, so the two
    // "mirrors" never drift out of sync with each other. See the processors'
    // own class doc comments for why @Volatile (not a lock) is the correct
    // and sufficient synchronization here.
    // ---------------------------------------------------------------------

    fun setEqLow(db: Float) {
        val clamped = db.coerceIn(EqAudioProcessor.MIN_DB, EqAudioProcessor.MAX_DB)
        eqProcessor.setLowDb(clamped)
        _state.value = _state.value.copy(eqLowDb = clamped)
    }

    fun setEqMid(db: Float) {
        val clamped = db.coerceIn(EqAudioProcessor.MIN_DB, EqAudioProcessor.MAX_DB)
        eqProcessor.setMidDb(clamped)
        _state.value = _state.value.copy(eqMidDb = clamped)
    }

    fun setEqHigh(db: Float) {
        val clamped = db.coerceIn(EqAudioProcessor.MIN_DB, EqAudioProcessor.MAX_DB)
        eqProcessor.setHighDb(clamped)
        _state.value = _state.value.copy(eqHighDb = clamped)
    }

    fun setFilter(value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        filterProcessor.setKnob(clamped)
        _state.value = _state.value.copy(filterValue = clamped)
    }

    /** No-op if BPM is unknown - mirrors setBeatLoop()'s effectiveBpm() ?:
     * return guard exactly, so echo can't be armed with no tempo to sync to. */
    fun toggleEcho() {
        if (_state.value.bpm == null) return
        val next = !_state.value.echoActive
        echoProcessor.enabled = next
        _state.value = _state.value.copy(echoActive = next)
    }

    /** beats is one of 0.25/0.5/1.0. Uses effectiveBpm() (pitch-adjusted),
     * same reasoning as setBeatLoop() - a beat is however long it currently
     * plays back. No-op if BPM is unknown. */
    fun setEchoDivision(beats: Float) {
        val bpm = effectiveBpm() ?: return
        val delayMs = 60_000f / bpm * beats
        val samples = (delayMs / 1000f * echoProcessor.sampleRateHz)
            .coerceIn(1f, (echoProcessor.capacitySamples - 1).toFloat())
        echoProcessor.targetDelaySamples = samples
        _state.value = _state.value.copy(echoDivisionBeats = beats)
    }

    // ---------------------------------------------------------------------
    // Cue point - single markable position with real-gear "stutter" semantics:
    // no cue yet -> tap sets one. Playing -> tap jumps back and pauses
    // ("back-cue"). Paused at an existing cue -> press-and-hold previews
    // playback from it, release snaps back ("stutter"). Gesture disambiguation
    // (press vs hold vs release) lives in the UI layer; these are primitives.
    // ---------------------------------------------------------------------

    /** True only while a previewCueStart() hold is still the reason playback
     * is running. A concurrent action that moves the playhead elsewhere
     * (e.g. a hot cue tapped with a second finger while CUE is held) clears
     * this, so the eventual previewCueRelease() knows not to snap back over
     * a jump the user made in the meantime. */
    private var previewActive = false

    /** No cue yet -> sets one here (paused or playing). No-op if no track is
     * loaded (durationMs == 0) - otherwise this would silently mark a pad/
     * cue at a meaningless position 0 before any media is ready. */
    fun setCueAtPlayhead() {
        if (_state.value.durationMs <= 0) return
        _state.value = _state.value.copy(cuePointMs = player.currentPosition)
    }

    /** Playing + cue exists -> pause and snap back to it ("back-cue"). */
    fun jumpToCueAndPause() {
        val cue = _state.value.cuePointMs ?: return
        previewActive = false
        exitLoopIfActive()
        player.pause()
        player.seekTo(cue)
    }

    /** Paused, cue exists, pointer-down -> start playing from the cue (preview). */
    fun previewCueStart() {
        val cue = _state.value.cuePointMs ?: return
        exitLoopIfActive()
        previewActive = true
        player.seekTo(cue)
        player.play()
    }

    /** Pointer-up after previewCueStart() -> pause + snap back ("stutter").
     * No-op if something else (e.g. a hot cue jump on another finger) already
     * took over playback since the preview started - see [previewActive]. */
    fun previewCueRelease() {
        if (!previewActive) return
        previewActive = false
        val cue = _state.value.cuePointMs ?: return
        player.pause()
        player.seekTo(cue)
    }

    fun clearCue() {
        _state.value = _state.value.copy(cuePointMs = null)
    }

    // ---------------------------------------------------------------------
    // Loop. Boundary detection was originally built on ExoPlayer.
    // createMessage() targeted at loopOutMs - on paper the right tool (fires
    // off the player's own render loop, not an app timer). In practice, on-
    // device testing showed the message never delivers: it's armed with its
    // target position already equal to (not ahead of) player.currentPosition
    // at that exact moment (confirmed via logging - both reads returned the
    // identical value), and PlayerMessage evidently needs to observe the
    // position actually CROSS the target from before it, not just find it
    // already there - so it silently never fires and the deck just plays
    // straight through the loop-out point. Rather than fight an API that
    // isn't behaving as documented on this device/Media3 version, this uses
    // a dedicated tight-interval coroutine watcher instead - the same proven
    // pattern the position/duration UI poll below already uses successfully,
    // just at a much finer interval since loop seams need far tighter timing
    // than a scrub-bar readout does. Only runs while a loop is actually
    // active (cancelled immediately on disarm), so it's not a standing cost.
    //
    // Precision tradeoff, stated explicitly: a 15ms tick bounds worst-case
    // detection lag to ~15ms. At 174 BPM a 1/4-beat loop is ~86ms, so that's
    // roughly an 18% timing error - audible as a faint seam click on the
    // tightest loops, not as skipping or drift. SeekParameters.EXACT (set
    // once in init) still matters independently: without it, seekTo() on a
    // compressed stream snaps to the nearest sync frame, tens of ms wide,
    // so even perfect timing would land in the wrong place.
    // ---------------------------------------------------------------------

    private var loopWatcherJob: kotlinx.coroutines.Job? = null

    private fun startLoopWatcher(outMs: Long) {
        loopWatcherJob?.cancel()
        loopWatcherJob = viewModelScope.launch {
            while (isActive) {
                val st = _state.value
                val inMs = st.loopInMs
                if (!st.loopActive || inMs == null) break
                if (player.currentPosition >= outMs) {
                    player.seekTo(inMs)
                }
                kotlinx.coroutines.delay(15)
            }
        }
    }

    private fun disarmLoop() {
        loopWatcherJob?.cancel()
        loopWatcherJob = null
    }

    fun setLoopIn() {
        if (_state.value.durationMs <= 0) return
        disarmLoop()
        // loopOutMs explicitly cleared, not left stale: redefining IN starts
        // a fresh loop measurement. Leaving a prior OUT in place let a new
        // IN pair with an old, now-inverted OUT (out < in) and canToggle only
        // checks non-null, not ordering - toggleLoop() would then seek into
        // an empty [in, out) range and arm a boundary already behind the
        // playhead, silently breaking the loop until OUT was re-pressed.
        _state.value = _state.value.copy(
            loopInMs = player.currentPosition, loopOutMs = null, loopActive = false, loopBeats = null
        )
    }

    /** Activates the loop iff IN is set and OUT lands at least MIN_LOOP_MS after it. */
    fun setLoopOut() {
        val inMs = _state.value.loopInMs ?: return
        val outMs = player.currentPosition
        if (outMs <= inMs + MIN_LOOP_MS) return
        _state.value = _state.value.copy(loopOutMs = outMs, loopActive = true, loopBeats = null)
        startLoopWatcher(outMs)
    }

    /** beats is one of 0.25/0.5/1/2/4/8. Uses effectiveBpm() (pitch-adjusted),
     * not raw state.bpm - a beat is however long it currently plays back, so
     * pitch nudges and sync-lock still produce a musically correct loop
     * length. No-op if BPM is unknown (isAnalyzing or detection failed). */
    fun setBeatLoop(beats: Float) {
        val bpm = effectiveBpm() ?: return
        val lengthMs = (60_000.0 / bpm * beats).toLong().coerceAtLeast(MIN_LOOP_MS)
        val inMs = player.currentPosition
        var outMs = inMs + lengthMs
        val dur = _state.value.durationMs
        if (dur <= 0) return
        // -5ms margin, not durationMs exactly: landing the loop-out boundary
        // precisely at end-of-media risks racing ExoPlayer's own transition
        // to STATE_ENDED, which can win and stop playback before the watcher
        // notices, leaving the deck stopped instead of looping.
        outMs = outMs.coerceAtMost(dur - 5L)
        // If the playhead is already within MIN_LOOP_MS of the clamped end
        // (e.g. the chip was tapped in literally the last few ms of the
        // track), the clamp above can push outMs to or below inMs - an
        // inverted/degenerate interval. Unlike setLoopOut, nothing else
        // validated this, so the watcher would see currentPosition >= outMs
        // as already true, seek to inMs, then immediately see it true again
        // on the very next 15ms tick - a tight stutter loop instead of a
        // musical one. Bail out instead.
        if (outMs <= inMs + MIN_LOOP_MS) return
        disarmLoop()
        _state.value = _state.value.copy(loopInMs = inMs, loopOutMs = outMs, loopActive = true, loopBeats = beats)
        startLoopWatcher(outMs)
    }

    /** Re-enable/disable an existing in/out pair without discarding the
     * points (reloop/exit, matches physical gear). Re-enabling snaps into
     * the loop if the playhead has drifted outside [in, out). */
    fun toggleLoop() {
        val st = _state.value
        val inMs = st.loopInMs ?: return
        val outMs = st.loopOutMs ?: return
        if (st.loopActive) {
            disarmLoop()
            _state.value = st.copy(loopActive = false)
        } else {
            // Defensive: every path that sets loopInMs/loopOutMs now
            // validates outMs > inMs itself, but re-check here too rather
            // than trust that invariant blindly - re-enabling a degenerate
            // pair would seek into an empty range and arm a boundary already
            // behind the playhead (silent break, same failure mode the
            // setLoopIn/setBeatLoop fixes were about).
            if (outMs <= inMs) {
                clearLoop()
                return
            }
            _state.value = st.copy(loopActive = true)
            if (player.currentPosition !in inMs until outMs) player.seekTo(inMs)
            startLoopWatcher(outMs)
        }
    }

    fun clearLoop() {
        disarmLoop()
        _state.value = _state.value.copy(loopInMs = null, loopOutMs = null, loopActive = false, loopBeats = null)
    }

    private fun exitLoopIfActive() {
        if (_state.value.loopActive) {
            disarmLoop()
            _state.value = _state.value.copy(loopActive = false)
        }
    }

    // ---------------------------------------------------------------------
    // Hot cues - a fixed bank of markable positions. Unlike the single cue
    // point (which pauses/previews), hot cues always trigger playback - the
    // real-gear distinction between the two.
    // ---------------------------------------------------------------------

    /** Empty pad -> sets it at current position. Filled pad -> jumps there
     * and starts playing if paused. */
    fun hotCueTap(index: Int) {
        val cues = _state.value.hotCues
        if (index !in cues.indices) return
        val pos = cues[index]
        if (pos == null) {
            // No-op on an empty deck (no track loaded) - otherwise this
            // marks the pad "filled" at a meaningless position 0.
            if (_state.value.durationMs <= 0) return
            val updated = cues.toMutableList().also { it[index] = player.currentPosition }
            _state.value = _state.value.copy(hotCues = updated)
        } else {
            // This jump can happen on a second finger while CUE is still
            // held for a preview (two independent gesture regions) - tell
            // previewCueRelease() not to snap back over this jump once the
            // CUE finger lifts.
            previewActive = false
            exitLoopIfActive()
            player.seekTo(pos)
            if (!player.isPlaying) player.play()
        }
    }

    fun clearHotCue(index: Int) {
        val cues = _state.value.hotCues
        if (index !in cues.indices) return
        _state.value = _state.value.copy(hotCues = cues.toMutableList().also { it[index] = null })
    }

    override fun onCleared() {
        disarmLoop()
        player.release()
        super.onCleared()
    }
}
