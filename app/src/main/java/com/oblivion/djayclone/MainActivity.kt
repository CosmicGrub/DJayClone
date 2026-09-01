@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.oblivion.djayclone

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

// Non-private (Stage 8): SettingsScreen.kt reuses these for visual
// consistency with the mixer's deck coloring.
val AccentA = Color(0xFF3DDC97) // green channel, like djay's deck coloring
val AccentB = Color(0xFFFF5A7A) // pink/red channel
private const val HALF_PI = Math.PI / 2.0

// Stage 9: replaces the old `var showSettings: Boolean` - a third screen
// made two independent booleans able to represent an invalid state (both
// "open" at once); an enum makes that unrepresentable by construction while
// keeping the exact same boolean-swap philosophy (no Navigation Compose -
// see MainActivity.onCreate's setContent for why that's still not warranted
// at three flat, one-level-deep destinations).
private enum class AppScreen { MIXER, LIBRARY, SETTINGS }

class MainActivity : ComponentActivity() {

    // Two independent decks, same ViewModel class - must be fetched with
    // distinct keys or the default-keyed provider would hand back the same
    // instance for both (default key is just the class's canonical name).
    private val viewModelProvider by lazy {
        androidx.lifecycle.ViewModelProvider(
            this,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )
    }
    private val deckA: DeckViewModel by lazy { viewModelProvider["deckA", DeckViewModel::class.java] }
    private val deckB: DeckViewModel by lazy { viewModelProvider["deckB", DeckViewModel::class.java] }
    // Default key - one recording session for the whole mix, not per-deck.
    private val recording: RecordingViewModel by lazy { viewModelProvider[RecordingViewModel::class.java] }

    // Stage 8: shared singleton, not per-deck - both DeckViewModels already
    // fetch the same instance via SettingsRepository.get(application).
    private val settingsRepo by lazy { SettingsRepository.get(application) }

    // Stage 9: default key, one library for the whole app (matches
    // `recording` above) - not per-deck.
    private val libraryViewModel: LibraryViewModel by lazy { viewModelProvider[LibraryViewModel::class.java] }

    // Stage 9: not a singleton - cheap to construct, holds no state of its
    // own (see TrackLibraryRepository's class doc). Used here only for the
    // fire-and-forget registerManualImport() call inside the per-deck SAF
    // launcher below; the Library screen's own UI state goes through
    // libraryViewModel instead.
    private val libraryRepo by lazy { TrackLibraryRepository(application) }

    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // Stage 6 recording consent chain: RECORD_AUDIO -> (API 33+) POST_NOTIFICATIONS
    // -> MediaProjection system consent dialog. Each step only runs if the
    // previous one succeeded/was already granted; declining any step lands
    // on RecordingViewModel.onPermissionDenied() rather than silently
    // stalling with no feedback.
    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestNotificationPermissionThenProjection()
            else recording.onPermissionDenied("Recording needs microphone-level permission to capture the mix.")
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Proceed regardless of the result - POST_NOTIFICATIONS only
            // gates the "Recording..." status notification's visibility,
            // never the recording itself.
            launchProjectionRequest()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            recording.onProjectionResult(result.resultCode, result.data)
        }

    // Stage 9: READ_MEDIA_AUDIO (API33+) / READ_EXTERNAL_STORAGE (API26-32,
    // maxSdk=32 in the manifest) were declared in the manifest since before
    // this stage but never actually requested at runtime anywhere - this is
    // that missing request flow, mirroring the RECORD_AUDIO pattern above
    // exactly. Manual/SAF-imported rows work regardless of this permission
    // (OpenDocument/OpenMultipleDocuments don't depend on it at all), so a
    // denial only affects the MediaStore-sourced half of the library.
    private val libraryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            libraryViewModel.setPermissionDenied(!granted)
            if (granted) libraryViewModel.refresh()
        }

    // Stage 9: the Library screen's own bulk "Add from files" action -
    // distinct from the existing per-deck registerImportLauncher below,
    // which still loads straight onto a deck. This one only ever registers
    // into the manual-import cache; it never touches either deck, so
    // batch-importing a folder's worth of tracks while browsing can't
    // disturb whatever's already loaded and playing mid-set.
    private val addToLibraryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            uris.forEach { uri ->
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            libraryViewModel.onManualImportPicked(uris)
        }

    private fun libraryPermissionName(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Called on every entry to the Library screen (see LibraryScreen's
     * onRequestPermission callback) - checks first rather than blindly
     * re-launching the system dialog every time, matching how the rest of
     * this app's permission flows behave. */
    private fun ensureLibraryPermission() {
        val perm = libraryPermissionName()
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            libraryViewModel.setPermissionDenied(false)
            libraryViewModel.refresh()
        } else {
            libraryPermissionLauncher.launch(perm)
        }
    }

    private fun startRecordingFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionThenProjection()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermissionThenProjection() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjectionRequest()
        }
    }

    private fun launchProjectionRequest() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // On API 34+, request the "entire default display" config explicitly
        // rather than the bare createScreenCaptureIntent(). Without it, this
        // device's picker defaults to "share one app" and forces picking an
        // app from the FULL alphabetical app list (not just recents) -
        // confirmed on-device: selecting DJayClone itself from that picker
        // relaunches its task and wipes in-memory deck state instead of
        // returning a projection result. We only ever want audio (never the
        // video/app-picker part of this API at all), so skip that entire
        // path - the resulting dialog is a plain "Start recording?" prompt.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pickForA = registerImportLauncher(deckA)
        val pickForB = registerImportLauncher(deckB)

        handleViewIntent(intent)
        requestAudioFocus()

        setContent {
            // Stage 10a: the two adaptive-layout signals, computed once here
            // and threaded down as CompositionLocals - see DjAdaptive.kt for
            // why they're kept separate. Zero UI change yet: nothing below
            // consumes these two values today, this step only wires and
            // logs ground-truth numbers (widthSizeClass/heightSizeClass/
            // FoldingFeature.state) on real hardware, replacing every
            // estimate the Stage 10 pitch flagged as unverified - before
            // Stage 10a's later steps build anything that depends on them.
            val djLayoutClass = rememberDjLayoutClass(this)
            val djPosture = rememberDjPosture(this)
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDjLayoutClass provides djLayoutClass,
                LocalDjPosture provides djPosture,
            ) {
            MaterialTheme(colorScheme = darkColorScheme(primary = AccentA)) {
                Surface(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    // Stage 8/9: enum-valued screen-swap, not Navigation
                    // Compose - this stays a flat set of destinations, each
                    // one level deep and always returning straight to
                    // Mixer, with no deep links or typed nav arguments
                    // needed (the one piece of cross-screen data, which
                    // deck to load a tapped library track into, is a plain
                    // callback param). Both decks keep playing underneath
                    // regardless of which screen is shown, since they live
                    // in Activity-scoped ViewModels independent of it.
                    var screen by remember { mutableStateOf(AppScreen.MIXER) }
                    when (screen) {
                        AppScreen.SETTINGS -> {
                            BackHandler { screen = AppScreen.MIXER }
                            val stateA by deckA.state.collectAsState()
                            val stateB by deckB.state.collectAsState()
                            SettingsScreen(
                                settingsRepo = settingsRepo,
                                deckAHotCues = stateA.hotCues,
                                deckBHotCues = stateB.hotCues,
                                onBack = { screen = AppScreen.MIXER },
                            )
                        }
                        AppScreen.LIBRARY -> {
                            BackHandler { screen = AppScreen.MIXER }
                            LibraryScreen(
                                library = libraryViewModel,
                                onLoadTrack = { track, deck ->
                                    (if (deck == Deck.A) deckA else deckB).loadTrack(track.uri, track.title)
                                },
                                onAddFromFiles = { addToLibraryLauncher.launch(arrayOf("audio/*")) },
                                onRequestPermission = { ensureLibraryPermission() },
                                onBack = { screen = AppScreen.MIXER },
                            )
                        }
                        AppScreen.MIXER -> {
                            MixerScreen(
                                deckA = deckA,
                                deckB = deckB,
                                recording = recording,
                                settingsRepo = settingsRepo,
                                onImportA = { pickForA.launch(arrayOf("audio/*")) },
                                onImportB = { pickForB.launch(arrayOf("audio/*")) },
                                onStartRecording = { startRecordingFlow() },
                                onOpenSettings = { screen = AppScreen.SETTINGS },
                                onOpenLibrary = { screen = AppScreen.LIBRARY },
                                // Stage 10b: identical wiring to the LIBRARY
                                // branch below, on purpose - the Expanded
                                // arrangement's sidebar is the same feature
                                // as the full-screen Library, just shown in
                                // a different place, so it drives the same
                                // deck-loading and manual-import side effects.
                                libraryViewModel = libraryViewModel,
                                onLoadTrack = { track, deck ->
                                    (if (deck == Deck.A) deckA else deckB).loadTrack(track.uri, track.title)
                                },
                                onAddFromFiles = { addToLibraryLauncher.launch(arrayOf("audio/*")) },
                                onRequestLibraryPermission = { ensureLibraryPermission() },
                            )
                        }
                    }
                }
            }
            } // CompositionLocalProvider
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onDestroy() {
        audioFocusRequest?.let {
            (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(it)
        }
        super.onDestroy()
    }

    /** Requested ONCE for the whole app, covering both decks together - not
     * per-deck. Each DeckViewModel's ExoPlayer has handleAudioFocus=false
     * specifically so this is the only thing managing focus; two independent
     * per-instance focus requests would make starting Deck B steal focus
     * from (and pause/duck) Deck A, breaking simultaneous two-deck mixing.
     * On a real interruption (phone call, another app taking over) both
     * decks pause together, same as a single-player app would - correct
     * behavior for the whole mix, and required for well-behaved audio
     * routing on Bluetooth/Android Auto/car head units, which generally
     * expect an app to participate properly in audio focus. */
    private fun requestAudioFocus() {
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // Pause both decks rather than duck - a DJ mixer
                        // partially ducking under a notification sound would
                        // be a worse experience than just pausing outright,
                        // and there's no safe "auto-resume" moment to pick.
                        if (deckA.player.isPlaying) deckA.player.pause()
                        if (deckB.player.isPlaying) deckB.player.pause()
                    }
                    // AUDIOFOCUS_GAIN: deliberately no auto-resume - the DJ
                    // decides when to resume, not the OS.
                }
            }
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun handleViewIntent(intent: android.content.Intent?) {
        val uri = intent?.takeIf { it.action == android.content.Intent.ACTION_VIEW }?.data ?: return
        // "deck" extra lets adb/test tooling target Deck B; real "Open with"
        // launches from other apps always land on Deck A.
        val target = if (intent.getStringExtra("deck") == "B") deckB else deckA
        target.loadTrack(uri, queryDisplayName(uri))
    }

    private fun registerImportLauncher(deck: DeckViewModel) =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                // Unchanged, still instant - a live-mixing DJ needs this
                // one-tap load to stay exactly as fast as it's always been.
                deck.loadTrack(uri, queryDisplayName(uri))
                // Stage 9: every SAF import also silently grows the library
                // going forward, whether or not the DJ ever opens it -
                // fire-and-forget, no refresh needed here since the Library
                // screen re-queries on its own next entry.
                libraryRepo.registerManualImport(uri)
            }
        }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return uri.lastPathSegment
        cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return uri.lastPathSegment
    }
}

