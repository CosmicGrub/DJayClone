# DJayClone: unbuilt items

Generated 2026-11-07 against **v0.18.1** (versionCode 22). Source: the djay Pro parity plan (159 candidate items; 30 more already existed before the plan). Each item lists its planned wave, user value (v, 1-5) and effort (e, 1-5).

| | Items |
|---|---|
| Built from the plan | 9 |
| Partly built | 21 |
| **Unbuilt** | **129** |
| of which deliberately deferred or dropped by the plan | 56 |

## Shipped outside the plan (v0.16.0 to v0.18.1)

Per-track saved cues, hot cues and loops (restored on load); loop halve/double/move and beat-jump pads; tap or drag the waveform to seek; elapsed/remaining toggle with end-of-track pulse; crossfader curves (smooth/linear/sharp) and reverse; undo for a cleared hot cue; keep-screen-on; plain-language playback errors; confirm-before-load on a playing deck; Auto Gain as a separate trim; Camelot key readout; and the v0.18.1 audit fixes (stale analysis, analyzer memory, recording reattach, library permission spinner, and more).

## Built from the plan

- **tempo-fader** (v0.17.0): Per-deck tempo (pitch) fader: selectable range, centre detent, invert, double-tap reset, signed % readout, throttled engine updates
- **tempo-bpm-edit** (v0.17.0): BPM correction sheet: half/double (and 3:2), typed BPM, Restore, saved per track, with a rescue path when detection fails
- **tempo-tap-bpm** (v0.17.0): Tap tempo with missed-beat tolerance and least-squares precision
- **library-load-guard** (v0.16.0 (library, Open-with and file picker; queue does not exist yet)): Protect active deck: guard every library/queue/Open-with load against live and locked decks
- **mixer-crossfader-label-swap** (v0.16.0): Crossfader end labels and arrow targets follow the Reverse setting
- **input-lock-gate-fixes** (v0.17.0): Close the three deck-lock bypasses (keyboard, KEY toggle, RESET) behind one pure gate
- **record-self-only-capture** (v0.18.0): Record only DJayClone's own audio (UID-scoped capture)
- **record-crash-safe-wav** (v0.18.0): Crash-safe recording file, durable location and automatic recovery
- **record-storage-guard** (v0.18.0): Recording time-left readout and a deterministic low-storage stop

## Partly built: what is still missing

