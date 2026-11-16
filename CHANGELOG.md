# Changelog

## 1.1.0
* **Languages:** English, Russian, Ukrainian (system language; per-app picker on Android 13+).
* **Design:** adaptive phone/tablet/landscape layout, 48 dp touch targets, TalkBack semantics (roles, selected/toggle state), waveform tap-to-seek, library search.
* **Functionality:** raw-microphone mode (no AGC/noise suppression), privacy-policy link, version in About.
* **Optimisation:** meters throttled to 1 Hz when the UI is hidden, wake lock only for shake-to-save, allocation-free JNI hot paths.
* **Distribution:** `full` / `play` flavors (Play build has no accessibility service), AAB output, upload-key signing via env/secrets, release workflow publishes APK + AAB.
* **Docs:** user guide (en/ru), architecture, build, releasing, Play Store checklist, store listings (en/ru/uk), privacy policy.

## 1.0.0
Initial release: Rust ring-buffer engine, foreground service, deck/library/trim UI, notification, tile, shake and volume-combo triggers.