@Composable
fun MixerScreen(
    deckA: DeckViewModel,
    deckB: DeckViewModel,
    recording: RecordingViewModel,
    settingsRepo: SettingsRepository,
    onImportA: () -> Unit,
    onImportB: () -> Unit,
    onStartRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    // Stage 10b: only consumed by MixerArrangementExpanded's LibrarySidebar
    // today (Compact/Medium still route to the full-screen Library via
    // onOpenLibrary above) - threaded through here rather than having
    // MixerArrangement reach back up to MainActivity itself, matching how
    // every other cross-cutting dependency (settingsRepo, recording) already
    // flows down through this one entry point.
    libraryViewModel: LibraryViewModel,
    onLoadTrack: (LibraryTrack, Deck) -> Unit,
    onAddFromFiles: () -> Unit,
    onRequestLibraryPermission: () -> Unit,
) {
    val stateA by deckA.state.collectAsState()
    val stateB by deckB.state.collectAsState()
    val recState by recording.state.collectAsState()
    val settings by settingsRepo.settings.collectAsState()

    // Seeded once from the persisted default at first composition, not
    // retroactively re-applied if the setting changes later mid-session -
    // matches how a physical mixer's crossfader doesn't jump on its own.
    var crossfader by remember { mutableStateOf(settings.defaultCrossfaderPosition) } // 0 = full A, 1 = full B

    // Equal-power crossfade combined with each deck's own channel fader.
    LaunchedEffect(crossfader, stateA.gain, stateB.gain) {
        val x = crossfader.toDouble()
        val factorA = cos(x * HALF_PI).toFloat()
        val factorB = sin(x * HALF_PI).toFloat()
        deckA.setEffectiveVolume(stateA.gain * factorA)
        deckB.setEffectiveVolume(stateB.gain * factorB)
    }

    // Continuous sync lock: once engaged, the follower deck keeps tracking
    // the leader's *effective* BPM (detected tempo x its own pitch nudges),
    // not just a one-time snap - so fine-nudging the leader afterwards keeps
    // both decks locked instead of drifting back out of sync.
    var syncLock by remember { mutableStateOf<String?>(null) } // "B_FOLLOWS_A" | "A_FOLLOWS_B" | null

    // Tempo-Sync v1: AUTO detects a half/double-time relationship instead
    // of always forcing 1:1 - see TempoSyncMath's doc for the real
    // motivation (a 140/70 BPM pair used to fight the old unity-only lock).
    // The other three modes force that ratio regardless of measurement,
    // for a deliberate half-time drop that isn't really a 2:1 relationship.
    var syncRatioMode by remember { mutableStateOf(SyncRatioMode.AUTO) }

    LaunchedEffect(syncLock, syncRatioMode, stateA.bpm, stateB.bpm, stateA.playbackSpeed, stateB.playbackSpeed) {
        val bpmA = stateA.bpm
        val bpmB = stateB.bpm
        if (bpmA == null || bpmB == null) return@LaunchedEffect

        fun resolveRatio(leaderEffectiveBpm: Float, followerEffectiveBpm: Float): TempoSyncMath.SyncRatio =
            when (syncRatioMode) {
                SyncRatioMode.AUTO -> TempoSyncMath.detectRatio(
                    leaderEffectiveBpm, followerEffectiveBpm,
                    tolerance = settings.tempoSyncConfig.ratioToleranceFraction,
                )?.ratio ?: TempoSyncMath.SyncRatio.UNITY
                SyncRatioMode.UNITY -> TempoSyncMath.SyncRatio.UNITY
                SyncRatioMode.DOUBLE_TIME -> TempoSyncMath.SyncRatio.DOUBLE_TIME
                SyncRatioMode.HALF_TIME -> TempoSyncMath.SyncRatio.HALF_TIME
            }

        when (syncLock) {
            "B_FOLLOWS_A" -> {
                val leaderEffective = bpmA * stateA.playbackSpeed
                val ratio = resolveRatio(leaderEffective, bpmB * stateB.playbackSpeed)
                val target = TempoSyncMath.targetPlaybackSpeed(leaderEffective, bpmB, ratio)
                if (kotlin.math.abs(target - stateB.playbackSpeed) > 0.0005f) deckB.setPlaybackSpeed(target)
            }
            "A_FOLLOWS_B" -> {
                val leaderEffective = bpmB * stateB.playbackSpeed
                val ratio = resolveRatio(leaderEffective, bpmA * stateA.playbackSpeed)
                val target = TempoSyncMath.targetPlaybackSpeed(leaderEffective, bpmA, ratio)
                if (kotlin.math.abs(target - stateA.playbackSpeed) > 0.0005f) deckA.setPlaybackSpeed(target)
            }
        }
    }

    // Stage 10b: "DeX keyboard shortcuts (space, arrow-key nudge)" per the
    // Stage 10 pitch - deliberately Deck A only, a decision made explicitly
    // rather than guessed: the pitch names one spacebar action and one
    // arrow-key pair, and this is a two-deck app with no existing "focused
    // deck" concept to disambiguate which deck an unqualified Space should
    // target. Extending to both decks would mean inventing 2-3 more
    // bindings (e.g. Enter for Deck B play, a second arrow pair or modifier
    // for Deck B nudge) the pitch never named - a materially bigger feature
    // than what was scoped. Deck A only is the literal, minimal reading:
    // unambiguous, and every binding here traces to something the pitch
    // actually said. Lives at MixerScreen's level, not inside any one
    // arrangement, for the same reason the crossfade/sync effects do: it's
    // not visual-arrangement-specific, it's a property of "the mixer is on
    // screen" regardless of which dispatcher branch is currently rendering
    // it.
    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyFocusRequester.requestFocus() }

    // Stage 10a: MixerScreen keeps owning state/effects (crossfade math,
    // sync-lock math) - only the visual arrangement below this point is
    // layout-class-dependent, so only that part moves into the dispatcher.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(keyFocusRequester)
            .focusable()
            // onPreviewKeyEvent (capture phase, root-to-leaf) rather than
            // onKeyEvent (bubble phase, leaf-to-root): this Box must see
            // Space/arrow presses even after focus has moved to a child -
            // tapping the crossfader Slider or any button - not only while
            // this exact Box holds focus. onKeyEvent alone would silently
            // stop working the moment any child control was touched.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val coarseStep = settings.nudgeCoarsePercent
                when (event.key) {
                    Key.Spacebar -> { deckA.togglePlay(); true }
                    Key.DirectionLeft -> { deckA.nudgeSpeed(-coarseStep); true }
                    Key.DirectionRight -> { deckA.nudgeSpeed(coarseStep); true }
                    else -> false
                }
            }
    ) {
        MixerArrangement(
            deckA = deckA,
            deckB = deckB,
            stateA = stateA,
            stateB = stateB,
            settings = settings,
            recState = recState,
            recording = recording,
            crossfader = crossfader,
            onCrossfaderChange = { crossfader = it },
            syncLock = syncLock,
            onSyncLockChange = { syncLock = it },
            syncRatioMode = syncRatioMode,
            onSyncRatioModeChange = { syncRatioMode = it },
            onImportA = onImportA,
            onImportB = onImportB,
            onStartRecording = onStartRecording,
            onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
            libraryViewModel = libraryViewModel,
            onLoadTrack = onLoadTrack,
            onAddFromFiles = onAddFromFiles,
            onRequestLibraryPermission = onRequestLibraryPermission,
        )
    }
}

