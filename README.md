# Scratch

Scratch is a vinyl-first two-deck DJ app for Android. It is built with Kotlin and Jetpack Compose for the UI, and a native C++ audio engine running on [Oboe](https://github.com/google/oboe) for low-latency playback.

![Scratch running on an Android phone](docs/screenshot.png)

![Graphical EQ with pre/post spectra](docs/eq.png)

![Persisted autoplay and crossfade settings](docs/settings.png)

## Status

This is an early development build, exercised on a physical Android 15 device. The core two-deck engine, scratching, MP3/FLAC/OGG decoding, tempo/key lock, filters, loops, SQLite library, search, and queues are working.

### Working today

- Immersive full-screen landscape UI with dual rotating platters, waveforms, and a center mixer.
- Oboe audio engine: one 48 kHz float output stream, low-latency performance mode, exclusive mode attempted first with shared-mode fallback.
- WAV and AIFF/AIFC (uncompressed) playback: 8/16/24/32-bit PCM, 32-bit float WAV, mono or stereo, including AIFC `NONE`/`sowt`.
- MP3, FLAC, OGG/Vorbis, and Opus decoding through `MediaExtractor` + `MediaCodec`.
- Long compressed tracks are decoded once into a 16-bit stereo PCM cache, then memory-mapped into the native engine. A 59:57 mix works with instant seeking and scratching anywhere in the track; the cache is reused on later loads.
- Decode progress is shown on the deck while the cache is being built.
- Key lock works at source rates that differ from the engine rate (e.g. 44.1 kHz into a 48 kHz engine) by resampling into the stretcher natively.
- Load-time cubic resampling for WAV/AIFF.
- Vinyl scratching: dragging the platter drives a variable-rate read head with cubic interpolation, reverse playback, rate smoothing, and start/end clamping.
- When paused, the platter acts as a jog wheel: it moves the playhead silently.
- Tempo slider (50%–200%) with optional key lock. Key lock uses WSOLA time-stretching; without key lock, tempo changes repitch the track like a turntable.
- Per-deck filter with explicit LP / OFF / HP buttons and a logarithmic 20 Hz–20 kHz cutoff slider. Center OFF is a true bypass.
- Per-deck 8-node graphical EQ: draggable low shelf, six peaks, and high shelf, each up to ±18 dB, with per-node Q. The editor draws the live magnitude curve plus pre-EQ and post-EQ spectra. FLAT resets gains, BYPASS removes the EQ from the chain while preserving settings, and named presets are saved to SQLite and can be loaded on either deck. EQ runs before the DJ filter.
- Master 32-band logarithmic spectrum analyzer in the mixer strip, fed from the final mixed output.
- Equal-power crossfader.
- Per-deck transport: PREV, PLAY/PAUSE, NEXT. NEXT pulls from the deck queue; PREV uses per-deck play history.
- BPM analysis on first load using a native onset/flux detector, persisted in SQLite. BPM is shown in the deck header and search results.
- Tempo SYNC matches the other deck’s effective BPM (including its tempo shift) and enables key lock automatically.
- Looping: one-tap LOOP creates and enables a 4-second loop at the playhead; IN and OUT set custom loop points; CLR clears. The active loop is drawn on the waveform.
- Waveforms computed from 2048 peak buckets at load and normalized for display.
- Local library indexed into SQLite, with case-insensitive title/artist/album search and escaped SQL wildcards.
- Search overlay with one-tap add to Deck A or Deck B queues.
- Per-deck queues are persistent playlists with a cursor: playing a track no longer removes it, and PREV/NEXT move backward/forward through the queue. The current track is highlighted in the queue overlay.
- Queue lists can be saved as named presets (Deck A, Deck B, or A+B) in SQLite and reloaded later. Loading A-only or B-only preserves the other deck’s queue.
- Settings screen with persisted Autoplay Deck A and Autoplay Deck B toggles, plus a persisted crossfade-duration value. Autoplay controls whether the deck advances to the next queued track at the end.
- Tap the waveform to seek.

### Known limitations

- WAV/AIFF tracks are still parsed and held in memory; only compressed formats use the disk-backed cache yet.
- The first load of a very long compressed track takes time (about two minutes for an hour-long MP3 on the test phone) while it decodes into the cache. Subsequent loads are fast. The PCM cache is capped at 3 GB and pruned at startup.
- BPM is analyzed the first time a track is loaded, not as a full library pre-analysis pass yet. Tempo SYNC does not phase/downbeat-align decks yet; beatgrid sync is next.
- Cue points, hot cues, and slip mode are not implemented.
- No audio focus, foreground playback service, or notification controls yet.
- True overlapping crossfade is not implemented yet; the crossfade duration is persisted, but it needs a second playback source per deck. Autoplay currently advances with the deck’s normal fade-in.
- Reported latency on the test phone varies between roughly 40–125 ms depending on the callback; Bluetooth output is not suitable for DJ use.
- The search keyboard floats over the results on some IMEs; the A/B queue buttons are deliberately kept on the left so they stay reachable.

## Controls

| Control | Action |
| --- | --- |
| Platter | While playing: scratch/nudge/spin back. While paused: jog the playhead silently. |
| PREV | Load the previous track from this deck's history, or restart if history is empty. |
| PLAY/PAUSE | Start or stop the deck. |
| NEXT | Load and play the next queued track. |
| Waveform | Tap to seek. Loop region is highlighted when active. |
| LP / OFF / HP | Select the filter type explicitly. |
| FILTER slider | Cutoff from 20 Hz to 20 kHz, logarithmic. |
| EQ | Open the graphical EQ editor; drag nodes for frequency/gain, select a peak for Q, FLAT resets gains. |
| Spectrum | Master mix spectrum in the mixer; pre/post deck spectra in the EQ editor. |
| SETTINGS | Autoplay A/B and crossfade-duration settings; persisted across launches. |
| TEMPO | 50%–200% playback rate. |
| SYNC | Match the other deck’s effective BPM and enable key lock. |
| KEY | Enables WSOLA key lock; tempo changes no longer repitch. |
| IN / OUT | Set the loop start/end at the current playhead, and enable the loop. |
| LOOP | Toggles the loop. With no loop points, creates and enables a 4-second loop at the playhead. |
| CLR | Clears loop points and disables looping. |
| CROSSFADER | Equal-power mix between Deck A and B. |
| SEARCH + QUEUE | Search the SQLite index and add results to either deck queue. |
| Queue bar | Open a deck queue; play or remove tracks, navigate with PREV/NEXT, and save/load A, B, or A+B queue presets. |

## Architecture

```
app/src/main/java/com/tkleisas/scratchdj/
  MainActivity.kt          Full-screen activity, permissions, lifecycle
  DjController.kt          UI state, library sync, queues/history, engine polling
  audio/DjEngine.kt        JNI facade
  audio/PcmDecoder.kt      MediaExtractor/MediaCodec decode
  audio/PcmCache.kt        Disk-backed PCM cache and pruning
  data/SettingsStore.kt    Persisted autoplay/crossfade settings
  data/QueuePresetStore.kt SQLite queue-list presets
  audio/SyncMath.kt        Tempo-sync ratio math
  data/                    MediaStore scan, SQLite DB, repository, queue
  model/                   DeckSide, DeckUiState
  ui/                      Compose screens, platters, waveforms, overlays
app/src/main/cpp/
  jni_bridge.cpp           JNI entry points and engine lifetime
  audio/AudioEngine.*      Oboe stream, mixing, crossfader, mastering
  audio/Deck.*             Transport, scratch/jog, tempo/key lock, filters, loops
  audio/TrackLoader.*      WAV/AIFF parsing, resampling, PCM16/mmapped track builder
  audio/BpmAnalyzer.*      Onset/flux BPM estimation over any track source
  audio/SpectrumAnalyzer.* Master and per-deck FFT spectrum analyzers
  audio/Svf.h              State-variable DJ filter
  audio/EqualPower.h       Crossfader law
  third_party/yawn/        Vendored MIT DSP from the YAWN project
app/src/test/cpp/          Host GoogleTest suite for native DSP
app/src/test/java/         JVM unit tests
app/src/androidTest/       Device SQLite, decoder, and native PCM tests
```

The Compose layer polls `DjEngine` every 33 ms for transport state; all sample-rate work, scratching, filtering, time-stretching, and looping happens on the native audio thread. Track loading, MediaStore scanning, and compressed decoding run on `Dispatchers.IO`. Track data is handed to the audio thread through a mutex-protected pending slot that the callback claims with `try_lock`, so the audio thread never blocks on file I/O or decoding.

WAV/AIFF files are parsed and resampled natively into float samples. Compressed formats are decoded to a source-rate 16-bit stereo PCM cache file in Kotlin, then memory-mapped into the native engine with JNI. The native deck reads float, heap PCM16, or mapped PCM16 through the same interpolation path, so scratch/jog/seek work across the whole track without loading it into RAM. The decoder fast-path writes MediaCodec PCM straight to disk when the output is 16-bit stereo, and falls back to conversion/resampling otherwise.

The SQLite schema stores one row per MediaStore audio item:

```sql
CREATE TABLE tracks (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    album TEXT NOT NULL,
    display_name TEXT NOT NULL,
    extension TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    uri TEXT NOT NULL
);
```

Title and artist are indexed, and search uses `LIKE ... ESCAPE '\'` with user wildcards escaped.

## Reused DSP

The `app/src/main/cpp/third_party/yawn/` directory contains code copied from [YAWN](https://github.com/tkleisas/yawn) (MIT licensed, same author):

- `audio/TimeStretcher.{h,cpp}` — WSOLA, phase vocoder, and PGHI time-stretching. Scratch uses WSOLA for key lock.
- `audio/TransientDetector.{h,cpp}` — onset/transient detection and BPM estimation, ready for import-time analysis.
- `audio/AudioBuffer.h` — buffer type used by the detector.
- `effects/Biquad.h` — reusable biquad, kept for EQ/filter work.
- `LICENSE.txt` — YAWN's MIT license.

Two minimal compatibility edits were applied: the vendored headers reference YAWN's `kDefaultSampleRate` constant, so the defaults were changed to `48000.0` to keep the files self-contained.

YAWN also has `Limiter` and `TapeEmulation` (wow/flutter, saturation, hiss) that are good candidates for the Scratch master bus and vinyl character pass.

## Build

Requirements:

- JDK 17 or newer.
- Android SDK with API 36, build-tools, platform-tools.
- NDK `27.2.12479018` (or adjust the installed NDK).
- CMake `3.22.1`.
- Gradle wrapper `8.12` (included).

Set the SDK path in `local.properties` (not committed):

```properties
sdk.dir=/path/to/Android/Sdk
```

Build and install:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If `JAVA_HOME` is not set, point it at a JDK 17+ installation, for example Android Studio's bundled JBR:

```bash
export JAVA_HOME="$HOME/Apps/android-studio/jbr"
```

## Tests

This project is developed test-first where the code allows it.

Native DSP and file parsing (50 GoogleTest cases):

```bash
cmake -S app/src/test/cpp -B build/native-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build/native-tests -j
ctest --test-dir build/native-tests --output-on-failure
```

The native suite covers WAV/AIFF parsing, truncation and wildcard cases, resampling, decoded PCM16 and mmap-backed tracks, scratch/jog behavior, key-lock tempo and reverse scratch, key lock at mismatched source rates, filter attenuation and bypass, EQ attenuation, bypass and response curves, loop wrapping, crossfader law, BPM detection, spectrum band mapping, and the vendored YAWN time-stretcher/transient detector.

JVM unit tests (31 JUnit cases):

```bash
./gradlew testDebugUnitTest
```

Covers scratch angular-velocity math, format detection, persistent queue navigation, SQL LIKE escaping, time formatting, tempo-sync ratios, and EQ preset serialization.

Device tests (18 cases, real device or emulator):

```bash
./gradlew connectedDebugAndroidTest
```

Covers SQLite track search/BPM/EQ/queue preset persistence, SharedPreferences settings persistence, the MediaCodec MP3 decode, in-memory and disk-cache decoding, mmap-backed JNI loading, waveform generation, and native BPM analysis. Note that `connectedDebugAndroidTest` may uninstall the app when it finishes; reinstall the debug APK afterwards.

## Roadmap

1. Full-library background analysis, beatgrid/downbeat detection, and phase-aligned SYNC.
2. Cue points, hot cues, and slip mode.
3. Disk-backed WAV/AIFF support and a cache-size/usage UI.
4. Foreground service, audio focus, media session, and notification controls.
5. Master limiter, vinyl wow/flutter, and better anti-aliasing at extreme scratch rates.
6. Latency measurement and device-specific audio tuning.

## Desktop Linux (in progress)

The chosen direction is Compose Multiplatform so the UI can be shared:

```
:shared          KMP domain models, queues, sync/scratch math, EQ definitions
:androidApp      Android UI, MediaStore, MediaCodec, Oboe
:desktopApp      Compose Desktop scaffold (Linux)
:audio-native    shared C++ engine + JNI for Android and Linux
```

A first KMP scaffold is in place: pure Kotlin domain logic now lives in `:shared` for the Android and JVM/desktop targets, and `:desktopApp` compiles a minimal Compose Desktop window. The next steps are moving the Compose deck UI into a shared module, adding a JACK/PipeWire audio backend, and replacing MediaCodec with FFmpeg on Linux, writing to the same PCM cache format. Packaging is planned via jpackage/AppImage/Flatpak.

## Versioning

Versions are derived from annotated git tags of the form `vX.Y.Z`:

- Exact tag: `versionName = X.Y.Z`
- Commits after the tag: `X.Y.Z+N.gHASH`, where `N` is commits since the tag
- Dirty working tree: appends `.dirty` build metadata
- `versionCode = major*10,000,000 + minor*100,000 + patch*1,000 + min(commitsSinceTag, 999)`

The version is shown in the Settings screen and used by both the Android and desktop builds. The first release is tagged `v0.1.0`.

To cut a release:

```bash
git tag -a v0.1.1 -m "Scratch v0.1.1"
git push origin v0.1.1
```

## License

Scratch is released under the GNU General Public License, version 3 or later. See [LICENSE](LICENSE).

Vendored YAWN DSP is MIT licensed; its license is preserved at `app/src/main/cpp/third_party/yawn/LICENSE.txt`. Oboe is Apache 2.0, and AndroidX/Compose libraries are Apache 2.0.
