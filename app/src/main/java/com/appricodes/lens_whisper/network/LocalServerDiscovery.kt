package com.appricodes.lens_whisper.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import com.appricodes.lens_whisper.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

private const val NOT_FOUND_MESSAGE =
    "Couldn't find your Local Server. Make sure the LensWhisper Desktop app is running and your phone is on the same Wi-Fi network."

/**
 * Finds a LensWhisper Desktop companion app (see desktop/README.md) on the local network by
 * probing `GET /health` on likely addresses, rather than using Android's `NsdManager`/mDNS —
 * NsdManager is well documented as unreliable in practice (missed/duplicate service events, empty
 * TXT records, needs a multicast lock, breaks under client-isolated Wi-Fi). A plain subnet HTTP
 * scan needs no extra permission beyond reading the phone's own IP and is simpler to reason about.
 */
object LocalServerDiscovery {

    private const val PORT = 8765
    private const val SCAN_TIMEOUT_MS = 4000L
    private const val PROBE_CONCURRENCY = 32

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(400, TimeUnit.MILLISECONDS)
        .build()

    /** Fast path: re-checks the cached address first (works for the common "already found it"
     * case in one round-trip); falls back to a fresh subnet scan if that fails or nothing was
     * cached yet. Updates [PreferencesManager.localServerBaseUrl] on a fresh find. Returns null if
     * no server could be reached either way. */
    suspend fun resolveBaseUrl(context: Context, prefs: PreferencesManager): String? {
        val cached = prefs.localServerBaseUrl
        if (cached.isNotBlank() && withContext(Dispatchers.IO) { probeHealth(cached) }) return cached

        val found = discover(context)
        if (found != null) prefs.localServerBaseUrl = found
        return found
    }

    /** Scans the phone's own /24 subnet for a responding LensWhisper Desktop server. Returns its
     * base URL (e.g. "http://192.168.1.42:8765"), or null if none answered within the time budget
     * or the phone isn't on a usable IPv4 network right now. */
    suspend fun discover(context: Context): String? = withContext(Dispatchers.IO) {
        val subnet = localSubnetPrefix(context) ?: return@withContext null
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            coroutineScope {
                val candidates = (1..254).map { "$subnet.$it" }
                for (chunk in candidates.chunked(PROBE_CONCURRENCY)) {
                    val hits = chunk.map { ip -> async { if (probeHealth(baseUrlFor(ip))) ip else null } }.awaitAll()
                    val found = hits.firstOrNull { it != null }
                    if (found != null) return@coroutineScope baseUrlFor(found)
                }
                null
            }
        }
    }

    /** Resolves the Local Server's address and runs [action] against a matching [LocalServerEngine],
     * or returns the app's standard "ERROR: ..." string if none could be found — same convention
     * every other non-streaming analysis path uses, so [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController]
     * doesn't need a special case for this one. */
    suspend fun callOrError(context: Context, prefs: PreferencesManager, action: suspend (LocalServerEngine) -> String): String {
        val baseUrl = resolveBaseUrl(context, prefs) ?: return "ERROR: $NOT_FOUND_MESSAGE"
        return action(LocalServerEngine(baseUrl))
    }

    /** Streaming counterpart — throws instead of returning an "ERROR:" string, matching every
     * other streaming path's convention (see [LocalServerEngine]'s streaming docs). */
    fun callOrErrorStreaming(context: Context, prefs: PreferencesManager, action: (LocalServerEngine) -> Flow<String>): Flow<String> = flow {
        val baseUrl = resolveBaseUrl(context, prefs) ?: throw IllegalStateException(NOT_FOUND_MESSAGE)
        emitAll(action(LocalServerEngine(baseUrl)))
    }

    private fun baseUrlFor(ip: String) = "http://$ip:$PORT"

    private fun probeHealth(baseUrl: String): Boolean = try {
        val request = Request.Builder().url("$baseUrl/health").get().build()
        probeClient.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    /** The phone's own "a.b.c" subnet prefix (first three octets of its LAN IPv4 address), or null
     * if it isn't on a usable IPv4 network right now. */
    private fun localSubnetPrefix(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val linkProperties = cm.getLinkProperties(network) ?: return null
        val ipv4: LinkAddress = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val address = (ipv4.address as Inet4Address).hostAddress ?: return null
        val parts = address.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }
}