- **transport-waveform-full-track** (plan W4, v5 e3): Waveform, playhead and seek now agree on the span analysed. Missing: decode and draw the whole track (the 3-minute cap remains).
- **transport-end-of-track** (plan W3, v4 e2): Play after the end restarts from the cue; loading onto an ended deck no longer autostarts. Missing: end-of-track policy (stop / repeat / next).
- **transport-pause-on-headphone-unplug** (plan W-, v4 e1): Same as mixer-noisy-pause: pause works, no banner.
- **transport-beat-jump-sizes** (plan W7, v3 e2): Fixed jumps of -4/-1/+1/+4 beats. Missing: selector for 1-128 beats and jump-moves-loop.
- **mixer-rec-capture-scope** (plan W-, v5 e2): Only this app's audio is captured. Missing: live peak/clip readout while recording.
- **mixer-noisy-pause** (plan W1, v4 e1): Pauses on disconnect (setting, default on). Missing: the 'Output disconnected' banner with Resume.
- **persist-bpm-key-override** (plan W-, v5 e3): BPM override saved per track (half/double/typed/tap). Missing: key override.
- **persist-undo-destructive** (plan W-, v4 e3): Hot-cue undo only. Missing: loop, library entry and discard undo log.
- **persist-recording-recovery** (plan W-, v4 e3): Unfinished takes are recovered and offered on launch. Missing: soft discard with undo.
- **persist-settings-hardening** (plan W1, v3 e2): Values clamped on load, fallback to defaults. Missing: schema version and migration, retiring legacy keys.
- **persist-recreation-saveable-ui** (plan W1, v3 e1): Current screen and cue panel survive fold/rotate. Missing: library sort order and scroll position.
- **input-key-hardening** (plan W8, v4 e2): Keyboard shortcuts ignore keys while a text field has focus. Missing: key-repeat suppression, handler consolidation.
- **input-haptics** (plan W7, v3 e3): Long-press haptic on hot cues only. Missing: the haptic policy for pads, detents and sync.
- **input-confirm-discard** (plan W-, v3 e1): Discard of a recording confirms. Missing: RESET tempos confirm and the reusable component.
- **record-capture-integrity** (plan W2, v4 e3): Frame-based clock, silence-filled gaps, stall watchdog, glitch count. Missing: dismissable glitch chip, saved gap record, a queue ledger.
- **record-indicator-notification** (plan W2, v4 e2): Notification says recording, has a live clock and Stop; red REC strip over Library/Settings. Missing: STOP inside the strip, pulse spec, hours-aware text on every surface.
- **record-stop-discard-guard** (plan W2, v4 e2): Tap-again STOP after 30 s and confirmed DISCARD (10 s+). Missing: soft-discard Undo, one-time rationale.
- **polish-deck-recovery** (plan W-, v4 e2): Ended deck restarts on Play. Missing: re-prepare a deck that failed to load.
- **polish-transient-messages-and-undo** (plan W1, v3 e2): Undo exists for cleared hot cues only. Missing: shared message host replacing Toasts and the undo log.
- **polish-nav-state-survival** (plan W-, v3 e1): Screen and cue-panel toggle survive. Missing: dialogs (load confirm, BPM, record confirm) are still lost on rotate.
- **polish-haptics** (plan W-, v3 e2): Same as input-haptics: only the hot-cue long-press haptic exists.

## Unbuilt, in planned release order

### W1 v0.16.0 Safety and foundations

| Item | What | v | e | Needs device |
|---|---|---|---|---|
| `persist-minijson` | MiniJson: a tiny dependency-free, bounded JSON reader/writer shared by all file formats | 2 | 2 | no |
| `polish-settings-registry` | Schema-driven, searchable Settings (sections, summaries, modified marker, per-section reset); retire the dead 'Max echo wet mix' | 3 | 4 | no |

### W3 v0.18.0 Per-track state and transport reliability

| Item | What | v | e | Needs device |
|---|---|---|---|---|
| `transport-cue-cdj-semantics` | CUE button gets full CDJ behaviour: re-set when paused away, PLAY-latch during hold, playhead starts at the cue on load | 4 | 2 | no |
| `persist-track-identity` | One identity per audio file across every load route, with a name fingerprint check | 4 | 3 | no |
| `persist-track-record-patching` | Lossless, multi-writer-safe per-track record updates (patches, extras, updatedAt) | 3 | 3 | no |
| `persist-gain-memory` | Optional remembered channel gain per track | 3 | 2 | no |
| `persist-song-loading-settings` | Song-loading settings: start at cue, reset EQ on load, what happens to playback after a load | 3 | 2 | no |
| `polish-deck-section-order` | Compact deck: move GAIN/FILTER/EQ above the platter (optional platter size/hide) | 4 | 2 | yes |
| `polish-deck-colour-presets` | Colour-blind-safe deck colours + never identify a deck by colour alone | 3 | 2 | no |

### W4 v0.19.0 Analysis pipeline and waveform

