import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The passphrase the Export/Import feature derives its AES key from (see
// data/BackupCrypto.kt). Kept out of version control on purpose: `secrets.properties` is
// gitignored and only `secrets.properties.example` is committed, so a fresh clone still builds —
// it just falls back to the placeholder below and produces .fdb files this build can read but a
// properly-keyed build cannot. Ship builds must supply the real file.
val placeholderPassphrase = "lenswhisper-placeholder-passphrase"
val secretsFile = rootProject.file("secrets.properties")
val backupPassphrase: String = if (secretsFile.exists()) {
    Properties().apply { secretsFile.inputStream().use { load(it) } }
        .getProperty("BACKUP_PASSPHRASE")
        ?.takeIf { it.isNotBlank() }
        ?: placeholderPassphrase
} else {
    placeholderPassphrase
}

// Release signing (the Play upload key). `keystore.properties` is gitignored — it holds storeFile,
// storePassword, keyAlias and keyPassword. Without it (e.g. a fresh clone of the public repo) a
// release build still runs, it just comes out unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
    Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
} else {
    null
}

android {
    namespace = "com.appricodes.lens_whisper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appricodes.lens_whisper"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BACKUP_PASSPHRASE", "\"$backupPassphrase\"")
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Modern Kotlin DSL for setting the JVM target
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // CameraX dependencies for live camera preview
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.media)

    // (EncryptedSharedPreferences used to live here. Its only two consumers were the signed-in
    // user's email and Google id, and there is no account system any more — nothing the app stores
    // is a secret. The Local Server password is never stored at all; only a token derived from it
    // is, and that token is useless without the server. See data/PreferencesManager.kt.)

    // Palette API for dominant color extraction
    implementation(libs.androidx.palette)

    // Video Player
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Networking
    implementation(libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Added for Compose Lifecycle support mapping
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Barcode scanning (QR codes and 1D product barcodes) - zxing-cpp (Apache-2.0), a bundled
    // native decoder: no model, no Play Services, ready at install. Backs
    // offline/OfflineBarcodeScanner.kt and BarcodeScanActivity.
    implementation(libs.zxing.cpp)

    // Text recognition (OCR) needs no dependency of its own: PaddleOCR PP-OCRv5 det/rec models
    // (Apache-2.0) run on the plain LiteRT runtime below, from assets/models/ocr/ — see
    // offline/PaddleOcr.kt. (ML Kit's play-services-mlkit-text-recognition* used to live here; it
    // and ML Kit barcode/translate were removed so the app ships no proprietary Google code, which
    // the AGPL-3.0 licence of this project can't be combined with.)

    // (ML Kit Image Labeling and play-services-mlkit-subject-segmentation used to live here,
    // powering OfflineColorDetector's own `detect`. That path was dropped once the
    // segmentation-based Detect Objects/Object and Color Detection buttons took over its slot —
    // see MlkitButtonProvider's doc — leaving these two as the single biggest dead weight in the
    // APK: image-labeling alone pulls in vision-internal-vkp, whose native lib
    // (libmlkitcommonpipeline.so) was shipping four times over, once per ABI, unused. Removed
    // 2026-08-27.)

    // (play-services-auth — Google Sign-In — used to live here for the old account system. There
    // is no account system any more (see data/PreferencesManager.kt's note above) and nothing in
    // this codebase references GoogleSignIn*. Removed 2026-08-27.)

    // LiteRT-LM on-device model inference (Manage Advanced Models)
    implementation(libs.litertlm.android)

    // Plain LiteRT (the classic Interpreter API — NOT litertlm-android above, which is a
    // separate, LLM-specific runtime that can't load an ordinary CNN). Runs the bundled
    // YOLOv8n-seg object-and-mask detector — see offline/OfflineSegmentationDetector.kt.
    // CPU-only deliberately: no GPU delegate is added. This exact codebase has already hit one
    // GPU-vision-delegate compatibility failure on real hardware (Exynos 2100/Mali, see
    // PreferencesManager.getVisionBackendOverride's doc) that needed a runtime CPU fallback for
    // the VLM catalog; YOLOv8n is small enough (3.2M params) that CPU-only should be fast enough
    // for a single-shot capture without repeating that failure mode for a second model family.
    implementation(libs.litert) {
        // LiteRT's optional AI-pack model delivery (com.google.ai.edge.litert.deployment, unused
        // here: every model is a bundled asset or a direct download) pulls in proprietary Play
        // libraries — play-services-basement/-tasks and Play Core — which an AGPL-3.0 app can't ship.
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }

    // Forces kotlinx-coroutines-core/-android to 1.11.0+ instead of whatever transitive
    // resolution would otherwise pick (1.9.0, as of writing — see litertlm-android's own POM,
    // which is what actually drives that). This isn't a version *preference*, it's a hard
    // requirement: litertlm-android's compiled Kotlin (Conversation.kt, the
    // sendMessageAsync(): Flow<Message> streaming path specifically) calls
    // `SendChannel.close$default(...)` as a *static interface method declared directly on
    // SendChannel* — the newer `-Xjvm-default=all`-style ABI. Versions through 1.9.0 (and 1.10.x,
    // unconfirmed) instead put that dispatcher on a separate `SendChannel$DefaultImpls` class, so
    // the call fails at runtime with NoSuchMethodError — not caught anywhere, so it crashes the
    // whole app — the instant a streamed on-device response finishes (confirmed via crash log,
    // 2026-08-24: `Conversation$sendMessageAsync$1$1.onDone`). Verified 1.11.0 puts it on the
    // interface directly (decompiled both sides to confirm before landing this). If a future
    // litertlm-android release fixes this on their end, this pin can likely be relaxed back to
    // whatever they declare — but don't lower it without re-checking both sides again.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // (Google Mobile Ads + the UMP Consent Management Platform used to live here, behind an
    // optional rewarded ad on "Support Development" — see ads/RewardedAdManager.kt and
    // ads/AdConsentManager.kt. AdMob was removed from the app entirely; there is no advertising
    // anywhere in the app any more.)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Shipping a release built on the placeholder key would be a one-way mistake: every .fdb a user
// exported from that build becomes unreadable the moment the real secrets.properties arrives,
// because the key it was encrypted under no longer exists anywhere. Checked against the task graph
// rather than inside `buildTypes.release { }` — that block is configured on every build, so a
// throw there would fail debug builds as well.
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { it.name.contains("Release") }
    if (buildingRelease && backupPassphrase == placeholderPassphrase) {
        throw GradleException(
            "secrets.properties is missing or still holds the placeholder BACKUP_PASSPHRASE. " +
                "Copy secrets.properties.example to secrets.properties and set a real value " +
                "before making a release build."
        )
    }
}
