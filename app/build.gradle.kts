plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.precord.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.precord.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // swap for a real keystore before publishing
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ---------------------------------------------------------------------------
// Rust -> Android: `cargo ndk` builds libprecord_core.so straight into jniLibs
// so a plain `./gradlew assembleDebug` produces a complete APK.
// Requirements: rustup targets, `cargo install cargo-ndk`, and ANDROID_NDK_HOME
// (or an NDK under $ANDROID_HOME/ndk).
// ---------------------------------------------------------------------------
val rustAbis = (findProperty("precord.abis") as String).split(",").map { it.trim() }.filter { it.isNotEmpty() }
val rustDir = rootProject.layout.projectDirectory.dir("rust-core")
val jniOut = layout.projectDirectory.dir("src/main/jniLibs")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "rust"
    description = "Compile rust-core into libprecord_core.so for ${rustAbis.joinToString()}"
    workingDir = rustDir.asFile
    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    inputs.property("abis", rustAbis)
    outputs.dir(jniOut)
    val cmd = mutableListOf("cargo", "ndk")
    rustAbis.forEach { cmd.addAll(listOf("-t", it)) }
    cmd.addAll(listOf("-o", jniOut.asFile.absolutePath, "build", "--release"))
    commandLine(cmd)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(cargoNdkBuild) }
