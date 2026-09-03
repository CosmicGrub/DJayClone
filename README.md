# DJayClone

A dual-deck DJ mixing app for Android, built with Kotlin, Jetpack Compose, and Media3 (ExoPlayer).

Personal project inspired by djay Pro's layout/workflow (not affiliated with or endorsed by djay Pro/Algoriddim). Not on the Play Store — built for direct sideload install on your own device.

## Features

- **Dual-deck mixer** — independent playback, gain, and crossfader, with layouts that adapt across phone, tablet, and foldable form factors (including a posture-aware arrangement for a foldable opened flat on a table).
- **3-band EQ** (low/mid/high) and a filter (LPF/HPF sweep) per deck, plus an echo/delay effect.
- **Tempo-Sync** — ratio-aware beat sync that recognizes half-time/double-time relationships (e.g. a 140 BPM track against a 70 BPM track), not just 1:1 matches, with an AUTO/1×/2×/½× override.
- **Key Detection** — analyzes each track's harmonic key (Krumhansl-Schmuckler profile matching over a chromagram) and shows it in Camelot notation (e.g. `8B`, `5A`) for harmonic mixing.
- **Key Lock** ("Master Tempo") — keeps a track's pitch locked to its original value while tempo-sync or nudge changes its speed.
- Spectral-colored waveform display, VU-style level metering, hot cues, manual/beat-multiple loops.
- Live-mix recording (captures the mixed output, not per-deck) and export.
- A browsable track library (MediaStore + manually-imported files), sortable by title/artist/date/duration/BPM/key.
- Adjustable settings: FX ranges, hot cue pad count (4/8/16), tempo-sync tolerance, nudge step sizes, and more.

## Requirements

- Android 8.0 (API 26) or newer.
- Android Studio / JDK 17 to build from source.

## Building

```bash
./gradlew assembleDebug     # debug build
./gradlew assembleRelease   # debug-signed release build, installs directly via adb/sideload
./gradlew testDebugUnitTest # unit tests (pure-Kotlin DSP/math modules - no device needed)
```

Install a built APK directly:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Prebuilt APKs are also attached to each [GitHub Release](../../releases).

## Project structure

The app is a single Gradle module (`app/`). A few entry points worth knowing about:

- `MainActivity.kt` — the mixer UI and its three width/posture-based arrangements (Compact, Medium/Expanded, TableTop).
- `DeckViewModel.kt` — per-deck playback state and the ExoPlayer/Media3 wiring.
- `DjFxRenderersFactory.kt` — the per-deck audio FX processor chain (level → EQ → filter → echo).
- `AudioAnalyzer.kt` / `Fft.kt` / `KeyDetector.kt` / `TempoSyncMath.kt` — the audio-analysis/DSP layer. These have no Android dependencies and are covered by real JVM unit tests (`app/src/test`).
- `TrackLibraryRepository.kt` / `TrackCacheRepository.kt` — the track library (MediaStore-backed) and its small local cache (BPM, key, thumbnails).
- `DjAdaptive.kt` — the adaptive-layout signals (window size class and foldable posture) that drive which mixer arrangement renders.

## License

[MIT](LICENSE)
