# tinyDJ for Android

A single-deck, motorized-reel audio player for Android. One big spinning disc you
grab with a finger to **scrub, scratch, and varispeed** FLAC and MP3 files, with
rich **haptics** that make the reel detents and buttons feel physical.

The audio core is a low-latency **C++/Oboe** engine: the whole file is decoded to
PCM in RAM and read through a fractional pointer, so scrubbing is sample-accurate
and the reel produces real **audible scratch** — forward and backward.

## Build (Docker — the verified path)

The whole toolchain is Dockerized (JDK 21, Android SDK 35, NDK 27, CMake 3.22.1,
Gradle 8.10.2), so you don't need a local SDK. From the repo root:

```sh
./build.sh
```

This fetches the decoder headers, builds the `tinydj-builder` image on first run
(point it at your Android SDK base image with `TINYDJ_BASE=<image> ./build.sh`, or
`docker tag <your-android-image> tinydj-base:latest` first), and produces:

```
app/build/outputs/apk/debug/app-debug.apk
```

The APK contains `libtinydj-audio.so` (the native engine) + `liboboe.so` +
`libc++_shared.so` for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

Install on a connected phone with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Build (Android Studio — alternative)

1. Fetch the decoders: `./app/src/main/cpp/third_party/download_dr_libs.sh`
2. Open the folder in **Android Studio** (Ladybug+), install **NDK `27.0.12077973`**
   and **CMake** via SDK Manager (change `ndkVersion` in
   [`app/build.gradle.kts`](app/build.gradle.kts) if yours differs), let it sync.
3. Run on your phone, or `./gradlew installDebug`.

> Test on a **physical Android phone** (min Android 12 / API 31). An emulator cannot
> reproduce the haptics or the low-latency audio — the whole point of the app — so
> test on real hardware with a good vibration motor (recent Pixel / flagship Samsung).

## Using it

The face is a faithful, de-branded clone of the reference hardware, so the
controls mirror it:

- **Long-press the mode capsule** (left edge, below the rocker) to open the library.
  **+ ADD** loads FLAC/MP3 files; they're remembered across launches (Storage Access
  Framework persisted permissions — no storage permission needed). Tap a track to load it.
- **play** starts playback; tap **play** again to **reverse** the direction (the glyph
  mirrors). **Hold the reel** to pause; release to continue. **stop** pauses where you
  are, then a second press rewinds to the start. **record** is a faithful prop (this is
  a player, not a recorder).
- **+ / −** skip to the next / previous track.
- Tap **mode**, then **turn the reel** to set varispeed (−50 … +200 %); it detents back
  to 0 at the centre. Tap **mode** again to leave.
- The **left rocker**: press-and-hold the top to fast-forward (`▲▲`), the bottom to
  rewind (`R▼`) — an audible scan.
- **Grab the reel** and drag to scrub/scratch: slow drags detent-tick, fast spins
  scratch (the haptics coalesce into a spin). Drag either way; backwards is real reverse.
- Roll the **knurled crown** (bottom-right) up/down for volume. Toggle **REPEAT** in the
  library sheet.

## How it fits together

```
ui/deck      DeckScreen, DeckContent (the device face), ReelDisc (the gesture+canvas
             core), TransportKeys / SideRocker / RotaryKnob / DeviceControls (the
             machined controls), MiniLcd (the OLED), DeckViewModel (StateFlow orchestration)
ui/library   LibrarySheet
core/audio   AudioEngine (the seam) · OboeAudioEngine (JNI) · FakeAudioEngine (previews)
core/haptics HapticEngine · SystemHapticEngine (capability detection + fallback) ·
             HapticThrottler (anti-flooding) · HapticSpec (the effect vocabulary)
data/library LibraryRepository · MetadataExtractor (SAF + MediaMetadataRetriever)
cpp/         native_audio_engine.cpp (Oboe stream + dr_libs decode + scratch DSP)
```

The UI/ViewModel depend only on the `AudioEngine` / `HapticEngine` interfaces, never
on Oboe or JNI types — so the engine can evolve (or be faked for `@Preview`) without
touching the UI.

## Tuning knobs

- Reel feel: `SECONDS_PER_REV`, `DETENTS`, `MAX_SPEED`, `SPIN_THRESHOLD` in
  [`ReelDisc.kt`](app/src/main/java/com/tinydj/ui/deck/ReelDisc.kt).
- Haptic intensity + per-effect mapping: [`SystemHapticEngine.kt`](app/src/main/java/com/tinydj/core/haptics/SystemHapticEngine.kt);
  detent rate-limit in [`HapticThrottler.kt`](app/src/main/java/com/tinydj/core/haptics/HapticThrottler.kt).
- Scratch interpolation / buffering: `native_audio_engine.cpp`.

## Status

**Builds cleanly** (verified via `./build.sh`): debug APK with the native engine
compiled for all three ABIs.

Implemented end to end: Oboe scratch engine (FLAC+MP3 decode, reverse, sample-accurate),
the reel gesture→scratch→detent loop, haptics with capability detection + fallback +
throttling, SAF library with persisted picks and metadata.

Not yet wired (intentional next steps):

- **Background playback / lockscreen transport** (a `MediaSessionService` + a
  `SimpleBasePlayer` adapter over the engine) — currently foreground only.
- **Audio focus / "becoming noisy"** (pause on call / unplug).
- **Windowed PCM buffer** for very long or 96 kHz files (today the whole track is
  decoded to RAM — fine for typical 3–6 min tracks).
- **Varispeed UI control** (the engine + `setSpeed` path exist; no slider yet).
- MediaStore "browse my music" mode (SAF picking is the current path).
