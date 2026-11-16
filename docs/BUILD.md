# Building

Prerequisites: JDK 17, Android SDK (API 35) with an **NDK**, Rust via rustup.

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<installed-version>
```

| Goal | Command | Output |
|---|---|---|
| Rust tests | `cargo test --manifest-path rust-core/Cargo.toml` | - |
| Debug APK (sideload) | `./gradlew assembleFullDebug` | `app/build/outputs/apk/full/debug/` |
| Release APK (sideload) | `./gradlew assembleFullRelease` | `app/build/outputs/apk/full/release/` |
| Play bundle | `./gradlew bundlePlayRelease` | `app/build/outputs/bundle/playRelease/*.aab` |
| Emulator (x86_64) | add `-Pprecord.abis=arm64-v8a,armeabi-v7a,x86_64` (and `rustup target add x86_64-linux-android`) | |

Gradle runs `cargo ndk` automatically (`cargoNdkBuild`, hooked to `preBuild`) and places `libprecord_core.so` in `app/src/main/jniLibs/`.

Version: `-Pprecord.versionCode=N -Pprecord.versionName=X.Y.Z`.

## CI
* `.github/workflows/android.yml` – every push to `main`, PRs, manual: tests + debug APKs; artifact `precord-debug-apk`.
* `.github/workflows/release.yml` – manual or `v*` tag: signed APK + AAB, GitHub Release. See [RELEASING.md](RELEASING.md).
