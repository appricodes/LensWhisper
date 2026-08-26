package com.appricodes.lens_whisper

import android.app.Application
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine

/**
 * Startup does almost nothing on purpose.
 *
 * What used to happen here and no longer does:
 * - `MobileAds.initialize` + a rewarded-ad preload. Initialising the Ads SDK reads and writes
 *   information on the user's device, so it must not happen before consent — it now runs only
 *   when the user opens Support Development, from [com.appricodes.lens_whisper.ads.AdConsentManager].
 * - An ad-credit balance sync against a backend. There are no credits and no backend; every model
 *   runs on-device, so nothing meters anything.
 * - RevenueCat configuration and a sign-in replay. Purchases and accounts are gone.
 *
 * The result is that a launch makes no network request at all and touches no identifier.
 */
class LensWhisperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OfflineInferenceEngine.registerMemoryPressureMonitor(this)
    }
}
