package com.appricodes.lens_whisper.ads

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * Plays one optional video advertisement when the user chooses "Support Development".
 *
 * **The ad grants nothing.** There is no reward, no credit, no unlock and no server round trip —
 * every model in the app runs on-device, so nothing costs anything per use and there is nothing
 * to meter. Watching is purely a way to support the developer, and the app is fully functional
 * for a user who never does. That is also what makes the consent [AdConsentManager] collects
 * genuinely freely given: declining costs the user no functionality at all.
 *
 * Consequences worth keeping in mind before "improving" any of this:
 * - No `ServerSideVerificationOptions` and no custom data. There is nothing for a reward callback
 *   to verify, and the install identifier that used to be attached here no longer exists.
 * - No `OnPaidEventListener` revenue logging. That reported to a backend this app no longer has.
 * - **Non-personalised only.** [nonPersonalizedRequest] pins `npa=1` on every request. This app's
 *   install base is overwhelmingly blind and low-vision users, so the package name alone
 *   correlates with disability — feeding that into interest-based targeting would push data that
 *   is health-adjacent into an ad profile. Do not remove this to raise eCPM.
 * - **Rewarded format only — never a banner or an interstitial.** A banner is invisible to a
 *   screen-reader user but still lands in the TalkBack swipe order, so it gets activated by
 *   accident constantly; that is both a miserable experience and an invalid-traffic risk to the
 *   AdMob account.
 *
 * [AD_UNIT_ID] and the `APPLICATION_ID` meta-data in `AndroidManifest.xml` are the developer's own
 * AdMob IDs.
 */
object RewardedAdManager {
    private const val TAG = "RewardedAdManager"

    private const val AD_UNIT_ID = "ca-app-pub-9386480260190622/6395336158"

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    /** Every request this app makes, personalisation explicitly off. */
    private fun nonPersonalizedRequest(): AdRequest {
        val extras = Bundle().apply { putString("npa", "1") }
        return AdRequest.Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
            .build()
    }

    /**
     * Loads an ad into memory. Only ever called after [AdConsentManager] has confirmed ads may be
     * requested — never from `Application.onCreate`, which is what it used to be called from.
     */
    fun load(context: Context, onLoaded: () -> Unit = {}, onFailed: () -> Unit = {}) {
        if (rewardedAd != null) { onLoaded(); return }
        if (isLoading) return
        isLoading = true
        RewardedAd.load(context.applicationContext, AD_UNIT_ID, nonPersonalizedRequest(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                isLoading = false
                rewardedAd = ad
                onLoaded()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                isLoading = false
                rewardedAd = null
                Log.w(TAG, "Rewarded ad failed to load: ${error.message}")
                onFailed()
            }
        })
    }

    /**
     * Shows a loaded ad. [onFinished] fires once the ad is gone, however it went — watched fully,
     * closed early, or failed to show — because nothing depends on the difference any more.
     * [onUnavailable] fires when there was no ad to show in the first place.
     */
    fun showAd(activity: Activity, onFinished: () -> Unit, onUnavailable: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            onUnavailable()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                onFinished()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                rewardedAd = null
                Log.w(TAG, "Rewarded ad failed to show: ${adError.message}")
                onUnavailable()
            }
        }

        ad.show(activity) { /* No reward is granted — see this object's doc. */ }
    }
}