| Item | What | v | e | Needs device |
|---|---|---|---|---|
| `transport-waveform-markers` | Hot-cue markers, minute ticks, marker-snapping taps and a scrub time bubble on the waveform | 4 | 2 | no |
| `transport-playhead-clock-platter` | Smooth extrapolated playhead and a platter that spins at the real playback rate and stops where it is | 3 | 2 | no |
| `transport-load-behaviour-auto-cue` | Load behaviour: auto-cue at the first audible sample, optional start-on-load, start at saved cue | 3 | 3 | no |
| `tempo-bpm-cache-on-load` | Instant BPM and key on load from the cache (and keep the cached value if analysis fails) | 4 | 2 | no |
| `library-analysis-store` | Persistent analysis store: waveform, spectral, BPM, key, gain with version/status, so loads are instant and the library knows what is analysed | 5 | 4 | no |
| `automix-silence-points` | Automatic track start/end points (silence trim), manual Set Start / Set End, optional skip-lead-in on load | 4 | 3 | yes |
| `polish-analysis-progress` | Determinate analysis progress, honest waveform placeholder, and 'no waveform' state | 3 | 3 | no |

### W5 v0.20.0 Library foundation, queue, key matching

| Item | What | v | e | Needs device |
|---|---|---|---|---|
| `tempo-key-compat` | Harmonic-compatibility indicator on each deck's key readout (Camelot wheel relation, with a non-colour glyph) | 4 | 1 | no |
| `library-live-index` | Live library index: in-memory sort with direction, live BPM/key updates, ContentObserver refresh, scroll memory | 4 | 3 | no |
| `library-search-tokens` | Search UX: multi-word AND search, accent folding, bpm:/key: operators, clear button, match highlight | 4 | 2 | no |
| `library-manual-metadata` | Manual/picker rows get real titles, artists, durations; MediaStore twins deduped; collision-proof list keys | 4 | 3 | no |
| `library-row-presenter` | Readable library rows: ellipsis/compact density, loaded-on-A/B and playing badges, analysed indicator, locate | 4 | 3 | no |
| `library-harmonic-match` | Harmonic match: Camelot compatibility colours, key filter, compatibility sort, key-format switch | 5 | 3 | no |
| `library-track-match` | Track Match: BPM-window filter, half/double-time awareness, tempo-offset readout and a MATCH ranking | 5 | 3 | no |
| `library-queue` | Up Next queue: add/play-next/reorder/remove, persisted, 'Load next' onto the idle deck, optional auto-prep | 5 | 4 | no |
| `library-playlists` | Playlists: create/rename/delete/duplicate, reorder, add-to-playlist flows, Favourites crate | 4 | 4 | no |
| `library-history` | Set history: play detection, session log, played-tracks tint, tracklist export (text/CSV/M3U) | 4 | 3 | no |
| `polish-library-states` | Library empty/permission states with real actions, permission recovery, keyboard-safe search | 3 | 3 | yes |

### W6 v0.21.0 Tempo, BPM and sync

| Item | What | v | e | Needs device |
|---|---|---|---|---|
| `tempo-target-bpm` | Type a target BPM or +/-% to set a deck's tempo directly | 3 | 2 | no |
| `tempo-sync-per-deck` | Per-deck SYNC chip with a safe leader choice (never retempo the live deck for a stopped one) and lit state | 4 | 3 | no |
| `tempo-sync-live-guard` | Do not yank a playing follower when the sync leader loads a new track (release the lock and say so) | 4 | 2 | no |
| `tempo-sync-status` | Truthful sync state on the button: waiting for BPM, locked (with ratio), or out of range (needs 2.7x, clamped to 2.0x) | 3 | 2 | no |
| `tempo-sync-behaviour-settings` | Sync behaviour switches: break-on-manual-tempo, reset tempo when sync turns off, maintain sync on load, auto-sync a new track to the playing deck, and a non-destructive RESET | 4 | 3 | no |
| `polish-disabled-reasons` | Say why a control is dimmed (no tempo, still analysing, locked) - tap a dimmed control for the reason | 3 | 2 | no |

### W7 v0.22.0 Deck controls and cue/loop pads