/**
 * Stage 10a/10b: the whole-screen arrangement dispatcher. Reads only
 * [LocalDjLayoutClass] - not [LocalDjPosture] yet. TableTop-specific
 * arrangement is Stage 10c's job (see DjAdaptive.kt's doc comment); this
 * dispatcher's job is a real 3-way split on width alone, and DjPosture is
 * still Flat-only-consumed at this stage regardless of what it reports.
 *
 * MEDIUM and EXPANDED no longer share one treatment - Stage 10a's own
 * comment here originally said they would "until Stage 10b," and this is
 * that stage. The reason they diverge for real: Stage 10b's center mix
 * column (200dp) plus library sidebar (280dp) are fixed-width zones that
 * eat 480dp before either deck column sees a pixel. At the true 840dp
 * Expanded floor that still leaves ~180dp/deck - right at Compact's own
 * proven-working minimum (its ~344dp cover screen splits two decks across
 * ~172dp each today). Below 840dp, that same fixed-width math gets
 * progressively worse - at the Fold's ~690dp portrait-book width it would
 * leave under 100dp per deck, nowhere near workable. So MEDIUM keeps 10a's
 * original wider-deck-only treatment (renamed [MixerArrangementMedium]) and
 * only true EXPANDED gets the center column + sidebar. This is a width-
 * budget conclusion reached by computing it, not a hunch - see the two
 * fixed widths' doc comments on [CenterMixColumn] and [LibrarySidebar] in
 * LibraryScreen.kt for the same math from each component's own side.
 */
@Composable
private fun MixerArrangement(
    deckA: DeckViewModel,
    deckB: DeckViewModel,
    stateA: DeckUiState,
    stateB: DeckUiState,
    settings: AppSettings,
    recState: RecordingUiState,
    recording: RecordingViewModel,
    crossfader: Float,
    onCrossfaderChange: (Float) -> Unit,
    syncLock: String?,
    onSyncLockChange: (String?) -> Unit,
    syncRatioMode: SyncRatioMode,
    onSyncRatioModeChange: (SyncRatioMode) -> Unit,
    onImportA: () -> Unit,
    onImportB: () -> Unit,
    onStartRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    libraryViewModel: LibraryViewModel,
    onLoadTrack: (LibraryTrack, Deck) -> Unit,
    onAddFromFiles: () -> Unit,
    onRequestLibraryPermission: () -> Unit,
) {
    when (LocalDjLayoutClass.current) {
        DjLayoutClass.COMPACT -> MixerArrangementCompact(
            deckA = deckA, deckB = deckB, stateA = stateA, stateB = stateB,
            settings = settings, recState = recState, recording = recording,
            crossfader = crossfader, onCrossfaderChange = onCrossfaderChange,
            syncLock = syncLock, onSyncLockChange = onSyncLockChange,
            syncRatioMode = syncRatioMode, onSyncRatioModeChange = onSyncRatioModeChange,
            onImportA = onImportA, onImportB = onImportB,
            onStartRecording = onStartRecording, onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
        )
        DjLayoutClass.MEDIUM -> MixerArrangementMedium(
            deckA = deckA, deckB = deckB, stateA = stateA, stateB = stateB,
            settings = settings, recState = recState, recording = recording,
            crossfader = crossfader, onCrossfaderChange = onCrossfaderChange,
            syncLock = syncLock, onSyncLockChange = onSyncLockChange,
            syncRatioMode = syncRatioMode, onSyncRatioModeChange = onSyncRatioModeChange,
            onImportA = onImportA, onImportB = onImportB,
            onStartRecording = onStartRecording, onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
        )
        DjLayoutClass.EXPANDED -> MixerArrangementExpanded(
            deckA = deckA, deckB = deckB, stateA = stateA, stateB = stateB,
            settings = settings, recState = recState, recording = recording,
            crossfader = crossfader, onCrossfaderChange = onCrossfaderChange,
            syncLock = syncLock, onSyncLockChange = onSyncLockChange,
            syncRatioMode = syncRatioMode, onSyncRatioModeChange = onSyncRatioModeChange,
            onImportA = onImportA, onImportB = onImportB,
            onStartRecording = onStartRecording, onOpenSettings = onOpenSettings,
            libraryViewModel = libraryViewModel, onLoadTrack = onLoadTrack,
            onAddFromFiles = onAddFromFiles, onRequestLibraryPermission = onRequestLibraryPermission,
        )
    }
}

