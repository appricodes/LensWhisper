package com.appricodes.lens_whisper

import android.app.Application
import com.appricodes.lens_whisper.data.PreferencesManager
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration

/**
 * Configures RevenueCat on process start. RevenueCat wraps Google Play Billing so credit
 * packages can be purchased without the app talking to Play Billing directly — see
 * REVENUECAT_SETUP.md for the one-time dashboard/Play Console configuration this depends on.
 */
class LensWhisperApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(PurchasesConfiguration.Builder(this, REVENUECAT_PUBLIC_SDK_KEY).build())

        // Ties RevenueCat's app_user_id to our own backend's identity (email), so the purchase
        // webhook can credit the right account. LoginActivity re-calls this after a fresh login;
        // this covers the case where the app was already logged in from a previous session.
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