| Item | What | v | e | Needs device |
|---|---|---|---|---|
| `transport-saved-loops` | Eight saved loop slots per track (LOOPS pad page) with undoable delete | 4 | 3 | no |
| `tempo-pitch-bend` | Momentary pitch bend (hold to nudge phase, release returns to tempo) that sync followers do not see | 5 | 3 | yes |
| `tempo-key-shift` | Semitone key shift per deck (+/-6) with Key Lock aware pitch maths and a shifted/effective key readout | 4 | 3 | no |
| `fx-echo-controls` | ECHO performance controls: live WET and FEEDBACK, more beat divisions, tap-latch / hold-momentary ECHO button, symmetric wet slew | 4 | 2 | no |
| `fx-filter-feel` | FILTER feel: centre detent, double-tap-to-centre, and a Low/Med/High resonance setting | 3 | 2 | no |
| `fx-reset-all` | FX OFF: one gesture that clears FILTER, ECHO, the FX rack and held pads on a deck | 3 | 1 | no |
| `mixer-control-detents-reset` | Centre detents and double-tap reset for EQ bands, FILTER, GAIN and the crossfader | 4 | 2 | no |
| `mixer-eq-kill` | Per-band EQ KILL: hold for momentary kill, tap to latch | 4 | 2 | no |
| `persist-hotcue-colors-labels` | Hot cue names and 16 colours, edited from a pad menu instead of a blind long-press delete | 4 | 3 | no |

### W8 v0.23.0 Mixer strip, fades and keyboard

| Item | What | v | e | Needs device |
|---|---|---|---|---|
| `mixer-meter-dbfs-peak-clip` | Peak/dBFS channel meters with peak-hold and clip LED on every layout, plus a master clip-risk indicator | 5 | 3 | no |
| `mixer-fader-start` | Fader start: a paused deck starts when the crossfader opens it | 3 | 2 | no |
| `mixer-trim-readout-override` | Visible TRIM chip: show Auto Gain in dB, tap to bypass, drag for manual trim, remembered per track | 4 | 2 | no |
| `mixer-master-trim-mute` | Master level, output headroom and a MUTE (panic) button with ramped gain | 3 | 2 | no |
| `input-action-bus` | Central input action bus: DjAction + ActionDispatcher with deck-lock gating, held-action tracking and CUE logic shared by touch, keyboard and MIDI | 4 | 3 | no |
| `input-keyboard-map` | Full djay-style two-deck keyboard layout with hold semantics, shortcut cheat-sheet and active-deck Space | 4 | 3 | no |
| `input-back-guard` | Back-guard (press back twice to leave while playing/recording) and edge-gesture exclusion for full-width faders | 4 | 1 | no |
| `automix-crossfader-auto-fade` | One-tap crossfader auto-fade (arrows to A or B) with ramp core and cancel-on-touch | 4 | 2 | no |
| `automix-master-fade-out` | Master FADE OUT and stop (end-of-set / panic fade) without moving any fader | 3 | 2 | no |

### W9 v0.24.0 Library power, persistence and recording completeness

| Item | What | v | e | Needs device |
|---|---|---|---|---|
| `library-batch-analyze` | Batch analyse library/playlist/selection in the background with progress, pause while mixing, resumable | 5 | 4 | yes |
| `library-row-actions` | Row actions: long-press menu, swipe, track-info sheet with BPM/key fix, and multi-select bulk actions | 4 | 4 | no |
| `library-browse-hide` | Browse by artist/album/folder, richer MediaStore metadata, hide track/folder, minimum length and system-sound filtering | 4 | 3 | no |
| `mixer-sink-buffer-profile` | Selectable audio latency profile (Safe/Balanced/Low) for the AudioTrack buffer, with underrun watchdog | 4 | 2 | yes |
| `persist-session-restore` | Restore the last session (decks, positions, FX, crossfader, sync) after a kill or relaunch | 5 | 4 | yes |
| `persist-trackdata-export-import` | Portable cue/loop/override backup that re-binds to files on any device, plus a Data section | 4 | 4 | no |
| `record-postfader-stems` | Post-fader recording via per-deck capture lanes and an in-app mixdown | 5 | 4 | yes |
| `record-level-meter-clip` | Recording level meter with clip latch | 4 | 2 | no |
| `record-tracklist-capture` | Automatic on-air tracklist with timestamps, plus a MARK button | 5 | 3 | no |
| `record-tracklist-export` | Tracklist export: .cue sheet, timestamp text, YouTube chapters, CSV | 4 | 2 | no |
| `record-share-sheet` | Share / open / save-a-copy for finished mixes | 4 | 2 | yes |
| `polish-height-aware-arrangement` | Height-aware layout policy + short-landscape arrangement (cover screen / phone landscape / split-screen) | 4 | 4 | yes |

