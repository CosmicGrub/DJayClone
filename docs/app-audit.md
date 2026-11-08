# DJayClone audit: gaps in features, functions, activities and performance

Date: 2026-11-07. Build audited: **v0.18.1** (versionCode 22).
Method: Android lint, a mechanical measurement pass, and three independent read-only reviews (feature and function gaps; platform, activities and lifecycle; performance). Every claim below was read from the code; items marked **checked** I re-verified myself. Nothing here was measured on a device except where stated, so performance numbers are estimates from the code unless marked **measured**.

The earlier correctness audit (v0.18.1) already fixed: stale analysis landing on the wrong track, Play after end of track, analyzer OOM/leak/hang, recording reattach and write errors, library permission spinner, keyboard shortcuts while typing, screen state on rotate, settings validation, and the lint errors. Those are not repeated.

Companion document: [unbuilt-items.md](unbuilt-items.md) (the parity plan, what is built and what is not).

---

## A. Set safety and lifecycle (do first)

| # | Finding | Evidence | Fix | Size |
|---|---|---|---|---|
| A1 | **Back on the Mixer ends the session with no warning.** It finishes the Activity, clears the ViewModels and releases both players. A running recording keeps capturing silence. | **checked**: `BackHandler` exists only on Settings and Library (`MainActivity.kt` ~341, 352), none on the Mixer. | Root back handler: move task to background, or confirm when a deck plays or a take records. | S |
| A2 | **No protection for a backgrounded deck.** No wake mode, no media session, no playback notification, no headset/lock-screen buttons. The OS can stop audio when the screen turns off. | **checked**: `grep setWakeMode\|MediaSession` finds nothing; `media3-session` is declared and unused. | `setWakeMode(WAKE_MODE_LOCAL)` now; a `MediaSessionService` with a media notification for real background play. You asked for this. | M |
| A3 | **Process death or swipe-away empties both decks** (tracks, positions, crossfader, sync). A restored Activity can land on Library with blank decks. | No `SavedStateHandle`/`onSaveInstanceState`; only `screen` and the pad panel are saveable. | Persist last URI, position, mixer state; offer restore on launch. | M |
| A4 | **Audio focus is requested once at launch and never again.** It interrupts the user's other music on open; after a call both decks pause and a later Play runs without focus; rotation re-requests without abandoning. | `requestAudioFocus()` only in `onCreate`; abandon in `onDestroy` even on config change. | Request on first Play, re-request after loss, skip abandon on configuration change. | S |
| A5 | **Dialogs vanish on rotate/fold/DeX resize** (load confirm, BPM, record rationale, discard). A "yes" is lost. | `pendingLoad` is an Activity field; `showRationale`, `showBpmDialog` use plain `remember`. | `rememberSaveable` or ViewModel state. | S |
| A6 | A new VIEW intent silently replaces an open confirm dialog; `setIntent` never called; the VIEW grant is not persisted. | `MainActivity.kt` 394-397, 445-450. | Queue or toast; call `setIntent`; try `takePersistableUriPermission`. | S |
| A7 | Last cue/loop edits can be lost if the process dies within 0.4 s (debounce) because there is no `onStop` flush. | `debounce(400)` in `DeckViewModel`; no lifecycle flush. | Flush on `ON_STOP`. | S |
| A8 | **"Forget saved cues and loops" also deletes BPM corrections, and its dialog says BPM is unaffected.** My bug from v0.17.0. | **checked**: `clearAll()` clears the whole prefs file; dialog text says "BPM and key data are not affected". | Fix the wording or keep BPM overrides; add undo. | S |
| A9 | "Last played" is stamped before the load is allowed (a locked deck or a cancelled confirm still counts), and is never read anywhere. | **checked**: `markPlayed` runs before `onLoadTrack` (`LibraryScreen.kt` 196-197). | Stamp on successful load; use it for sort and a played tint. | S |
| A10 | A locked deck can still be made a sync follower and retempo'd; TableTop (fold) has no lock control at all, so a locked deck cannot be unlocked there. | No `isLocked` check in the sync collector; `DeckLabelRow` used only in Compact and Expanded. | Gate the follower, add the lock to TableTop. | S |

## B. Release engineering and platform

