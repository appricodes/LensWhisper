package com.appricodes.lens_whisper.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.network.AdRevenueClient
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lets a user earn free credits by watching a rewarded (video) ad — see the "Watch an Ad" section
 * of `ui/components/CreditsDialog.kt`, which is the only caller. No purchase alternative right now
 * — see LOGIN_AND_PURCHASES_REMOVED.md at the repo root for why.
 *
 * **Setup note (same pattern as [com.appricodes.lens_whisper.REVENUECAT_PUBLIC_SDK_KEY]):**
 * [AD_UNIT_ID] and the `APPLICATION_ID` meta-data in `AndroidManifest.xml` are the real IDs from
 * the developer's own AdMob console (set 2026-08-24, replacing Google's placeholder test IDs).
 * Still required as a one-time console step, not doable from this file: configure this ad unit's
 * Server-Side Verification callback URL to `https://lw.wikiponder.com/admob_ssv_callback.php`
 * (AdMob console > this ad unit > Server-side verification) — until that's set, Google's ad
 * servers never call the endpoint at all, so no credit is ever granted no matter what the PHP
 * side does.
 *
 * **Crediting happens server-side, not here.** [showAd]'s `onEarned` callback is only the
 * client-side "the SDK saw a reward" signal — trusting that alone to grant credits is trivially
 * spoofable on a modified client. The actual grant happens when Google's ad servers call
 * `backend/admob_ssv_callback.php` directly, keyed by the `installId` [showAd] attaches to
 * the ad via [ServerSideVerificationOptions] before showing it. `onEarned` here just triggers a
 * "thanks, your balance will update shortly" message and a delayed re-fetch — see `CreditsDialog.kt`.
 *
 * **Revenue logging is separate from all of the above.** [showAd] also attaches an
 * [OnPaidEventListener], the only per-impression revenue figure Google exposes anywhere (the SSV
 * callback carries no monetary value) — see [AdRevenueClient]'s doc for why that makes it
 * best-effort/analytics-only, never something crediting depends on.
 */
object RewardedAdManager {
    private const val TAG = "RewardedAdManager"

    private const val AD_UNIT_ID = "ca-app-pub-9386480260190622/6395336158"

    const val CREDITS_PER_AD = 2

    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    // Fire-and-forget scope for AdRevenueClient posts — deliberately outlives any single showAd()
    // call (a slow/late-arriving OnPaidEventListener callback shouldn't get cancelled just because
    // the dialog that triggered it was dismissed in the meantime).
    private val revenueLogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts loading an ad into memory ahead of time so it's ready the moment the user taps
     *  "Watch an Ad" — call from `LensWhisperApplication.onCreate` and again whenever the credits
     *  dialog opens (a no-op if one's already loaded or loading). */
    fun preload(context: Context) {
        if (rewardedAd != null || isLoading) return
        isLoading = true
        RewardedAd.load(context.applicationContext, AD_UNIT_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                isLoading = false
                rewardedAd = ad
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                isLoading = false
                rewardedAd = null
                Log.w(TAG, "Rewarded ad failed to load: ${error.message}")
            }
        })
    }

    /**
     * Shows the preloaded ad if one is ready. Exactly one of [onEarned]/[onUnavailable] fires;
     * [onDismissedWithoutReward] additionally fires if the user closes the ad before it finishes
     * (no reward in that case, even though it played).
     *
     * [installId] must be [com.appricodes.lens_whisper.data.PreferencesManager.installId] — it's
     * what `backend/admob_ssv_callback.php` matches against `install_credits.install_id` to know
     * whose balance to credit once Google confirms the reward.
     */
    fun showAd(
        activity: Activity,
        installId: String,
        onEarned: () -> Unit,
        onUnavailable: () -> Unit,
        onDismissedWithoutReward: () -> Unit = {}
    ) {
        val ad = rewardedAd
        if (ad == null) {
            preload(activity)
            onUnavailable()
            return
        }

        ad.setServerSideVerificationOptions(
            ServerSideVerificationOptions.Builder().setCustomData(installId).build()
        )

        // The only per-impression revenue signal that exists anywhere — see AdRevenueClient's
        // doc. Not tied to crediting in any way, purely a best-effort analytics log.
        ad.onPaidEventListener = OnPaidEventListener { adValue ->
            revenueLogScope.launch { AdRevenueClient.logRevenue(installId, AD_UNIT_ID, adValue) }
        }

        var earnedReward = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                preload(activity)
                if (!earnedReward) onDismissedWithoutReward()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                rewardedAd = null
                preload(activity)
                Log.w(TAG, "Rewarded ad failed to show: ${adError.message}")
                onUnavailable()
            }
        }

        ad.show(activity) { _ ->
            earnedReward = true
            onEarned()
        }
    }
}