### W10 v0.25.0 FX suite and Automix

| Item | What | v | e | Needs device |
|---|---|---|---|---|
| `fx-rack-shell` | FX rack: 3 Manual FX slots per deck (type, ON, dry/wet, amount, beat division) in one Media3-contract-safe processor, shipping BITCRUSH as the first effect | 4 | 4 | no |
| `fx-instant-pads` | Instant FX pad page (8 one-tap pads) with HOLD latch, tap one-shots, exclusive groups, Edit mode and variants | 5 | 4 | no |
| `fx-echo-out` | ECHO OUT pad (1 beat / 3/4 beat): the track fades into decaying echoes, dry returns on release | 4 | 3 | no |
| `fx-roll` | ROLL pad: sample-accurate loop roll / beat repeat (1/8, 1/4, 1/2, 1 beat) that returns to the live position on release | 5 | 3 | no |
| `fx-tape-stop-backspin` | VINYL STOP (4 / 8 beats) and BACKSPIN pads: audio-domain brake and spin-back with live (slip) return | 4 | 4 | no |
| `fx-reverb` | REVERB (Freeverb-style, size + damping) as a Manual FX type and a WASH pad | 4 | 3 | no |
| `automix-deck-lifecycle-end-policy` | Deck ready/ended signals, replay-from-end fix, and end-of-track policy (stop / repeat / load next / play next) | 4 | 2 | no |
| `automix-transition-planner` | Transition planner: where to start the mix, how long, how to treat tempo and key (plus Camelot compatibility) | 4 | 3 | no |
| `automix-transition-shapes` | Transition styles: Fade, EQ bass-swap, Filter sweep, Cut as pure automation curves | 3 | 2 | yes |
| `automix-engine` | Automix engine: queue-driven, tempo- and key-aware automatic mixing with Mix Now / Skip and a status bar | 5 | 5 | yes |

### Unscheduled: deferred or dropped by the plan

