package com.appricodes.lens_whisper.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Gathers advertising consent before a single line of ad code runs, and only then starts the Ads
 * SDK.
 *
 * **Nothing here happens at app startup, deliberately.** `MobileAds.initialize` itself touches
 * device identifiers, so calling it eagerly (which is what this app used to do, from
 * `LensWhisperApplication.onCreate`) reads and writes information on the user's device before any
 * consent exists — §25(1) TDDDG. Everything below is therefore driven from exactly one place: the
 * user tapping Support Development. A user who never taps it never has the Ads SDK started, never
 * has an ad requested, and never sees a consent dialog.
 *
 * A Google-certified CMP is also Google's own requirement for serving ads to EEA/UK users, not
 * only a legal one — [UserMessagingPlatform] is Google's certified implementation of it.
 *
 * Consent is asked for even though this app only ever requests **non-personalised** ads (see
 * [RewardedAdManager]): non-personalised still uses a device identifier for frequency capping,
 * reporting and fraud prevention, which is not "strictly necessary" for a service the user asked
 * for, so it needs consent all the same.
 */
object AdConsentManager {
    private const val TAG = "AdConsentManager"

    @Volatile private var mobileAdsStarted = false

    /**
     * Fill this with the hashed device ID that AdMob logs under the "Ads" tag the first time an ad
     * request comes from a device, to force the EEA consent flow to appear there for testing.
     * Must stay empty in a release build — a device listed here is treated as being in the EEA
     * regardless of where it actually is.
     */
    private val debugGeographyTestDeviceIds = emptyList<String>()

    /**
     * Runs the full gather-consent-then-start-the-SDK sequence and calls [onReady] only if the
     * result permits requesting an ad. [onBlocked] is called with a user-facing sentence otherwise
     * — the user declined, or the consent form itself failed — and the caller is expected to
     * announce it and carry on; nothing in the app depends on an ad ever being shown.
     */
    fun ensureConsentThenReady(activity: Activity, onReady: () -> Unit, onBlocked: (String) -> Unit) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .apply {
                if (debugGeographyTestDeviceIds.isNotEmpty()) {
                    val debugSettings = ConsentDebugSettings.Builder(activity)
                        .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                        .apply { debugGeographyTestDeviceIds.forEach { addTestDeviceHashedId(it) } }
                        .build()
                    setConsentDebugSettings(debugSettings)
                }
            }
            .build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form error: ${formError.message}")
                    }
                    if (consentInformation.canRequestAds()) {
                        startMobileAdsOnce(activity)
                        onReady()
                    } else {
                        onBlocked("Advertising needs your consent. Nothing else in the app is affected.")
                    }
                }
            },
            { requestError ->
                Log.w(TAG, "Consent info update failed: ${requestError.message}")
                onBlocked("Couldn't reach the consent service. Please try again later.")
            }
        )
    }

    /**
     * Re-opens the consent form so the user can change or withdraw what they gave, from Legal
     * Information. Withdrawal has to stay available at any time, and it has to be reachable
     * without first agreeing to anything.
     */
    fun showPrivacyOptions(context: Context, announce: (String) -> Unit) {
        val activity = context as? Activity ?: return
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        if (consentInformation.privacyOptionsRequirementStatus !=
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        ) {
            announce("There is no advertising consent stored for this device yet.")
            return
        }
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) announce("Couldn't open the consent options.")
        }
    }

    /** `MobileAds.initialize` is idempotent, but it is also the exact call that must never run
     *  before consent — keeping the one call site here makes that easy to verify by reading. */
    private fun startMobileAdsOnce(context: Context) {
        if (mobileAdsStarted) return
        mobileAdsStarted = true
        MobileAds.initialize(context.applicationContext)
    }
}
