plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.appricodes.lens_whisper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appricodes.lens_whisper"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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

    // for using crypto-secured preferences
    implementation(libs.androidx.security.crypto)

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

    // Latin Script (English, Spanish, French, German, Italian, etc.)
    implementation(libs.text.recognition)

    // Chinese Script
    implementation(libs.text.recognition.chinese)

    // Japanese Script
    implementation(libs.text.recognition.japanese)

    // Korean Script
    implementation(libs.text.recognition.korean)

    // Devanagari Script (Hindi, Marathi, Nepali, Sanskrit, etc.)
    implementation(libs.text.recognition.devanagari)

    // ML Kit Image Labeling (Bundled base model - increases APK size by ~5MB but works instantly offline)
    implementation(libs.image.labeling)

    // subject segmentation
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

    // MLKit translator
    implementation(libs.translate)

    // google signing
    implementation(libs.play.services.auth)

    // LiteRT-LM on-device model inference (Manage Advanced Models)
    implementation(libs.litertlm.android)

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

    // RevenueCat: manages Google Play Billing purchases for credit packages
    implementation("com.revenuecat.purchases:purchases:9.23.1")

    // Google Mobile Ads: rewarded ads let users earn free credits without paying — see
    // ads/RewardedAdManager.kt and REVENUECAT_SETUP.md-style setup notes there.
    implementation("com.google.android.gms:play-services-ads:23.6.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}