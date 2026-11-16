# Changelog

## 1.3.0
* **Remove phone audio (new engine):** the app now receives the exact audio the phone is playing (Android AudioPlaybackCapture) and subtracts it from the microphone with an adaptive filter written in Rust (delay tracking + NLMS with double-talk protection). Replaces the old call-mode approach that also ducked the user's voice.
* **No more conflicting settings:** one *Microphone mode* choice (Standard / Noise reduction / Remove phone audio / Raw) instead of independent switches; old settings are migrated.
* **Status bar instead of an overlay:** the floating bubble and the "display over other apps" permission are gone. Android 16+ shows the buffered time as a status-bar chip (Live Update); older versions keep the system mic indicator + notification.
* **Deck fits the screen:** flexible layout without scrolling on phones; quick-save keys and the arm switch share one row.
* **Transcription:** language picker, offline-pack status and download from the app, optional online fallback, "transcribe again".
* **Visual conventions:** destructive actions (delete, replace original, stop buffer) are red and ask for confirmation with Cancel / Confirm dialogs; errors are red, success is green; orange stays reserved for recording and SAVE.
* Settings regrouped (Capture / Sound / Saving / Triggers / Transcription / Keep alive). Scope fade, ring write-head marker.
* Rust: 24 unit tests.

## 1.2.0
* **Noise reduction:** platform noise suppressor + 90 Hz rumble filter (Rust biquad).
* **Voice boost:** presence EQ (+4 dB @ 2.8 kHz) and slow auto-level (up to +18 dB, frozen below a noise floor so hiss is not pumped), soft limiter.
* **Suppress device playback:** call-mode microphone + acoustic echo canceller removes video/music playing on the same phone (effect depends on the device).
* **Transcript:** on-device speech-to-text of saved clips (Android 13+, offline speech pack, no internet), searchable in the library.
* **Recording bubble:** optional floating pill with buffered time at the top of the screen; tap saves. Needs "display over other apps".
* **Keep alive:** `stopWithTask=false`, automatic microphone re-open on read errors, re-arm when the app is opened after the system killed the buffer, battery-optimisation shortcut.
* Rust: 20 unit tests (HPF, AGC floor, limiter added).

## 1.1.0
* **Languages:** English, Russian, Ukrainian (system language; per-app picker on Android 13+).
* **Design:** adaptive phone/tablet/landscape layout, 48 dp touch targets, TalkBack semantics (roles, selected/toggle state), waveform tap-to-seek, library search.
* **Functionality:** raw-microphone mode (no AGC/noise suppression), privacy-policy link, version in About.
* **Optimisation:** meters throttled to 1 Hz when the UI is hidden, wake lock only for shake-to-save, allocation-free JNI hot paths.
* **Distribution:** `full` / `play` flavors (Play build has no accessibility service), AAB output, upload-key signing via env/secrets, release workflow publishes APK + AAB.
* **Docs:** user guide (en/ru), architecture, build, releasing, Play Store checklist, store listings (en/ru/uk), privacy policy.

## 1.0.0
Initial release: Rust ring-buffer engine, foreground service, deck/library/trim UI, notification, tile, shake and volume-combo triggers.
