package com.appricodes.lens_whisper.network

import com.appricodes.lens_whisper.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Credit-balance lookup — see backend/get_credits.php's docblock for the full design (it serves
 * both the anonymous, install_id-keyed path this always sends, and a dormant email+google_id
 * account path kept for when login is restored, see LOGIN_AND_PURCHASES_REMOVED.md).
 *
 * [PreferencesManager.installId] is never anything hardware/Play-Services-derived, and never sent
 * on to AdMob — see [com.appricodes.lens_whisper.ads.RewardedAdManager], which uses installId
 * directly as AdMob's customData. On the server this also lazily creates the `install_credits` row
 * for a never-seen-before install_id, so this doubles as "register this install" the first time
 * it's called.
 *
 * On success, updates [PreferencesManager.freeCredits]/[PreferencesManager.purchasedCredits]
 * directly (single place that writes them, so every caller — startup warmup, the credits dialog's
 * manual refresh, the post-ad-watch re-check — stays in sync without duplicating that step).
 */
object AdCreditsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Returns the new total (free + purchased) on success, null on any failure (no network,
     *  server error, malformed response) — prefs are left untouched on failure. */
    suspend fun fetchCredits(prefs: PreferencesManager): Int? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("email", prefs.userEmail ?: "")
                put("google_id", prefs.googleId ?: "")
                put("install_id", prefs.installId)
            }
            val request = Request.Builder()
                .url("https://lw.wikiponder.com/get_credits.php")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext null
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(body)
                if (json.optBoolean("error", true) || !json.has("free_credits") || !json.has("purchased_credits")) return@withContext null
                val free = json.getInt("free_credits")
                val purchased = json.getInt("purchased_credits")
                prefs.freeCredits = free
                prefs.purchasedCredits = purchased
                free + purchased
            }
        } catch (e: Exception) {
            null
        }
    }
}
