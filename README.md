# Precord

An **audio-only instant replay recorder** for Android — ShadowPlay for your microphone.

A foreground service keeps the mic open and writes into a circular buffer in RAM (1 min … 60 min, or custom).
When something worth keeping happens, hit **SAVE REPLAY** (app, notification, Quick Settings tile, shake, or
Volume Up+Down) and the last *N* minutes are written to a permanent file — while the buffer keeps running.

```
AudioRecord ──PCM16──▶ Rust engine ──▶ gain / noise-gate ──▶ meters + scope
 (capture thread)            │
                             └──▶ lock-free ring buffer (calloc'd, pages only touched as they fill)
                                        │  snapshot (seqlock-validated, never blocks the audio thread)
                                        ▼
                      normalise ──▶ WAV (Rust)  |  M4A-AAC / Ogg-Opus (MediaCodec, fed PCM by Rust)
```

## Layout

| Path | What |
|---|---|
| `rust-core/` | `precord_core` cdylib: `ring.rs` (lock-free SPSC ring + seqlock snapshots), `engine.rs` (DSP, meters, scope, normalise, WAV export), `wav.rs` (header, peaks, lossless trim), `jni_bridge.rs` (JNI). 17 unit tests. |
| `app/` | Kotlin + Jetpack Compose. `core/` (engine facade, settings, clip library, encoders), `service/` (foreground service, QS tile, shake, volume combo), `ui/` (theme, deck, library, trim editor, settings). |

## Build

Prerequisites: JDK 17, Android SDK (API 35) + **NDK**, Rust (rustup).

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<installed-version>

cargo test --manifest-path rust-core/Cargo.toml     # engine tests, runs on the host
./gradlew assembleDebug                              # runs `cargo ndk` first, then Kotlin → app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Emulator (x86_64): `./gradlew assembleDebug -Pprecord.abis=arm64-v8a,armeabi-v7a,x86_64`
(also `rustup target add x86_64-linux-android`).

## Using it

* **Deck** – live scope + 40-segment RMS/peak meter (levels computed in Rust), buffer ring (`03:42 / 05:00`),
  length pills (`30s 1m 5m 15m 30m 60m CUSTOM` – the custom dialog is a detented scrubber with haptic ticks),
  exact RAM estimate (`5 min @ 48 kHz MONO = ~28.8 MB RAM`), **SAVE REPLAY**, and *Save last* 30 s / 1 m / 5 m quick-trim.
* **Triggers** – notification action (works from the lockscreen), Quick Settings tile (toggle monitoring),
  shake ×3, Volume Up+Down (needs the accessibility service, enabled from Settings → *Enable service*).
* **Library** – `REC_2026-11-16_03-33-12.wav` style names, duration/size, editable title + tags,
  waveform with draggable start/end trim (new copy or replace), play selection, Share sheet,
  export to `Recordings/Precord` (or auto-export every save).
* **Settings** – 16 / 44.1 / 48 kHz, WAV / M4A / Opus, peak-normalise on save (to −1 dBFS, max +30 dB),
  input gain, noise gate.

## Design notes / honest caveats

* **Encoders.** WAV is produced entirely in Rust, streamed from the ring (no second copy in RAM). AAC and Opus are
  *not* implemented in Rust: there is no production-grade pure-Rust AAC encoder, and linking libopus for two ABIs
  would make the build far more fragile. Instead Rust hands DSP-processed, normalised PCM to Android's
  `MediaCodec`/`MediaMuxer` in 4096-sample chunks. If a device lacks the encoder the clip is saved as WAV and you are told.
  Opus is offered at 16/48 kHz only (what the codec supports). Trimming WAV is Rust; trimming M4A/Ogg is a lossless re-mux.
* **Mono, 16-bit.** RAM = `seconds × rate × 2 B`. 60 min @ 48 kHz is 345 MB — the UI warns above 200 MB; use 16 kHz
  (115 MB) for hour-long voice buffers. Pages are zero-allocated, so memory is only consumed as the buffer fills.
* **Changing length or sample rate restarts the buffer** (old samples can't be reinterpreted).
* **Ring buffer.** Single producer, wait-free; `claimed`/`write_pos` seqlock lets saves read the oldest audio while
  capture continues; a snapshot that was overtaken is rejected rather than saved corrupted. 2 s of slack beyond the window.
* **Background starts.** Android 14+ blocks microphone foreground services started from the background. Arming works from
  the app, the tile (falls back to opening the app) and notification actions on an already-running service.
* **Screen-off triggers** keep a partial wake lock while armed (needed for reliable sensor/key delivery) — expect battery cost.
* **Verification status.** `cargo test` (17 tests) passes and the crate type-checks for `aarch64-linux-android` and
  `armv7-linux-androideabi`. The Kotlin code (core, service, all Compose UI) was compiled against the real API 35
  `android.jar` and the Compose 1.7 libraries, but the full Gradle/AGP build, manifest/resource packaging, `cargo ndk`
  and on-device behaviour were **not** run in the authoring sandbox (no access to Google's SDK/Maven). The first
  `./gradlew assembleDebug` on a real machine is the real test; CI config is in `.github/workflows/android.yml`.