| # | Finding | Evidence | Fix | Size |
|---|---|---|---|---|
| B1 | **Release is debug-signed, unminified, unshrunk.** If the debug key is lost, updates over the installed copy fail; Play upload is impossible. Dex is about 47 MB uncompressed (APK 13.8 MB). You approved the one-time reinstall for a real key. | **checked**: `isMinifyEnabled = false`, debug signing config, `material-icons-extended` pulled for about 25 icons. | Real keystore (back it up), R8 plus `shrinkResources`, Media3 keep rules, replace the extended icon set with the icons used. | M |
| B2 | **Edge-to-edge is not handled** at all; raising targetSdk to 35 draws under system bars on Android 15. You said Play is possible, so this is on the list but not urgent. | no `WindowInsets`/`enableEdgeToEdge` anywhere. | `enableEdgeToEdge()` plus `safeDrawing` insets before bumping targetSdk. | M |
| B3 | 16 KB page alignment of `libandroidx.graphics.path.so` is unverified (required for targetSdk 35+ on Play). | only native lib in the APK. | Bump Compose BOM/AGP, check alignment. | S |
| B4 | The VIEW intent filter still lists `file://`; on API 33+ that cannot be read without a media permission, so the load fails. | `AndroidManifest.xml`. | Drop `file://` or copy. | S |
| B5 | Predictive back not opted in; the three BackHandlers get no preview. | no `enableOnBackInvokedCallback`. | Set it and test. | S |
| B6 | No crash reporting, no CI, no instrumented or Compose UI tests; only 9 JVM test files (pure maths and FX). Lifecycle behaviour (recreation, process death, the service) has no test. | `app/src` has only `main` and `test`; no `.github`. | Local uncaught-exception log shown on next launch; CI for unit tests and lint; a few `ActivityScenario` tests. | M |
| B7 | `media3-session` is declared but unused; two `Log.d` calls fire on every posture change in release. | `build.gradle.kts`, `DjAdaptive.kt`. | Use it for A2 or remove it; strip logs via R8. | S |
| B8 | Input coverage is thin: no gamepad, MIDI or headset-button handling; the keyboard drives Deck A only; font scale and RTL untested; no phone-landscape layout. | `MainActivity.kt` 556-575. | Keyboard map for Deck B (plan item), a landscape phone arrangement, a 1.3x font-scale pass. | M to L |

## C. Feature and function gaps (beyond the parity plan)

| # | Gap | What a DJ experiences | Size |
|---|---|---|---|
| C1 | **No beat grid or beat-phase sync.** SYNC matches tempo only; kicks sit wherever Play was pressed. Beat loops and jumps start at the raw playhead. | Two "synced" tracks drift in phase; a 1-beat loop tapped late is off-beat. The plan lists quantize as blocked on this but has no grid item. | L |
| C2 | **Layout parity.** Level meters exist only in TableTop and Expanded (none on Compact/Medium phones); the library sidebar exists only in Expanded; Compact hides CUE/LOOP/PADS/ECHO behind a toggle the others do not have; ECHO sits in a different place per layout. | Gain staging is invisible on a phone; browsing hides the decks on a phone or fold. | M |
| C3 | Auto Gain on/off does not apply to decks already loaded (trim changes only on next load). | Toggling the setting seems to do nothing. | S |
| C4 | Saving a mix gives no confirmation or location; the file name and folder are fixed; the rationale shows on every start; no size-per-minute hint. | "Did it save? where?" | S |
| C5 | Library: manual (SAF) tracks are removed with one tap and no undo; no refresh or re-analyse control; cached BPM/key cannot be cleared; search is title/artist/key only (no BPM, no clear button). | A stray tap loses an import; a wrong cached BPM sticks. | S to M |
| C6 | Settings that apply later or silently: default crossfader position applies next launch only and does not say so; tempo range and nudge steps are global, not per deck. | Settings look broken. | S |
| C7 | Locked-deck feedback is a toast on load only; dimmed controls give no reason (plan covers sync/BPM reasons only). Hot-cue delete is a blind long-press with no hint. | "Why does nothing happen?" | S |
| C8 | Ended-deck handling is inconsistent across routes (Play restarts from cue, but waveform tap and beat-jump do not reset the ended state). Lower confidence. | Odd behaviour after a track ends. | S |

## D. Performance

Measured: key detection on a warmed desktop JVM is **about 0.5 s per 3-minute track**; the 300 spectral FFTs about 18 ms. A phone without a warm JIT is typically several times slower. Everything else below is reasoned from the code.

