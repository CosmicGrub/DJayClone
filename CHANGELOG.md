# Changelog

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
