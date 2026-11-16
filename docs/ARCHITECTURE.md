# Architecture

```
AudioRecord ─PCM16─▶ Rust engine ─▶ gain / noise-gate ─▶ meters + scope
 (capture thread)         │
                          └─▶ lock-free ring buffer (zero-allocated, pages touched as they fill)
                                   │ seqlock-validated snapshot (never blocks capture)
                                   ▼
                  normalise ─▶ WAV (Rust)  |  M4A-AAC / Ogg-Opus (MediaCodec fed PCM by Rust)
```

## Rust (`rust-core/`)

| File | Responsibility |
|---|---|
| `ring.rs` | Single-producer circular buffer of `i16`. Absolute 64-bit positions, `claimed`/`write_pos` seqlock. Readers copy then verify they were not overtaken; overtaken snapshots are rejected, never saved corrupted. Capacity = window + 2 s slack. Storage via `alloc_zeroed` so untouched pages cost no RAM. |
| `engine.rs` | `Engine::process` (audio thread, allocation-free): gain, noise gate (envelope follower, 4 ms attack / 60 ms release, -30 dB floor), 20 ms RMS/peak windows, 160-point scope. `plan` computes the snapshot range and normalisation gain (-1 dBFS target, max +30 dB). `save_wav` streams to disk without a second copy of the buffer. |
| `wav.rs` | PCM16 header read/write, waveform peaks, lossless trim. |
| `jni_bridge.rs` | `extern "system"` functions for `com.precord.app.core.NativeBridge`. Never throws; returns status codes. Hot paths use stack buffers (no per-call heap allocation). |

17 unit tests: wrap-around, overwrite rejection, a concurrent producer/reader torn-read test, gate, normalisation, WAV round trips, trim.

### DSP chain (per sample, `Engine::process`)
`90 Hz high-pass (optional) → presence peaking EQ (voice) → noise gate → user gain → AGC (voice, frozen below −48 dB floor) → soft limiter → ring buffer`. Platform effects (NoiseSuppressor, AcousticEchoCanceler, VOICE_COMMUNICATION source) are attached in `PrecordService.attachEffects`.

## Android (`app/`)

| Package | Responsibility |
|---|---|
| `core/` | `ReplayEngine` (safe facade, RW-lock so `close()` can't free memory under a call), `SettingsStore`, `ClipRepository` (+ `index.json` for titles/tags), `ClipEncoder` (WAV in Rust; AAC/Opus via MediaCodec/MediaMuxer, WAV fallback), `ClipEditor` (waveform peaks, lossless trim/remux), `ClipExporter` (MediaStore `Recordings/Precord`, share intent), `Monitor` (process-wide state/event bus), `Fmt` (localised units). |
| `service/` | `PrecordService` (foreground service type `microphone`; owns AudioRecord + engine; notification actions), `QuickTileService`, `ShakeDetector`, `ServiceController`. |
| `ui/` | Compose UI: `DeckScreen` (adaptive phone / tablet layout), `LibraryScreen` (search), `ClipDetailScreen` (waveform, tap-to-seek, trim handles), `SettingsSheet`, theme + components. |
| `src/full/` | Flavor-only code: `VolumeComboService` (accessibility key-event filter). Not shipped in the Play flavor. |

### Flavors
* `full` – GitHub APK, includes the optional Volume Up+Down accessibility trigger.
* `play` – Google Play bundle without any accessibility service (Play policy restricts the Accessibility API to accessibility use).

### Power & performance
* UI visible: meters/scope at 25 Hz. UI hidden: 1 Hz bookkeeping only.
* The partial wake lock is held **only** while *Shake to save* is on.
* Audio capture thread runs at `THREAD_PRIORITY_URGENT_AUDIO`; JNI write path copies through a fixed 2048-sample stack buffer.
* Memory = `seconds × rate × 2 B` (mono PCM16); e.g. 5 min @ 48 kHz ≈ 28.8 MB.

### Localisation
`values/` (English), `values-ru/`, `values-uk/`. Units (`s/с`, `kHz/кГц`, `MB/МБ`) are resources, loaded into `Fmt` on start and on configuration change. `locales_config.xml` enables the per-app language picker on Android 13+.