| # | Finding | Estimate | Fix | Size |
|---|---|---|---|---|
| D1 | **Whole-mixer recomposition.** `MixerScreen` collects both deck states, the recording state and the crossfader at the top and passes the whole unstable `DeckUiState` (arrays, list, bitmap, Uri) into every child, so nothing can skip. Position ticks every 100 ms per deck; a slider drag emits at touch rate. Compose compiler 1.5.14 with strong skipping off. | 10 to 20 full-tree passes per second playing, 60 to 120 during a drag; about 3 to 6 ms each on a flagship, 10 to 15 ms on the handheld. Frames drop while performing. | Read state slices at the leaf; split position into its own flow; stable UI models; enable strong skipping. | M |
| D2 | **Key detection computes about 63M `cos`/`sin` calls per track** (double precision, per butterfly, 60 FFTs of 65,536 points). | about 1.5 to 5 s CPU per track; during it BPM, key, sync, loops and echo are unavailable. Real FFT arithmetic is only about 0.3 s. | One precomputed twiddle table and Hann table (about 10x); decimate to about 5.5 kHz and use 8,192 points (about 8x more). | S |
| D3 | **Analysis peak memory 64 to 100 MB per track**; two decks loading together can reach 130 to 200 MB against a 192 to 256 MB heap, no `largeHeap`. | OOM risk; the analyzer swallows it into a null result. | Decode to about 11 kHz mono, pre-size, avoid the `toArray` copy, run analyses one at a time. | M |
| D4 | **The cache never skips work.** Only BPM, key and gain are cached; waveform and spectral data are not, so every reload re-decodes and re-runs D2 and D3. | A known track costs the full analysis again. | Persist the whole analysis per track (key by size and mtime). This is plan item `library-analysis-store`. | M |
| D5 | **Recording ticks the whole UI**: `elapsedMs` updates per capture buffer (about 25 to 50 times per second), and the strip state sits at the top of the tree. | 25 to 50 full recompositions per second while recording. | Publish at 1 Hz and read it at the leaf. | S |
| D6 | **Sink buffer 250 to 750 ms**, so FX knobs act late; each loop wrap seeks and (from memory of Media3, not verified here) flushes the sink. Loops under about 60 ms cannot be gapless. | Late filter/EQ/echo response; a gap per short-loop wrap. | Smaller `DefaultAudioTrackBufferSizeProvider` (Safe/Balanced/Low profile, plan item), check underruns; longer term, loops inside an AudioProcessor. | M |
| D7 | Turntable recomposes every frame (rotation read in composition) and its animation clock runs while paused. | Needless GPU/CPU and battery, two decks. | Read rotation in `graphicsLayer`; animate only while playing. | S |
| D8 | Polling loops live for the ViewModel, not the screen: position (10 Hz x 2), loop watcher (about 67 Hz in a loop), level meter (15 Hz x 2). | Battery while backgrounded. | `repeatOnLifecycle`, poll only while playing or visible. | S |
| D9 | No minify, no app baseline profile, the extended icon set. | Slower cold start, bigger dex. | See B1; add a baseline profile. | M |
| D10 | Library: thumbnail misses are never cached (art-less rows retry on every scroll); the fallback path writes a prefs key per thumbnail (whole-file rewrite). SharedPreferences used as the track database (about 5 keys per track; XML parse on Main at first access). | First load jank; battery on fast scroll. | Negative cache, file-based thumb paths, move track data off Main or to a single file/Room. | M |
| D11 | Library refresh re-queries MediaStore on every entry and on Expanded recreation; per row `Html.fromHtml` twice and about 8 prefs lookups; title sort lowercases on every comparison. | 0.2 to 0.5 s of IO for 5,000 rows, repeated. | Cache by MediaStore generation, `IS_MUSIC` filter, precompute sort keys. | M |
| D12 | Recording Save copies with an 8 KB buffer through FUSE; needs about 2x space and time. | A 1-hour mix is about 690 MB. | 256 KB buffer or write straight into the pending MediaStore row. | S |
| D13 | Album-art decode bound is too loose (a 1500 px cover decodes at full size, about 9 MB per deck). | Memory. | Fix the `inSampleSize` loop. | S |
| D14 | The analysis cap (first 3 minutes) means BPM, key and gain describe the intro on long tracks. | Wrong tempo/key on tracks that open with an intro. | Analyse a middle section for BPM and key; decode the whole track at low rate for the waveform. | M |
| D15 | No instrumentation: no trace sections, no jank stats, no underrun counter, no benchmark. | Every number above is a guess until measured. | `androidx.tracing`, `JankStats`, an `AnalyticsListener` underrun counter, a startup Macrobenchmark, a JVM microbenchmark for `Fft` and `KeyDetector`, debug `StrictMode`. | S to M |

Checked and found efficient: audio-thread processing (no allocation or locks in the sample loops, about 0.4% of a core per deck), BPM autocorrelation (about 16M flops), the recording write path (bounded queue, heartbeat header, periodic fsync, no busy-waits), `LazyColumn` keys, the debounced search, and per-track persistence writes.

---

## E. Recommended order

1. **v0.19 Set safety:** A1 to A10, B7, C3, D5, D7, D8. Adds the keystore and R8 (B1) in the same release so the one-time reinstall happens once.
2. **v0.20 Analysis speed and the waveform:** D2, D3, D4, D14, plus the plan's full-track waveform and markers.
3. **v0.21 Performance pass:** D1, D6 (buffer profile), D9, D10 to D13, D15 instrumentation.
4. **v0.22 Visual system phase 1:** theme variables and the design tokens, theme picker, skins (see the design canvas).
5. **v0.23 Library foundation** (plan W5), then sync and tempo completion, beat grid (C1), layout parity (C2).
6. Later: FX rack, Automix, MIDI and headphone cue (need hardware), edge-to-edge and 16 KB alignment (B2, B3) before any Play submission.
