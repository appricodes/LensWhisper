package com.appricodes.lens_whisper.network

import com.appricodes.lens_whisper.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Reads the logged-in user's current free/purchased credits from the backend. Used to refresh
 * the header's credit count after a RevenueCat purchase — the actual grant happens server-side
 * via backend/revenuecat_webhook.php, asynchronously, so this is a poll rather than something
 * the client can compute itself.
 */
object CreditsSyncClient {
    private val client = OkHttpClient()

    suspend fun syncCredits(prefs: PreferencesManager): Boolean = withContext(Dispatchers.IO) {
        val email = prefs.userEmail
        val googleId = prefs.googleId
        if (email.isNullOrBlank() || googleId.isNullOrBlank()) return@withContext false

        try {
            val payload = JSONObject().apply {
                put("email", email)
                put("google_id", googleId)
            }
            val request = Request.Builder()
                .url("https://www.wikiponder.com/lense_whisper/sync_credits.php")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext false
                if (!response.isSuccessful) return@withContext false
                val json = JSONObject(body)
                if (!json.has("free_credits") || !json.has("purchased_credits")) return@withContext false
                prefs.freeCredits = json.getInt("free_credits")
                prefs.purchasedCredits = json.getInt("purchased_credits")
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