| Item | What | v | e | Why it was held back |
|---|---|---|---|---|
| `transport-loop-roll` | Loop Roll (momentary bounce pads) with slip: hold = roll, release = resume where the track would have been | 5 | 3 | Not placed in a wave. |
| `transport-scrolling-waveform` | Scrolling, zoomable waveform view (centred playhead, 4 zoom levels, drag-scrub, markers) for the deck strip and TableTop glance pane | 4 | 4 | Effort 4 with gesture arbitration risk, and no beat lines without a grid. Deferred to wave 11+, since the full-track waveform and markers cover the main need. |
| `transport-pitch-bend-hold` | Hold-to-bend: press-and-hold a nudge button for a temporary, progressively growing speed offset that releases back to tempo | 4 | 2 | Not placed in a wave. |
| `transport-slip-mode` | Slip mode toggle: normal loops resume where the track would have been | 4 | 3 | Depends on a roll clock and has to hook every loop-exit path (risk 3). Deferred to wave 11+. Audio-domain Roll in W10 already gives slip-style return. |
| `transport-quantize` | Quantize (Q) for hot cues, loops, cue set and long-press beat-snapped CUE - requires a beat grid | 4 | 3 | Hard dependency on a beat grid. No beat-grid item exists in the candidate list, so this cannot ship. Revisit when a grid is built. |
| `transport-instant-double` | Instant Double: copy the loaded track, position and markers to the other deck without re-analysing | 2 | 2 | Value 2, niche juggling technique on a phone UI. Not worth the load-guard and persistence-handshake risk. |
| `tempo-key-match` | One-tap KEY MATCH: shift a deck to the other deck's key by the smallest number of semitones | 3 | 2 | Value 3 and depends on key shift plus compat. Key shift (W7) and the compat indicator (W5) already let the DJ do this manually. Deferred. |
| `tempo-sync-auto-leader` | AUTO sync mode: the louder / playing deck leads, the other follows, with hysteresis and no tempo yank at the flip | 3 | 3 | Value 3, effort 3. The explicit per-deck chip with the safe-leader rule and the live-leader guard cover the safety need without flip-flop risk. |
| `tempo-bpm-estimator` | Extract the BPM estimator into a pure, tested BpmEstimator with harmonic-sum scoring, a tempo prior and a confidence value | 3 | 4 | Effort 4, value 3. The BPM correction sheet, tap tempo and half/double rescue paths in W6 cover octave errors. The estimator rewrite can follow once a real-music corpus exists. |
| `library-m3u` | M3U/M3U8 playlist import and export with robust matching to library tracks | 3 | 2 | Value 3. App-local playlists are in W5 and heuristic matching needs a results dialog. Deferred to wave 11+. |
| `library-saf-folder-import` | Folder import and SAF grant hygiene: tree picker, grant budget warnings, release on remove, covered-by-tree access check | 3 | 4 | Lowest value for a MediaStore-first library, effort 4, device-dependent grant-cap behaviour unconfirmed. Deferred. |
| `fx-noise-sweep` | SWEEP pad: beat-length white-noise riser (high-pass sweep + level ramp) for build-ups | 3 | 2 | Value 3, taste-dependent and needs a listening pass. The reviewer has no ears. Deferred. |
| `fx-modulation-pack` | FLANGER, PHASER and TREMOLO Manual FX with beat-locked LFO rate (1..16 beats) | 3 | 3 | Value 3 and needs a listening pass. Deferred behind the rack. |
| `fx-gate` | GATE (trance gate) Manual FX and pad: 1/16, 1/8, 1/4 note chop with duty and smoothing | 3 | 2 | Grid-locked chop needs a beat grid to be useful. Deferred. |
| `fx-xy-pad` | Pad FX: X/Y touch pad that drives the selected FX slot (touch engages, release disengages, HOLD latches) | 4 | 3 | Needs at least two parameterised rack types shipped first, and it is value-add polish. Deferred to wave 11+. |
| `fx-lag-compensation` | FX clock and queue-lag estimate: content-position anchor so grid-locked FX and ROLL line up with what the DJ heard | 3 | 3 | Needs a device to calibrate (value 3). Roll works in free-run with lag 0 in W10. Deferred. |
| `mixer-crossfader-transition` | Crossfader auto-transition arrows (beat-timed fade to A or B) and hold-to-cut | 4 | 3 | Not placed in a wave. |
| `mixer-gain-in-chain` | In-chain trim + fader (pre-EQ TRIM, post-FX FADER) so the recorder hears the faders; boost-capable trim | 5 | 4 | Risk 4. Faders would lag by the 250-750 ms sink buffer. Post-fader recording is solved in W9 via per-deck lanes with instant faders. Deferred until stems and the latency profile are proven on the tablet. |
| `mixer-fx-postfader-routing` | Echo routing: let the echo tail ring after the fader/crossfader closes (pre/post-fader FX) | 3 | 2 | Depends on in-chain faders, which are deferred. |
| `mixer-mono-output` | Mono output switch (fold the master to mono for single-speaker PAs) | 2 | 1 | Only meaningful with in-chain gain. Deferred with it. |
| `mixer-cue-split-output` | Headphone cue (PFL) via Split Output: master mono on LEFT, cue mono on RIGHT, with per-deck CUE buttons, cue volume and cue/mix | 5 | 4 | Valuable but needs in-chain gain plus a splitter cable or USB DAC to verify. Deferred to wave 11+ after gain-in-chain. |
| `mixer-deck-output-device` | Route a deck to a chosen output device (e.g. cue deck to Bluetooth/USB headphones, master deck to speaker) | 3 | 3 | Entirely OEM and device dependent (risk 4), narrower than split output. Deferred. |
| `mixer-master-bus-sink` | STRATEGIC: in-app master bus (custom AudioSink) for a real master limiter, master meter, sample-accurate post-fader recording and a true cue bus | 4 | 5 | Strategic rewrite (effort 5, risk 5) of the playback path. Do not start before a smaller in-chain approach proves out. Deferred. |
| `persist-analysis-cache` | Persistent analysis cache: waveform, spectrum, BPM, key and Auto Gain load instantly | 5 | 3 | Not placed in a wave. |
| `persist-saved-loops` | Eight saved loops per track (name, jump-and-arm, delete with undo) | 4 | 3 | Not placed in a wave. |
| `persist-settings-export-import` | Export and import settings as a file, with a change preview | 3 | 3 | Value 3. Settings roam via Auto Backup, and the new schema keeps them sanitised. Deferred. |
| `persist-cue-reset-and-clear` | Move or clear the saved main cue (it can currently never be corrected) | 4 | 1 | Not placed in a wave. |
| `input-pitch-bend` | Hold-to-bend pitch bend (momentary tempo nudge that returns by itself) for buttons, keyboard, MIDI and platter | 5 | 4 | Not placed in a wave. |
| `input-eq-kill` | EQ kill: hold a band to kill it momentarily (touch long-press, key, MIDI) and restore the previous value on release | 4 | 2 | Not placed in a wave. |
| `input-double-tap-reset` | Double-tap to reset a control (GAIN, FILTER, EQ band, crossfader, deck tempo) plus a documented gesture convention | 4 | 2 | Not placed in a wave. |
| `input-midi-core` | MIDI core: JVM-testable byte parser, mapping engine, MIDI Learn, mapping file codec, soft takeover and control coalescer | 4 | 4 | No controller available to verify, and the empty default map means no out-of-box support. Deferred until hardware is on hand. The action bus in W8 is the prerequisite and is kept. |
| `input-midi-usb` | MIDI over USB (Android MIDI API): device manager, hot-plug, Settings MIDI section with Learn and mapping import/export | 4 | 4 | Needs a device and a controller. Deferred together with MIDI core. |
| `input-platter-jog` | Platter and jog as a pitch-bend control (circular drag on the vinyl, relative MIDI jog ticks) | 2 | 3 | Value 2. Touch jog on a 60 dp platter is not realistic, and real scratching is infeasible on ExoPlayer. |
| `input-mouse-wheel` | Mouse-wheel (DeX / Chromebook) adjustment of hovered faders with sticky neutral detents | 2 | 2 | Value 2, only useful docked in DeX. Deferred. |
| `automix-play-queue` | Play Queue (Up Next list) with library swipe-to-add, reorder, shuffle, repeat, persistence | 5 | 3 | Not placed in a wave. |
| `automix-track-matcher` | Matching songs: BPM- and key-aware ranking, queue auto-fill, and a MATCH sort | 3 | 2 | Overlaps library-track-match and harmonic match in W5. Queue auto-fill can reuse that ranking later. |
| `automix-set-history` | Session history with 'played' tint, text/CSV export and recording-linked cue sheet | 3 | 3 | Not placed in a wave. |
| `automix-sampler-core` | Sampler engine core: voices, one-shot / hold / loop pads, choke groups, anti-click envelopes, soft limiter, synthesized starter kit | 3 | 4 | Separate subsystem (own AudioTrack, mixer, synthesised kit), value 3, effort 4. Deferred to a dedicated sampler roadmap after Automix. |
| `automix-sampler-pads-ui` | Sampler pads: low-latency AudioTrack output, 4x2 pad sheet, per-pad volume / mode / choke, master volume | 3 | 4 | Depends on the sampler core, and low-latency Java AudioTrack may need Oboe. Deferred. |
| `automix-sampler-user-samples` | Sampler user samples: import from file and capture the active loop / hot-cue region into a pad | 3 | 4 | Depends on the sampler UI, device-only decode path. Deferred. |
| `automix-sampler-tempo-follow` | Sampler loop/one-shot pads that follow the main deck's tempo (rate match with half/double folding) | 2 | 2 | Value 2 and depends on the other sampler items. Deferred. |
| `automix-background-keepalive` | Playback keep-alive foreground service so a hands-free Automix queue survives the screen turning off | 2 | 3 | Value 2, risk 4, the OS behaviour is unverified, and the Activity-scoped players die with the Activity anyway. Drop unless a device shows Automix needs it. |
| `record-library-browser` | Mixes screen: list, rename, delete, share, load onto a deck | 4 | 3 | A full Mixes screen with rename and MediaStore delete flows across API 29/30/33 is device-heavy. The general Library already lists saved mixes. Deferred. |
| `record-naming-metadata` | Editable mix name, name template, and real tags (MediaStore + WAV chunks) | 3 | 2 | Value 3. A naming template and WAV tags can follow once the review sheet exists. Deferred with it. |
| `record-review-trim-normalize` | Post-record review sheet: silence warning, auto-trim, manual trim, peak normalize, progress | 4 | 4 | Largest recording UI (effort 4). Auto-trim and normalise can be revisited after the tracklist export ships. Deferred. |
| `record-format-aac` | Save as AAC (M4A) with a bitrate choice | 4 | 3 | Depends on the review/export pipeline, which is deferred. MediaCodec/muxer glue is device-only. Deferred to wave 11+. |
| `record-format-flac` | Save as FLAC (lossless, about half the size) with embedded tracklist | 3 | 4 | Hand-written encoder (effort 4, value 3). Low value; deferred in favour of simple WAV. |
| `polish-slider-detents` | Magnetic centre detents (filter / EQ / crossfader) and double-tap-to-reset | 4 | 2 | Not placed in a wave. |
| `polish-a11y-semantics` | Accessibility pass: roles, labels, values and actions for TalkBack (and for uiautomator-driven device tests) | 3 | 3 | Needs a TalkBack walkthrough on a device; no Compose UI tests exist. Deferred, though real content descriptions would also help uiautomator scripts. |
| `polish-back-guard` | Back-press guard while a deck is playing or a mix is recording | 2 | 1 | Not placed in a wave. |
| `polish-theme-contrast-light` | Contrast-validated palette tokens, Light / System theme, readable secondary text | 3 | 4 | Effort 4 mechanical refactor of about 170 colour literals across 8 files; the Light theme is value 3. Deferred; the contrast-safe deck accents ship in W3. |
| `polish-library-row-state` | Library rows show what is loaded on Deck A/B and what you already played this session | 3 | 2 | Not placed in a wave. |
| `polish-platter-motion` | Platter spins at the track's real speed and stops where it is (no snap to 0 on pause) | 2 | 2 | Not placed in a wave. |
| `polish-onboarding-hints` | First-run tips and an empty-deck prompt (one at a time, never while mixing) | 2 | 3 | Value 2 and an annoyance for the owner. Drop. |
| `polish-about-whats-new` | About screen: version, bundled 'What's new' since last launch, open-source notices, copy-diagnostics | 2 | 3 | Value 2. Drop. |
| `polish-skip-low-value-ux` | RECOMMEND SKIP: string-resource externalisation, locale-safe number formatting, appearance extras, Chromebook UI | 1 | 5 | Its own spec recommends not building it (translations, locale formatting, appearance extras, Chromebook UI). |