/**
 * Stage 10a: verbatim extraction of the pre-Stage-10 MixerScreen body - a
 * pure refactor, zero intended behavior/pixel change from what shipped
 * through Stage 9. This is the screenshot-diff baseline: the cover-screen
 * (Compact) render must be byte-for-byte identical to every prior stage's
 * screenshots after this extraction, before anything Expanded-specific gets
 * built on top of it.
 */
@Composable
private fun MixerArrangementCompact(
    deckA: DeckViewModel,
    deckB: DeckViewModel,
    stateA: DeckUiState,
    stateB: DeckUiState,
    settings: AppSettings,
    recState: RecordingUiState,
    recording: RecordingViewModel,
    crossfader: Float,
    onCrossfaderChange: (Float) -> Unit,
    syncLock: String?,
    onSyncLockChange: (String?) -> Unit,
    syncRatioMode: SyncRatioMode,
    onSyncRatioModeChange: (SyncRatioMode) -> Unit,
    onImportA: () -> Unit,
    onImportB: () -> Unit,
    onStartRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "DJayClone",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
            Row {
                IconButton(onClick = onOpenLibrary) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = "Library", tint = Color.Gray)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.Gray)
                }
            }
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DeckColumnCompact(
                label = "DECK A",
                accent = AccentA,
                state = stateA,
                deck = deckA,
                settings = settings,
                onImport = onImportA,
                modifier = Modifier.weight(1f)
            )
            DeckColumnCompact(
                label = "DECK B",
                accent = AccentB,
                state = stateB,
                deck = deckB,
                settings = settings,
                onImport = onImportB,
                modifier = Modifier.weight(1f)
            )
        }

        // Beat-sync — toggles a continuous lock, not a one-time snap.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val canSync = stateA.bpm != null && stateB.bpm != null
            val tolerance = settings.tempoSyncConfig.ratioToleranceFraction
            TextButton(
                onClick = { onSyncLockChange(if (syncLock == "B_FOLLOWS_A") null else "B_FOLLOWS_A") },
                enabled = canSync,
                modifier = Modifier.weight(1f)
            ) {
                val suffix = if (syncLock == "B_FOLLOWS_A") {
                    syncRatioSuffix(stateA.bpm?.times(stateA.playbackSpeed), stateB.bpm?.times(stateB.playbackSpeed), syncRatioMode, tolerance)
                } else ""
                Text(
                    if (syncLock == "B_FOLLOWS_A") "🔒 B→A$suffix" else "SYNC B→A",
                    color = if (!canSync) Color.DarkGray else if (syncLock == "B_FOLLOWS_A") Color.White else AccentB,
                    maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall
                )
            }

            TextButton(
                onClick = {
                    onSyncLockChange(null)
                    deckA.setPlaybackSpeed(1f)
                    deckB.setPlaybackSpeed(1f)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "RESET", color = Color.Gray,
                    maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall
                )
            }

            TextButton(
                onClick = { onSyncLockChange(if (syncLock == "A_FOLLOWS_B") null else "A_FOLLOWS_B") },
                enabled = canSync,
                modifier = Modifier.weight(1f)
            ) {
                val suffix = if (syncLock == "A_FOLLOWS_B") {
                    syncRatioSuffix(stateB.bpm?.times(stateB.playbackSpeed), stateA.bpm?.times(stateA.playbackSpeed), syncRatioMode, tolerance)
                } else ""
                Text(
                    if (syncLock == "A_FOLLOWS_B") "🔒 A→B$suffix" else "SYNC A→B",
                    color = if (!canSync) Color.DarkGray else if (syncLock == "A_FOLLOWS_B") Color.White else AccentA,
                    maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall
                )
            }
        }
        SyncRatioModeToggle(mode = syncRatioMode, onModeChange = onSyncRatioModeChange, modifier = Modifier.fillMaxWidth())

        // Mix recording — sits with the other mix-level controls (sync,
        // crossfader), not tucked into either deck. Always visible, not
        // collapsed like Stage 5's per-deck CUE·LOOP·PADS panel: unlike
        // that panel (large, secondary, per-deck detail), this is a single
        // compact row and the app's only entry point to the whole feature -
        // hiding it behind a disclosure toggle would bury it entirely.
        RecordRow(state = recState, recording = recording, onStart = onStartRecording)

        // Crossfader — the signature DJ-mixer control.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("A", color = AccentA, style = MaterialTheme.typography.labelLarge)
                Text("CROSSFADER", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                Text("B", color = AccentB, style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = crossfader,
                onValueChange = onCrossfaderChange,
                thumb = { CrossfaderThumb() },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.8f),
                    inactiveTrackColor = Color.DarkGray,
                )
            )
        }
    }
}

/**
 * Stage 10a, renamed in 10b: this is 10a's original "Expanded" construction,
 * unchanged in content - only its name and its dispatcher slot moved, once
 * Stage 10b's width math (see [MixerArrangement]'s doc) showed it has to
 * keep serving MEDIUM on its own rather than being shared with a real
 * >=840dp EXPANDED that now carries a center column and a library sidebar.
 * Free to diverge from Compact however this width class benefits from,
 * unlike the verbatim Compact extraction. Deliberately kept as its own
 * largely-parallel top-level function rather than sharing header/sync/
 * crossfader sub-pieces with MixerArrangementCompact - see the original
 * Stage 10a reasoning, still true: a shared-subcomposable seam built for
 * content that's still identical is just indirection to unwind later. The
 * differences from Compact stay narrow and isolated to the deck row: wider
 * columns (via DeckColumnExpanded, not via changing this Row's own
 * spacing/weights), the CUE·LOOP·PADS·FX panel default-open, and a taller
 * waveform - everything else here is intentionally identical to Compact.
 */
@Composable
private fun MixerArrangementMedium(
    deckA: DeckViewModel,
    deckB: DeckViewModel,
    stateA: DeckUiState,
    stateB: DeckUiState,
    settings: AppSettings,
    recState: RecordingUiState,
    recording: RecordingViewModel,
    crossfader: Float,
    onCrossfaderChange: (Float) -> Unit,
    syncLock: String?,
    onSyncLockChange: (String?) -> Unit,
    syncRatioMode: SyncRatioMode,
    onSyncRatioModeChange: (SyncRatioMode) -> Unit,
    onImportA: () -> Unit,
    onImportB: () -> Unit,
    onStartRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "DJayClone",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
            Row {
                IconButton(onClick = onOpenLibrary) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = "Library", tint = Color.Gray)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.Gray)
                }
            }
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            DeckColumnExpanded(
                label = "DECK A",
                accent = AccentA,
                state = stateA,
                deck = deckA,
                settings = settings,
                onImport = onImportA,
                modifier = Modifier.weight(1f)
            )
            DeckColumnExpanded(
                label = "DECK B",
                accent = AccentB,
                state = stateB,
                deck = deckB,
                settings = settings,
                onImport = onImportB,
                modifier = Modifier.weight(1f)
            )
        }

        // Beat-sync — toggles a continuous lock, not a one-time snap.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val canSync = stateA.bpm != null && stateB.bpm != null
            val tolerance = settings.tempoSyncConfig.ratioToleranceFraction
            TextButton(
                onClick = { onSyncLockChange(if (syncLock == "B_FOLLOWS_A") null else "B_FOLLOWS_A") },
                enabled = canSync,
                modifier = Modifier.weight(1f)
            ) {
                val suffix = if (syncLock == "B_FOLLOWS_A") {
                    syncRatioSuffix(stateA.bpm?.times(stateA.playbackSpeed), stateB.bpm?.times(stateB.playbackSpeed), syncRatioMode, tolerance)
                } else ""
                Text(
                    if (syncLock == "B_FOLLOWS_A") "🔒 B→A$suffix" else "SYNC B→A",
                    color = if (!canSync) Color.DarkGray else if (syncLock == "B_FOLLOWS_A") Color.White else AccentB,
                    maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall
                )
            }

            TextButton(
                onClick = {
                    onSyncLockChange(null)
                    deckA.setPlaybackSpeed(1f)
                    deckB.setPlaybackSpeed(1f)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "RESET", color = Color.Gray,
                    maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall
                )
            }

            TextButton(
                onClick = { onSyncLockChange(if (syncLock == "A_FOLLOWS_B") null else "A_FOLLOWS_B") },
                enabled = canSync,
                modifier = Modifier.weight(1f)
            ) {
                val suffix = if (syncLock == "A_FOLLOWS_B") {
                    syncRatioSuffix(stateB.bpm?.times(stateB.playbackSpeed), stateA.bpm?.times(stateA.playbackSpeed), syncRatioMode, tolerance)
                } else ""
                Text(
                    if (syncLock == "A_FOLLOWS_B") "🔒 A→B$suffix" else "SYNC A→B",
                    color = if (!canSync) Color.DarkGray else if (syncLock == "A_FOLLOWS_B") Color.White else AccentA,
                    maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall
                )
            }
        }
        SyncRatioModeToggle(mode = syncRatioMode, onModeChange = onSyncRatioModeChange, modifier = Modifier.fillMaxWidth())

        RecordRow(state = recState, recording = recording, onStart = onStartRecording)

        // Crossfader — the signature DJ-mixer control.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("A", color = AccentA, style = MaterialTheme.typography.labelLarge)
                Text("CROSSFADER", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                Text("B", color = AccentB, style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = crossfader,
                onValueChange = onCrossfaderChange,
                thumb = { CrossfaderThumb() },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.8f),
                    inactiveTrackColor = Color.DarkGray,
                )
            )
        }
    }
}

