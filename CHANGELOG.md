# Changelog

## [v0.15.0](https://github.com/CosmicGrub/DJayClone/releases/tag/v0.15.0) - 2026-09-28

Two more items from the same audit that produced v0.14.0.

- **Fixed: Auto Gain silently overwrote the GAIN fader.** Since v0.13.0, loading a loud track moved the GAIN slider itself to the auto-calculated level - visually indistinguishable from you having dragged it there. Auto Gain's attenuation is now a separate internal trim: the GAIN slider always shows exactly what you set it to, and the trim is applied underneath. Verified on a real tablet: after loading a loud test tone, the GAIN slider stayed at full while `dumpsys media.audio_flinger` showed the deck's actual playback volume at 0.279083 - exactly `gain(1.0) x trim(0.3947) x crossfaderFactor(0.7071)`.
- **Fixed: seeking on a VBR MP3 could land noticeably off-target** (cues, loops and hot cues are all seeks). By default Media3's MP3 extractor estimates a seek position from the bitrate of whatever it has already decoded, or from an encoder's approximate seek-table header when present - both coarse. The extractor now builds an exact seek index instead (`Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING`, confirmed by inspecting the library's own bytecode: this switches it from the approximate `XingSeeker`/estimation path to the exact `IndexSeeker` regardless of whether a Xing/VBRI header is present). Every other container Media3 handles was already exact.

Also found along the way: recording an already-loud deck showed the recorded level was undiminished by GAIN, the crossfader, or Auto Gain - confirming the audit's separate suspicion that the mix recorder captures pre-fader audio. Not fixed here; tracked as its own item.

## [v0.14.0](https://github.com/CosmicGrub/DJayClone/releases/tag/v0.14.0) - 2026-09-28

**Audio-path correctness release.** Found by a code audit and confirmed on a real tablet by recording the app's own output and measuring it - before this release, moving FILTER to full low-pass left an 8 kHz tone at full level for ~9 s, and it collapsed (-65 dB) only when an unrelated tempo nudge happened to flush the audio chain.

- **FILTER, EQ and ECHO now take effect the instant you move them.** Media3 only re-checks whether an audio processor is active when the chain is flushed (a seek, cue jump, loop wrap, or tempo change), and these three reported themselves inactive while flat - so a knob move did nothing until something else flushed them. They now stay in the chain, keep their state warm, and are bit-exact when centred. Measured after: the same FILTER sweep silences 8 kHz within a fraction of a second, no flush needed; an EQ HIGH kill takes -23 dB off 8 kHz and leaves 200 Hz alone.
- **Fixed: ECHO switched off after being on could freeze the deck's audio.** The disabled echo returned without consuming its input, which spins the playback thread forever. Echo now ramps in over 5 ms and, when turned off, stops feeding new audio and lets its repeats ring out over about two taps before returning to a clean pass-through.
- **Echo no longer replays audio you never heard.** Media3 runs the audio chain up to 250-750 ms ahead of the playhead and throws that queued audio away on a seek, so an echo that kept its delay line across a loop wrap, hot-cue jump or scrub would play back the far side of the jump. Seeks now empty the delay line; a tempo nudge (which drains the chain into the live output first) keeps a ringing tail. Found by the pre-release review of this very change.
- **Hardened: a sink reset no longer zeroes the FILTER/EQ knob positions.** ExoPlayer resets the audio sink on stop/release and some stream changes (not on an ordinary track reload - a reload only flushes, measured on the tablet), and the processors used to forget the user's positions there while the deck UI kept showing them.
- **Fixed: tempo nudges and cue jumps no longer put a click into a filtered signal** - the filter/EQ state used to be zeroed on every flush.
- **Fixed: a fast flick of FILTER straight through centre no longer clicks.** The low-pass/high-pass tap swap used to happen at a buffer boundary while the filter was still fully in; it now fades to dry on the old side first.
- **Fixed: FILTER went silent on 22.05/24/32 kHz (and lower) sources** - its fixed 20 kHz open end is above Nyquist there. Cutoffs are now clamped below Nyquist.
- **Changed sound: the EQ shelf slope is now what the code always claimed it was** (RBJ S=1). LOW/HIGH kills reach their full -26 dB (60 Hz was -22.7 dB) and no longer leak about -3.5 dB into the mids. Expect kills to sound a little deeper and more decisive.
- **Fixed: echo and beat loops applied the playback speed twice.** They are sized from the track's analysed BPM in source time (echo sits before the speed stage; loop points are source positions), so a 1/2-beat echo or a 1-beat loop at 88% speed is now a true beat instead of 12% off.
- **Fixed: ECHO on a fresh deck ran a 1-sample comb filter** until a division chip was tapped; the delay is now seeded from the track's tempo when echo is armed.
- Tests: 25 new JVM pipeline tests drive the real processors through Media3's real audio pipeline (`FxPipelineTest`, `PipelineRig`), including a replay of `DefaultAudioSink`'s drive loop with a stall guard, seek-versus-tempo flush semantics, RBJ reference curves, neutrality, click guards and the time-base invariant through the real speed stage. Each guard was checked by mutation: re-introducing the old behaviour makes the intended test fail.
- Known limitation, not addressed here: the filter's knob is smoothed once per audio buffer, so a single instantaneous full-range jump steps the cutoff by octaves at once (a tone near the cutoff can click). Per-sample coefficient smoothing is a separate improvement. README corrected: `AudioAnalyzer` and `TempoSyncMath` are not yet unit-tested.

## [v0.13.1](https://github.com/CosmicGrub/DJayClone/releases/tag/v0.13.1) - 2026-09-12

- The track library no longer surfaces voice memos / call recordings (e.g. Samsung Voice Recorder's "Recordings" folder) alongside real music. MediaStore's audio collection doesn't distinguish speech from music on its own, so anything in a recognized voice-recorder-style folder is now filtered out of the scan. Found during an end-to-end integration pass, where a personal voice recording showed up as an ordinary-looking library row.

## [v0.13.0](https://github.com/CosmicGrub/DJayClone/releases/tag/v0.13.0) - 2026-09-12

- **Auto Gain** — on track load, analyzes the track's overall loudness (RMS) and normalizes the deck's GAIN toward a -12 dBFS ceiling. Attenuation-only by design (Android's `Player.volume` API is hard-capped at 1.0, so there's no way to boost past unity anyway) - a track already quieter than the ceiling is left untouched rather than clamped/boosted, which would risk clipping transients that were never near 0dBFS. On by default; toggle in Settings. The measured value is always cached alongside BPM/Key regardless of the setting; only *applying* it to the deck's gain is gated by the toggle.
- Verified on real hardware: a real music track measured well below the ceiling correctly triggered no change (cross-checked against an independent ffmpeg-decode + Python RMS/dBFS recomputation of the same source file), and a synthetic loud test tone with a known expected gain (~0.395) correctly triggered visible attenuation, confirmed both visually and via pixel-level measurement of the GAIN slider's fill.

## [v0.12.3](https://github.com/CosmicGrub/DJayClone/releases/tag/v0.12.3) - 2026-09-12

- Fixed a second live-mix recording in the same app session silently never recording anything (a stale `Job` reference blocked capture from restarting) - found while gathering audio evidence for Key Lock's pitch-preservation behavior.

## [v0.12.2](https://github.com/CosmicGrub/DJayClone/releases/tag/v0.12.2) - 2026-09-12

- Fixed TableTop's RECORD and CROSSFADER controls being completely unreachable (no scroll mechanism on the center column) - found on the first real physical-fold test of a Z Fold 5.

## [v0.12.1](https://github.com/CosmicGrub/DJayClone/releases/tag/v0.12.1) - 2026-09-01

- Library search now matches a track's detected Camelot key (exact match, e.g. `8B`) alongside title/artist.
- Added this CHANGELOG, a README, and `.gitattributes` to the repo.

## [v0.12.0](https://github.com/CosmicGrub/DJayClone/releases/tag/v0.12.0) - 2026-09-01

- **TableTop arrangement** (Stage 10c) — a posture-aware layout for a foldable opened to a horizontal-hinge "laptop" angle and set on a table. Splits at the real hinge position (live sensor data, not a guessed midpoint): a "glance" zone above (both decks' track name/waveform/BPM/key) and a "touch" zone below (transport, EQ, filter, cue/loop/hot cues, nudge, sync, crossfader, recording). Overrides the normal width-based arrangement choice whenever the device reports this posture.
- Debounced the underlying fold-angle signal so mid-fold gestures don't flicker the whole UI.
- Not yet verified against a real physical fold.

## [v0.11.0](https://github.com/CosmicGrub/DJayClone/releases/tag/v0.11.0) - 2026-09-01

- **Key Detection** — analyzes each track's harmonic key (Krumhansl-Schmuckler profile matching over a chromagram) and displays it in Camelot notation (e.g. `8B`, `5A`). Shown on both decks and in the library's KEY column, sortable by real Camelot wheel position. Backed by real unit tests, including synthetic-chord-tone tests through the full FFT → chromagram → correlation pipeline.
- **Key Lock** ("Master Tempo") — keeps a track's pitch locked to its original value while tempo-sync or nudge changes its speed, using Media3's built-in pitch-independent time-stretching.
- **Tempo-Sync v1** — ratio-aware beat-sync recognizing half-time/double-time relationships (e.g. 140 BPM vs. 70 BPM) instead of only forcing 1:1 matches, with an AUTO/1×/2×/½× override and an exposed tolerance setting.
- 3-band EQ, spectral-colored waveform, VU-style level metering.

## v0.7.0 - 2026-08-25

Initial public release. Playback and dual-deck mixing, BPM sync, cue/loop/hot-cues, live-mix recording and export, filter/echo FX, adjustable settings, a MediaStore-backed track library, and adaptive tablet/foldable layout (Compact/Medium/Expanded).
