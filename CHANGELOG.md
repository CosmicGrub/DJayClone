# Changelog

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