/**
 * Stage 10b: the real >=840dp arrangement - "fill the space" per the Stage
 * 10 pitch. Two changes from [MixerArrangementMedium], both named directly
 * in the pitch: the crossfader/sync/record group relocates out from below
 * the deck row into a [CenterMixColumn] sitting between the two decks (a
 * real physical two-deck controller's center channel strip is the reference
 * shape - see CenterMixColumn's own doc for why it restacks those controls
 * vertically instead of keeping their Compact/Medium horizontal layout),
 * and a persistent [LibrarySidebar] fills the dead space identified in the
 * pitch's own tablet finding - browsing/queuing the next track without
 * leaving the mixer. The header's Library icon is dropped here on purpose:
 * with the sidebar always visible, a button that navigates to the same
 * content full-screen would be pure redundancy, not a real second path.
 */
@Composable
private fun MixerArrangementExpanded(
    deckA: DeckViewModel,
    deckB: DeckViewModel,
    stateA: DeckUiState,
    stateB: DeckUiState,
    settings: AppSettings,
    recState: RecordingUiState,
    recording: RecordingViewModel,
    crossfader: Float,
    onCrossfaderChange: (Float) -> Unit,
    syncLock: String?,
    onSyncLockChange: (String?) -> Unit,
    syncRatioMode: SyncRatioMode,
    onSyncRatioModeChange: (SyncRatioMode) -> Unit,
    onImportA: () -> Unit,
    onImportB: () -> Unit,
    onStartRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    libraryViewModel: LibraryViewModel,
    onLoadTrack: (LibraryTrack, Deck) -> Unit,
    onAddFromFiles: () -> Unit,
    onRequestLibraryPermission: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "DJayClone",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.Gray)
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                DeckColumnExpanded(
                    label = "DECK A",
                    accent = AccentA,
                    state = stateA,
                    deck = deckA,
                    settings = settings,
                    onImport = onImportA,
                    modifier = Modifier.weight(1f)
                )
                CenterMixColumn(
                    deckA = deckA,
                    deckB = deckB,
                    stateA = stateA,
                    stateB = stateB,
                    recState = recState,
                    recording = recording,
                    crossfader = crossfader,
                    onCrossfaderChange = onCrossfaderChange,
                    syncLock = syncLock,
                    onSyncLockChange = onSyncLockChange,
                    syncRatioMode = syncRatioMode,
                    onSyncRatioModeChange = onSyncRatioModeChange,
                    tempoSyncTolerance = settings.tempoSyncConfig.ratioToleranceFraction,
                    onStartRecording = onStartRecording,
                )
                DeckColumnExpanded(
                    label = "DECK B",
                    accent = AccentB,
                    state = stateB,
                    deck = deckB,
                    settings = settings,
                    onImport = onImportB,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        LibrarySidebar(
            library = libraryViewModel,
            onLoadTrack = onLoadTrack,
            onAddFromFiles = onAddFromFiles,
            onRequestPermission = onRequestLibraryPermission,
        )
    }
}

/**
 * Stage 10b: the crossfader/sync/record group, relocated between the two
 * decks per the Stage 10 pitch - a real two-deck DJ controller's center
 * channel strip is the reference shape, not an arbitrary rearrangement.
 * Fixed at 200dp: at the true 840dp Expanded floor, 200dp (this) + 280dp
 * ([LibrarySidebar]) leaves 360dp split across both deck columns - 180dp
 * each, just above the ~172dp/deck the Compact cover-screen layout already
 * proves works today. That margin is the actual reason this stays a fixed
 * width instead of weight(1f): a flexible center column would eat further
 * into the one width budget that already has the least slack in the whole
 * arrangement.
 *
 * The sync buttons restack vertically here (one per row) rather than
 * keeping Compact/Medium's horizontal Row(weight(1f)x3) - at Compact's own
 * ~344dp cover-screen width each button already gets only ~115dp; splitting
 * three ways again inside this column's 200dp would give each under 70dp,
 * well under that proven floor. Stacked, each button gets the column's full
 * width instead.
 */
@Composable
private fun CenterMixColumn(
    deckA: DeckViewModel,
    deckB: DeckViewModel,
    stateA: DeckUiState,
    stateB: DeckUiState,
    recState: RecordingUiState,
    recording: RecordingViewModel,
    crossfader: Float,
    onCrossfaderChange: (Float) -> Unit,
    syncLock: String?,
    onSyncLockChange: (String?) -> Unit,
    syncRatioMode: SyncRatioMode,
    onSyncRatioModeChange: (SyncRatioMode) -> Unit,
    tempoSyncTolerance: Float,
    onStartRecording: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.Center
    ) {
        // VU meter - one bar per deck, reading real audio-thread RMS level
        // via LevelAudioProcessor, not a decorative animation. Lives above
        // the sync buttons, matching where a real two-deck mixer's center
        // channel strip puts its level meters.
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            LevelMeter(deck = deckA, color = AccentA, modifier = Modifier.weight(1f).fillMaxHeight())
            LevelMeter(deck = deckB, color = AccentB, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(16.dp))

        val canSync = stateA.bpm != null && stateB.bpm != null

        TextButton(
            onClick = { onSyncLockChange(if (syncLock == "B_FOLLOWS_A") null else "B_FOLLOWS_A") },
            enabled = canSync,
            modifier = Modifier.fillMaxWidth()
        ) {
            val suffix = if (syncLock == "B_FOLLOWS_A") {
                syncRatioSuffix(stateA.bpm?.times(stateA.playbackSpeed), stateB.bpm?.times(stateB.playbackSpeed), syncRatioMode, tempoSyncTolerance)
            } else ""
            Text(
                if (syncLock == "B_FOLLOWS_A") "🔒 SYNC B→A$suffix" else "SYNC B→A",
                color = if (!canSync) Color.DarkGray else if (syncLock == "B_FOLLOWS_A") Color.White else AccentB,
                maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall
            )
        }
        TextButton(
            onClick = {
                onSyncLockChange(null)
                deckA.setPlaybackSpeed(1f)
                deckB.setPlaybackSpeed(1f)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "RESET", color = Color.Gray,
                maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall
            )
        }
        TextButton(
            onClick = { onSyncLockChange(if (syncLock == "A_FOLLOWS_B") null else "A_FOLLOWS_B") },
            enabled = canSync,
            modifier = Modifier.fillMaxWidth()
        ) {
            val suffix = if (syncLock == "A_FOLLOWS_B") {
                syncRatioSuffix(stateB.bpm?.times(stateB.playbackSpeed), stateA.bpm?.times(stateA.playbackSpeed), syncRatioMode, tempoSyncTolerance)
            } else ""
            Text(
                if (syncLock == "A_FOLLOWS_B") "🔒 SYNC A→B$suffix" else "SYNC A→B",
                color = if (!canSync) Color.DarkGray else if (syncLock == "A_FOLLOWS_B") Color.White else AccentA,
                maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall
            )
        }
        SyncRatioModeToggle(mode = syncRatioMode, onModeChange = onSyncRatioModeChange, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(20.dp))
        RecordRow(state = recState, recording = recording, onStart = onStartRecording)
        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("A", color = AccentA, style = MaterialTheme.typography.labelLarge)
            Text("CROSSFADER", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Text("B", color = AccentB, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = crossfader,
            onValueChange = onCrossfaderChange,
            thumb = { CrossfaderThumb() },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.8f),
                inactiveTrackColor = Color.DarkGray,
            )
        )
    }
}

