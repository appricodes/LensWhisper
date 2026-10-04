package com.appricodes.lens_whisper

import android.app.Application
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.service.ModelDownloader
import com.appricodes.lens_whisper.service.ProductDbDownloader
import com.appricodes.lens_whisper.utils.CaptureCacheCleaner

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
        // Before anything can preload the selected model: if the app was killed while loading it
        // last time, don't walk straight into the same wall — switch to Basic tasks and say why.
        OfflineInferenceEngine.recoverFromInterruptedLoad(this)
        OfflineModelsRepository(this).retireRemovedModels(PreferencesManager(this))
        CaptureCacheCleaner.sweepInBackground(this)
        // A download the system kept running while the app was closed: follow it again, or put
        // it in place if it finished meanwhile. No-ops when nothing is pending.
        ModelDownloader.resume(this)
        ProductDbDownloader.resume(this)
    }
}
