package com.appricodes.lens_whisper

import android.app.Application
import com.appricodes.lens_whisper.ads.AdCreditsSession
import com.appricodes.lens_whisper.ads.RewardedAdManager
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration

/**
 * Login is OPTIONAL — the app's default credit source is still anonymous, install_id-keyed, ad-
 * watch credits (see [AdCreditsSession] / backend/get_credits.php); nothing here gates any screen
 * behind being signed in. Signing in (via [LoginActivity], reachable from
 * [com.appricodes.lens_whisper.ui.components.CreditsDialog]) is only needed to buy a credit pack
 * or to claim the one-time 100-credit trial bonus new accounts get — see
 * LOGIN_AND_PURCHASES_REMOVED.md for the fuller history of this file.
 */
class LensWhisperApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        OfflineInferenceEngine.registerMemoryPressureMonitor(this)

        // TEST_DEVICE_IDS: registers specific physical devices so the real AdMob ad unit above
        // still serves clearly-labeled TEST ads on them (no real revenue), instead of "No Fill" —
        // which is what a brand-new, not-yet-approved AdMob account/app returns for real requests.
        // This is Google's documented way to validate the full pipeline (real ad unit → real SSV
        // callback URL configured in the console → backend/admob_ssv_callback.php crediting) while
        // waiting on account approval. Each entry is the hashed device ID AdMob logs under the
        // "Ads" tag ("Use RequestConfiguration.Builder().setTestDeviceIds(...)") the first time an
        // ad request comes from that device — add more entries here for other test devices as
        // needed. REMOVE before shipping — a device left in this list never counts as real
        // impressions/revenue even after the account is fully approved.
        val testDeviceIds = listOf("48E793B33C52E59C4316998DDC7F2E70") // Samsung Galaxy S21
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder().setTestDeviceIds(testDeviceIds).build()
        )

        MobileAds.initialize(this)
        RewardedAdManager.preload(this)
        AdCreditsSession.startWarmup(this, PreferencesManager(this))

        // RevenueCat purchasing — only reachable via CreditsDialog's purchase section, which
        // itself only shows once signed in. Configuring the SDK here regardless (rather than
        // lazily) is what RevenueCat's own docs recommend — it's cheap and offline-safe.
        if (BuildConfig.DEBUG) Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(PurchasesConfiguration.Builder(this, REVENUECAT_PUBLIC_SDK_KEY).build())
        // Re-establish RevenueCat's app_user_id on process restart for an already-logged-in user
        // (a fresh login calls this itself — see LoginActivity.verifyLogin).
        val email = PreferencesManager(this).userEmail
        if (!email.isNullOrBlank()) Purchases.sharedInstance.logIn(email)
    }
}

/**
 * RevenueCat's PUBLIC SDK key (safe to ship in the app — distinct from the SECRET API key used
 * server-side for webhook/REST calls). Get it from the RevenueCat dashboard under
 * Project Settings > API keys > App specific keys > (your Android app) > Public app-specific key.
 */
const val REVENUECAT_PUBLIC_SDK_KEY = "REPLACE_ME_REVENUECAT_PUBLIC_SDK_KEY"