/**
 * Mix-recording control row. Four states: unavailable (API<29, no fallback
 * capture mechanism exists), idle (with a one-time inline rationale before
 * the first system consent dialog - it looks like a screen-recording
 * prompt, so it shouldn't ambush the user), recording (Stop + live timer),
 * and stopped/pending (Save/Discard - explicit, not auto-saved, so an
 * accidental tap of the always-visible REC button can't silently litter
 * the user's Music library).
 */
@Composable
private fun RecordRow(state: RecordingUiState, recording: RecordingViewModel, onStart: () -> Unit) {
    var showRationale by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            !state.isAvailable -> {
                Text(
                    "Recording requires Android 10+",
                    color = Color.DarkGray,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            state.isRecording -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { recording.stop() }) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color.Red, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("STOP", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(format(state.elapsedMs), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }

            state.hasPendingRecording -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Recorded ${format(state.elapsedMs)}",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall
                    )
                    TextButton(onClick = { recording.save() }, enabled = !state.isSaving) {
                        Text(if (state.isSaving) "SAVING…" else "SAVE", color = AccentA, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { recording.discard() }, enabled = !state.isSaving) {
                        Text("DISCARD", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            showRationale -> {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Android will show its screen-recording permission prompt to authorize audio " +
                            "capture — only the mix audio is recorded, nothing on screen.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall
                    )
                    TextButton(
                        onClick = { showRationale = false; onStart() },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) {
                        Text("CONTINUE", color = AccentA, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            else -> {
                TextButton(onClick = { showRationale = true }) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color.Red, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("REC", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        state.error?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, color = Color(0xFFFF5A7A), style = MaterialTheme.typography.labelSmall)
                TextButton(
                    onClick = { recording.clearError() },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("DISMISS", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DeckColumnCompact(
    label: String,
    accent: Color,
    state: DeckUiState,
    deck: DeckViewModel,
    settings: AppSettings,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scrollable: Stage 5 added enough fixed-height rows (cue/loop controls,
    // beat chips, hot cue grid) that shorter phones can't guarantee everything
    // fits in the space MixerScreen's Row(weight(1f)) hands this column. A
    // non-scrolling Column would silently clip instead (e.g. GAIN scrolling
    // off with no way to reach it) - scrolling here keeps everything
    // reachable. Note: Compose disallows a weight-using child inside a
    // scrollable Column (unbounded height vs. weight needing a bounded max),
    // which is why Turntable below switches from .weight(1f) to a fixed cap.
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DeckLabelRow(label = label, accent = accent, locked = state.isLocked, onToggleLock = { deck.setLocked(!state.isLocked) })

        TextButton(onClick = onImport, enabled = !state.isLocked) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = if (state.isLocked) Color.DarkGray else accent)
            Spacer(Modifier.width(4.dp))
            Text(state.trackName, color = if (state.isLocked) Color.DarkGray else Color.White, maxLines = 1)
        }

        val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        WaveformStrip(
            trackName = state.trackName,
            progress = progress,
            accentColor = accent,
            waveform = state.waveform,
            spectral = state.spectral,
            isAnalyzing = state.isAnalyzing,
            durationMs = state.durationMs,
            cuePointMs = state.cuePointMs,
            loopInMs = state.loopInMs,
            loopOutMs = state.loopOutMs,
            loopActive = state.loopActive,
            modifier = Modifier.fillMaxWidth()
        )

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    state.isAnalyzing -> "Analyzing…"
                    state.bpm != null -> {
                        val effective = state.bpm * state.playbackSpeed
                        val base = "%.1f BPM  ·  %.2f%%".format(effective, state.playbackSpeed * 100)
                        // Appended after formatting, not folded into the format
                        // string itself - a Camelot code is data, not a printf
                        // template, and keeping it out avoids any risk of a
                        // future value containing a literal '%' being misread
                        // as a format specifier.
                        state.key?.let { "$base  ·  $it" } ?: base
                    }
                    else -> "-- BPM"
                },
                color = accent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            KeyLockToggle(enabled = state.keyLockEnabled, accent = accent, onToggle = { deck.setKeyLock(!state.keyLockEnabled) })
        }

        // Stage 5 controls (cue, loop, hot cues) default to collapsed - djay
        // itself keeps the deck's primary view (platter/waveform/transport)
        // uncluttered and tucks cue/loop/pad controls behind a tap, rather
        // than showing every control permanently. The cue/loop state is
        // still always visible for free on the waveform overlay above even
        // while this panel is collapsed.
        var showCueLoopPanel by remember { mutableStateOf(false) }
        TextButton(
            onClick = { showCueLoopPanel = !showCueLoopPanel },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("CUE · LOOP · PADS · FX", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Icon(
                if (showCueLoopPanel) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(visible = showCueLoopPanel) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CueLoopRow(state = state, deck = deck, accent = accent, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)
                BeatLoopChips(
                    state = state, deck = deck, accent = accent,
                    extendedLengths = settings.extendedLoopLengths,
                    modifier = Modifier.fillMaxWidth(),
                    locked = state.isLocked,
                )
                HotCueGrid(hotCues = state.hotCues, deck = deck, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)
                // Stage 7: echo lives here (toggle-then-glance, same
                // interaction shape as the beat-loop chips above it) rather
                // than always-visible like FILTER below - it's armed once
                // and left running, not continuously ridden.
                EchoRow(state = state, deck = deck, accent = accent, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)
            }
        }

        // Ultrafine pitch nudge - the manual correction knob for beat-matching
        // drift that a BPM estimate alone (however precise) can't fully close,
        // across genres/tempos the auto-sync coarser pass might misjudge.
        // Two rows (coarse/fine) so both fit within a half-width deck column
        // without clipping off the edge of the screen.
        // Stage 8: step sizes are user-configurable (Settings > Playback
        // defaults); default to the original +/-1%/+/-0.1% behavior.
        val coarsePct = settings.nudgeCoarsePercent
        val finePct = settings.nudgeFinePercent
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NudgeButton("−%.2f%%".format(coarsePct * 100), accent, enabled = !state.isLocked) { deck.nudgeSpeed(-coarsePct) }
            NudgeButton("+%.2f%%".format(coarsePct * 100), accent, enabled = !state.isLocked) { deck.nudgeSpeed(coarsePct) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NudgeButton("−%.3f%%".format(finePct * 100), accent, enabled = !state.isLocked) { deck.nudgeSpeed(-finePct) }
            NudgeButton("+%.3f%%".format(finePct * 100), accent, enabled = !state.isLocked) { deck.nudgeSpeed(finePct) }
        }

        Turntable(
            accentColor = accent,
            isPlaying = state.isPlaying,
            trackName = state.trackName,
            albumArt = state.albumArt,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 240.dp), // fixed cap, not weight(1f) - see the
            // verticalScroll comment above DeckColumn's Column for why. Sized
            // generously since the default (collapsed) view has room now -
            // the platter should dominate the deck like it does in djay.
            locked = state.isLocked,
            onTap = { deck.togglePlay() }
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            MetallicIconButton(onClick = { deck.togglePlay() }, enabled = !state.isLocked) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = if (state.isLocked) Color.DarkGray else accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                "${format(state.positionMs)} / ${format(state.durationMs)}",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Text("GAIN", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        Slider(
            value = state.gain,
            valueRange = 0f..1f,
            onValueChange = { deck.setGain(it) },
            enabled = !state.isLocked,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.DarkGray,
            )
        )

        // Stage 7: FILTER is ridden continuously mid-mix, same usage
        // pattern as GAIN above it - always visible, not behind the
        // CUE·LOOP·PADS·FX tap like ECHO (toggle-then-glance) is.
        FilterSlider(state = state, deck = deck, accent = accent, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)

        // 3-band EQ trim - always-visible like GAIN/FILTER, same reasoning:
        // ridden continuously mid-mix, not a toggle-then-glance control.
        EqControls(state = state, deck = deck, accent = accent, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)
    }
}

/**
 * Small "🔒 KEY" / "KEY" toggle for Key Lock (see DeckViewModel.setKeyLock).
 * Deliberately a bare clickable Text, not a TextButton - TextButton's
 * enforced Material3 minimum touch-target height is exactly the class of
 * bug that forced VerticalEqBar's own Canvas-based redesign (see
 * FxControls.kt), and this sits inline in DeckColumnExpanded's already
 * tightly-budgeted BPM/key row, where that extra height isn't available.
 * A shared leaf widget, not a big arrangement block - same sharing rule
 * SyncRatioModeToggle/syncRatioSuffix already follow.
 *
 * Text, not the Lock/LockOpen icon DeckLabelRow uses below - that icon
 * already means a completely different thing here (locking the whole deck's
 * controls), and reusing it for Key Lock would read as the same toggle.
 * "🔒 KEY" vs plain-text on/off instead mirrors the sync-lock buttons'
 * own "🔒 B→A" vs "SYNC B→A" convention elsewhere in this file.
 */
@Composable
private fun KeyLockToggle(enabled: Boolean, accent: Color, onToggle: () -> Unit) {
    Text(
        text = if (enabled) "🔒 KEY" else "KEY",
        color = if (enabled) accent else Color.Gray,
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = 6.dp)
    )
}

/**
 * Deck label + lock toggle, shared by both DeckColumn variants. The lock
 * button is deliberately the one control in this whole row-and-below
 * hierarchy that's NEVER gated by [locked] itself - if it were, a locked
 * deck could never be unlocked again.
 */
@Composable
private fun DeckLabelRow(label: String, accent: Color, locked: Boolean, onToggleLock: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onToggleLock, modifier = Modifier.size(32.dp)) {
            Icon(
                if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (locked) "Unlock $label" else "Lock $label",
                tint = if (locked) accent else Color.Gray,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Stage 10c: the Expanded/Medium deck column. No longer a scrollable,
 * collapsible variant of Compact - per direction, every control on a
 * tablet/Fold-class screen must be simultaneously visible and correctly
 * sized for that device's real measured dimensions, not reachable only by
 * scrolling or hidden behind the CUE·LOOP·PADS·FX disclosure toggle. Three
 * concrete changes from the Stage 10a/10b version make that fit real,
 * not just claimed: the CUE·LOOP·PADS·FX panel content renders
 * unconditionally (no toggle, no AnimatedVisibility - there is no collapsed
 * state anymore), Turntable's cap drops from 240dp to 130dp, and the coarse/
 * fine nudge buttons combine into one row of four instead of two rows of
 * two - all three verified against real budget math: at the true 840dp
 * Expanded floor this column is one of two roughly-180dp-wide panels (see
 * [CenterMixColumn]'s doc), and vertically the tightest real case is the
 * tablet's 822.86dp-tall landscape screen, not its width.
 */
@Composable
private fun DeckColumnExpanded(
    label: String,
    accent: Color,
    state: DeckUiState,
    deck: DeckViewModel,
    settings: AppSettings,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        // Tighter than Compact's 6dp/Stage-10a's original 4dp - real budget
        // math after adding EQ below: see this function's doc comment for
        // the real height accounting that forced this and the other cuts
        // in this function (Turntable, the waveform, the dropped CUE·LOOP·
        // PADS·FX label, GAIN's combined label+slider row, EQ's compact
        // vertical-bar redesign). Measured against real `uiautomator dump`
        // pixel bounds on the actual tablet, not estimated blind.
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        DeckLabelRow(label = label, accent = accent, locked = state.isLocked, onToggleLock = { deck.setLocked(!state.isLocked) })

        TextButton(onClick = onImport, enabled = !state.isLocked) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = if (state.isLocked) Color.DarkGray else accent)
            Spacer(Modifier.width(4.dp))
            Text(state.trackName, color = if (state.isLocked) Color.DarkGray else Color.White, maxLines = 1)
        }

        val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        WaveformStrip(
            trackName = state.trackName,
            progress = progress,
            accentColor = accent,
            waveform = state.waveform,
            spectral = state.spectral,
            isAnalyzing = state.isAnalyzing,
            durationMs = state.durationMs,
            cuePointMs = state.cuePointMs,
            loopInMs = state.loopInMs,
            loopOutMs = state.loopOutMs,
            loopActive = state.loopActive,
            modifier = Modifier.fillMaxWidth(),
            heightDp = 52.dp,
        )

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    state.isAnalyzing -> "Analyzing…"
                    state.bpm != null -> {
                        val effective = state.bpm * state.playbackSpeed
                        val base = "%.1f BPM  ·  %.2f%%".format(effective, state.playbackSpeed * 100)
                        state.key?.let { "$base  ·  $it" } ?: base
                    }
                    else -> "-- BPM"
                },
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            KeyLockToggle(enabled = state.keyLockEnabled, accent = accent, onToggle = { deck.setKeyLock(!state.keyLockEnabled) })
        }

        // No section label here (unlike Compact) and no collapse toggle -
        // this content is always rendered, and a "CUE · LOOP · PADS · FX"
        // heading over four already-self-explanatory rows cost real height
        // this column didn't have to spare once EQ was added below.
        CueLoopRow(state = state, deck = deck, accent = accent, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)
        BeatLoopChips(
            state = state, deck = deck, accent = accent,
            extendedLengths = settings.extendedLoopLengths,
            modifier = Modifier.fillMaxWidth(),
            locked = state.isLocked,
        )
        HotCueGrid(hotCues = state.hotCues, deck = deck, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)
        EchoRow(state = state, deck = deck, accent = accent, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)

        // One row of four, not two rows of two: this column has real
        // horizontal width to spend on a >=600dp screen, unlike Compact's
        // half-phone-width origin for the two-row layout.
        val coarsePct = settings.nudgeCoarsePercent
        val finePct = settings.nudgeFinePercent
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NudgeButton("−%.2f%%".format(coarsePct * 100), accent, enabled = !state.isLocked) { deck.nudgeSpeed(-coarsePct) }
            NudgeButton("+%.2f%%".format(coarsePct * 100), accent, enabled = !state.isLocked) { deck.nudgeSpeed(coarsePct) }
            NudgeButton("−%.3f%%".format(finePct * 100), accent, enabled = !state.isLocked) { deck.nudgeSpeed(-finePct) }
            NudgeButton("+%.3f%%".format(finePct * 100), accent, enabled = !state.isLocked) { deck.nudgeSpeed(finePct) }
        }

        Turntable(
            accentColor = accent,
            isPlaying = state.isPlaying,
            trackName = state.trackName,
            albumArt = state.albumArt,
            modifier = Modifier
                .fillMaxWidth(0.55f)
                // Real budget math, not a preference: 130dp (Stage 10c's
                // original figure) was sized before EQ existed, and even
                // 84dp still didn't leave room once measured against real
                // `uiautomator dump` pixel bounds on the actual tablet.
                // Still the deck's largest single element by a wide margin.
                .heightIn(max = 60.dp),
            locked = state.isLocked,
            onTap = { deck.togglePlay() }
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            MetallicIconButton(onClick = { deck.togglePlay() }, enabled = !state.isLocked) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = if (state.isLocked) Color.DarkGray else accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                "${format(state.positionMs)} / ${format(state.durationMs)}",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall
            )
        }

        // GAIN's label sits inline with its slider (EqBandRow's own
        // pattern) rather than stacked above it like Compact/Medium keep -
        // one more of this function's real height cuts.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GAIN", color = Color.Gray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(34.dp))
            Slider(
                value = state.gain,
                valueRange = 0f..1f,
                onValueChange = { deck.setGain(it) },
                enabled = !state.isLocked,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = Color.DarkGray,
                )
            )
        }

        FilterSlider(state = state, deck = deck, accent = accent, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)
        EqControls(state = state, deck = deck, accent = accent, modifier = Modifier.fillMaxWidth(), locked = state.isLocked)
    }
}

