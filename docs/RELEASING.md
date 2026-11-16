# Releasing

## Versioning
`versionName` = `MAJOR.MINOR.PATCH` (default in `app/build.gradle.kts`). `versionCode` must increase for every Play upload; CI uses the workflow run number.

## Upload key (once)
```bash
keytool -genkeypair -v -keystore precord-upload.jks -alias precord -keyalg RSA -keysize 4096 -validity 10000
```
Keep the file and passwords **out of git** and backed up. Then add repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `PRECORD_KEYSTORE_B64` | `base64 -w0 precord-upload.jks` |
| `PRECORD_KEYSTORE_PASSWORD` | store password |
| `PRECORD_KEY_ALIAS` | `precord` |
| `PRECORD_KEY_PASSWORD` | key password |

Without these secrets the release workflow still runs but signs with the debug key - fine for sideloading, **rejected by Google Play**.
Enrol in *Play App Signing* when creating the app; the key above is then only the upload key.

## Cutting a release
1. Update `CHANGELOG.md`.
2. Actions → **release** → *Run workflow* (or push a tag `vX.Y.Z`).
3. Download `precord-release` artifact: `precord-vX.Y.Z.apk` (GitHub) and `precord-play-vX.Y.Z.aab` (Play).
4. Upload the `.aab` in Play Console → Production (or Internal testing first).

## Local signed build
```bash
export PRECORD_KEYSTORE_PATH=$PWD/precord-upload.jks PRECORD_KEYSTORE_PASSWORD=… PRECORD_KEY_ALIAS=precord PRECORD_KEY_PASSWORD=…
./gradlew bundlePlayRelease -Pprecord.versionCode=2 -Pprecord.versionName=1.1.1
```
