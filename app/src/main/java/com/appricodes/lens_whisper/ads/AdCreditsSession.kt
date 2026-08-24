package com.appricodes.lens_whisper.ads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.network.AdCreditsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Opportunistic, at-most-once-per-process-run sync of the ad-credit balance — there's no server
 * round trip needed for *identity* any more ([PreferencesManager.installId] is generated once on
 * device and lives there permanently; [com.appricodes.lens_whisper.ads.RewardedAdManager] and
 * `process.php` both use it directly), only for the *balance* itself.
 *
 * Called once from [com.appricodes.lens_whisper.LensWhisperApplication.onCreate]. If there's no
 * network yet at startup, a connectivity listener retries until the first successful fetch, then
 * stops trying for the rest of this process's life — [com.appricodes.lens_whisper.ui.components.CreditsDialog]'s
 * "Update Credits" button and the post-ad-watch re-check are separate, always-hit-the-network
 * refreshes that don't go through this "only once" gate.
 */
object AdCreditsSession {
    @Volatile private var syncedThisRun = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startWarmup(context: Context, prefs: PreferencesManager) {
        scope.launch { syncOnce(prefs) }

        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (syncedThisRun) return
                scope.launch { syncOnce(prefs) }
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            // No-op: worst case, the balance just shows whatever was last synced (or 0 for a
            // fresh install) until the user opens the credits dialog and taps "Update Credits".
        }
    }

    private suspend fun syncOnce(prefs: PreferencesManager) {
        if (syncedThisRun) return
        if (AdCreditsClient.fetchCredits(prefs) != null) syncedThisRun = true
    }
}