/**
 * A real per-deck VU meter, not a decorative animation - polls
 * [DeckViewModel.currentLevel] (LevelAudioProcessor's audio-thread RMS
 * reading) on its own timer rather than riding [DeckUiState]'s ~10fps
 * StateFlow, matching this codebase's established rule for anything that
 * updates faster than deck *state* actually changes. ~15fps polling is
 * smooth enough for a meter, cheap enough to poll continuously.
 *
 * Vertical bar, bottom-up fill, three color zones - green (normal),
 * amber (hot), red (near clip) - the same three-zone convention every real
 * mixer VU meter uses, not an arbitrary gradient.
 */
@Composable
private fun LevelMeter(deck: DeckViewModel, color: Color, modifier: Modifier = Modifier) {
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(deck) {
        while (isActive) {
            level = deck.currentLevel
            delay(66L)
        }
    }
    Canvas(modifier = modifier.width(14.dp)) {
        drawRoundRect(
            color = Color(0xFF1A1A1F),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
        // Three color zones by height fraction, not by instantaneous level
        // alone - a meter sitting at 0.9 shows green at its own bottom
        // three-fifths, amber through the next quarter, red only at the
        // very top, matching how a real segmented VU meter reads.
        val filled = level.coerceIn(0f, 1f)
        val greenTopFrac = 0.6f
        val amberTopFrac = 0.85f

        fun drawZone(fromFrac: Float, toFrac: Float, zoneColor: Color) {
            val clampedTo = toFrac.coerceAtMost(filled)
            if (clampedTo <= fromFrac) return
            val top = size.height * (1f - clampedTo)
            val bottom = size.height * (1f - fromFrac)
            drawRect(color = zoneColor, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, bottom - top))
        }
        drawZone(0f, greenTopFrac, color)
        drawZone(greenTopFrac, amberTopFrac, Color(0xFFF2A93C))
        drawZone(amberTopFrac, 1f, Color(0xFFFF5A7A))
    }
}

