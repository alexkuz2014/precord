# Google Play submission guide

Status: the app is **technically ready for an internal-testing upload**; the items marked ☐ need the account owner.

## 1. Before you start
- ☐ Google Play developer account (one-time fee; personal accounts created after Nov 2023 must run a **closed test with ≥12 testers for 14 days** before production access).
- ☐ Create the upload key and CI secrets - [RELEASING.md](RELEASING.md). Enrol in **Play App Signing**.
- ☐ Decide the final `applicationId` (currently `com.precord.app`). It cannot be changed after first upload; if you want your own namespace change `namespace`/`applicationId` in `app/build.gradle.kts` **before** publishing (package directories can stay).
- ☐ Host the privacy policy: use `https://github.com/mantisfett/precord/blob/main/docs/PRIVACY_POLICY.md` (already linked from the app) or your own page.

## 2. Build
Use the **`play` flavor** AAB: `./gradlew bundlePlayRelease` (or the release workflow artifact `precord-play-vX.Y.Z.aab`). It contains no accessibility service. Target SDK 35, min SDK 29, ABIs arm64-v8a + armeabi-v7a, 16 KB page-size note below.

> **16 KB page size:** Play requires native libs aligned for 16 KB pages for apps targeting Android 15+. Rust/NDK r27+ with AGP 8.5+ produces compatible `.so` files automatically (the CI image's NDK is r27+). Verify on the first upload (Play Console warns if not); otherwise add `-C link-arg=-Wl,-z,max-page-size=16384` to `RUSTFLAGS`.

## 3. Store listing
Texts in [play/listing-en.md](play/listing-en.md), [ru](play/listing-ru.md), [uk](play/listing-uk.md). Graphics in [play/assets/](play/assets): `icon-512.png`, `feature-graphic-1024x500.png`.
- ☐ **Screenshots** (min 2, phone; recommended 4-8, plus 7"/10" tablet): take them from the debug APK on a device - Deck armed, Library, Clip editor, Settings. Capture each language you list.
- Category: *Tools* (or *Music & Audio*). Tags: audio recorder, voice recorder.

## 4. App content (Play Console → Policy → App content)
| Item | Answer |
|---|---|
| Privacy policy | URL from step 1 |
| Ads | No |
| App access | All functionality available without login |
| Content rating | IARC questionnaire: utility, no violence/sex/gambling/UGC sharing → expected *Everyone* |
| Target audience | 18+ (not directed at children) |
| News / COVID / Government | No |
| Data safety | **Collects no data; shares no data.** Data is processed only on device. Mark "Audio" as *not collected* (never leaves the device). No encryption-in-transit question applies (no network). Deletion: data deleted on uninstall. |
| Financial / Health features | None |

## 5. Permission & policy declarations
| Item | What to declare |
|---|---|
| `RECORD_AUDIO` | Core feature: continuous in-memory replay buffer. Prominent in-app disclosure: the notification and Arm button; consider adding a first-run explainer screenshot to the review notes. |
| **Foreground service: microphone** (`FOREGROUND_SERVICE_MICROPHONE`) | Declaration form (Policy → App content → *Foreground service permissions*). Use case: *"User-initiated, ongoing audio capture into a replay buffer while the user has armed it; a persistent notification with Save/Stop is shown."* Provide a short demo video (arm → notification → save → clip in library). |
| `POST_NOTIFICATIONS` | Required to show the foreground-service notification (Android 13+). |
| `VIBRATE`, `WAKE_LOCK` | Haptic confirmation; wake lock only while *Shake to save* is on. |
| `SYSTEM_ALERT_WINDOW` | Optional *Recording bubble* (user-enabled, off by default): small indicator that shows the app is recording and saves on tap. Sensitive permission - explain in the review notes; if review pushes back, remove the permission and the `indicator` setting. |
| Speech recognition | Transcription uses Android's on-device `SpeechRecognizer` (no extra permission beyond `RECORD_AUDIO`, no network). |
| Accessibility API | **Not used in the `play` flavor.** Do not ship the `full` flavor to Play: Google restricts the Accessibility API to accessibility tools. |
| Photo/video/all-files access | Not used (MediaStore insert into `Recordings/` needs no permission on API 29+). |

## 6. Release
1. Internal testing → add yourself, install from the opt-in link, smoke test (arm, save, notification, tile, trim, share, ru/uk language).
2. Closed testing (if required) → Production, staged rollout 10-20 %.
3. Each upload needs a higher `versionCode` (CI does this automatically).

## 7. Pre-launch checklist
- ☐ Real keystore signed AAB, versionCode > previous
- ☐ Screenshots for every listed language
- ☐ Data safety + content rating submitted
- ☐ FGS microphone declaration + video
- ☐ Tested on Android 10, 13, 14/15 (FGS start restrictions; notification permission)
- ☐ Pre-launch report reviewed (no crashes)
