plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oblivion.djayclone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oblivion.djayclone"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "0.12.2" // Stages 1-10c complete: playback, mixing, BPM sync,
        // cue/loop/hot-cues, live-mix recording + export, filter/echo FX,
        // adjustable settings, track library, adaptive tablet/foldable
        // layout including the TableTop posture-aware arrangement (the one
        // piece of 10c the 0.11.0 comment claimed done before it actually
        // was) - plus the parallel Audio Engineering track: 3-band EQ,
        // spectral waveform, VU meter, Tempo-Sync v1 (ratio-aware
        // half/double-time), Key Detection (Camelot notation), Key Lock.
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so `assembleRelease` produces an
            // APK you can actually install directly (adb install / sideload)
            // for daily personal use, rather than an unsigned artifact that
            // needs a real release keystore before it'll install at all.
            // Fine for a personal project that isn't going to the Play
            // Store; revisit if that ever changes.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Media3 (ExoPlayer) - broad codec support (MP3, AAC, FLAC, WAV, OGG, OPUS)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // Stage 10: adaptive layout (tablet/foldable). One first-party artifact
    // covers both signals - WindowSizeClass (how much room) and
    // WindowInfoTracker/FoldingFeature (what physical posture) - see
    // DjAdaptive.kt for why these are read as two separate signals, not one.
    implementation("androidx.window:window:1.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // KeyDetector.kt (and TempoSyncMath.kt/Fft.kt before it) are plain
    // Kotlin with zero Android imports specifically so they're testable
    // here, in a real JVM unit test, without an emulator/device.
    testImplementation("junit:junit:4.13.2")
}
