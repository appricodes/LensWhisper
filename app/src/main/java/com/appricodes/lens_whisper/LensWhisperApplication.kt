package com.appricodes.lens_whisper

import android.app.Application
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine

/**
 * Startup does almost nothing on purpose.
 *
 * What used to happen here and no longer does:
 * - `MobileAds.initialize` + a rewarded-ad preload. AdMob has been removed from the app entirely
 *   (Support Development / the `ads` package / the Play Services Ads and UMP dependencies are gone)
 *   — there is no advertising anywhere in the app any more.
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