/**
 * A round dot, not Material3's default stadium/pill shape - the crossfader
 * is DJayClone's one control most directly modeled on a specific real DJ
 * mixer part (a physical fader cap), and every reference mixer's fader cap
 * reads as a circular profile from the front, not a tall rounded rectangle.
 */
/**
 * A round, subtly-domed button - the transport play/pause control, the one
 * DJayClone button closest to djay Pro's real reference look: a physical-
 * feeling circular button rather than a flat Material icon button. A
 * centered radial gradient (light center, dark edge) plus a faint light
 * border ring reads as a raised metal cap without needing directional-
 * light math that would only be safe to tune with a live preview this
 * session's hardware-verification budget doesn't have room for.
 *
 * Wraps IconButton rather than reimplementing click handling - keeps
 * IconButton's real accessibility semantics (Role.Button, ripple,
 * minimum touch target) instead of a hand-rolled clickable Box.
 */
@Composable
private fun MetallicIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(44.dp)
            .background(
                brush = Brush.radialGradient(
                    if (enabled) listOf(Color(0xFF3A3A42), Color(0xFF19191D))
                    else listOf(Color(0xFF232328), Color(0xFF17171A))
                ),
                shape = CircleShape,
            )
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.12f else 0.04f), CircleShape)
    ) {
        content()
    }
}

@Composable
private fun CrossfaderThumb() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(Color.White, androidx.compose.foundation.shape.CircleShape)
    )
}

@Composable
private fun NudgeButton(label: String, accent: Color, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            color = if (enabled) accent else Color.DarkGray,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false
        )
    }
}

/**
 * Tempo-Sync v1: the ratio-mode 4-way toggle (Auto/1×/2×/½×), the
 * "(a)" deliverable the original pitch named explicitly. A small leaf
 * widget shared across all three arrangements - unlike the big per-
 * arrangement header/sync/crossfader blocks this file deliberately keeps
 * unshared, small reusable controls (DeckLabelRow, NudgeButton,
 * CrossfaderThumb) already follow this same pattern.
 */
@Composable
private fun SyncRatioModeToggle(mode: SyncRatioMode, onModeChange: (SyncRatioMode) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(
            SyncRatioMode.AUTO to "AUTO",
            SyncRatioMode.UNITY to "1×",
            SyncRatioMode.DOUBLE_TIME to "2×",
            SyncRatioMode.HALF_TIME to "½×",
        ).forEach { (value, label) ->
            val selected = mode == value
            TextButton(
                onClick = { onModeChange(value) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else Color.DarkGray,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/** Not a composable, so sharing it across all three arrangements doesn't
 * cross this file's "don't share the big arrangement blocks" line - it's
 * a pure display calculation, same category as [format] just below.
 * Empty string when unlocked, no ratio detected/forced, or a UNITY match -
 * UNITY intentionally shows no suffix at all (it's the ordinary case). */
private fun syncRatioSuffix(
    leaderEffectiveBpm: Float?,
    followerEffectiveBpm: Float?,
    mode: SyncRatioMode,
    tolerance: Float,
): String {
    if (leaderEffectiveBpm == null || followerEffectiveBpm == null) return ""
    val ratio = when (mode) {
        SyncRatioMode.AUTO -> TempoSyncMath.detectRatio(leaderEffectiveBpm, followerEffectiveBpm, tolerance)?.ratio
        SyncRatioMode.UNITY -> TempoSyncMath.SyncRatio.UNITY
        SyncRatioMode.DOUBLE_TIME -> TempoSyncMath.SyncRatio.DOUBLE_TIME
        SyncRatioMode.HALF_TIME -> TempoSyncMath.SyncRatio.HALF_TIME
    } ?: return ""
    return if (ratio.shortLabel.isEmpty()) "" else " ${ratio.shortLabel}"
}

private fun format(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
