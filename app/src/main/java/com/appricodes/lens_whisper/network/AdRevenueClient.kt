package com.appricodes.lens_whisper.network

import com.google.android.gms.ads.AdValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reports one AdMob impression's revenue (from `OnPaidEventListener`, see
 * [com.appricodes.lens_whisper.ads.RewardedAdManager]) to `backend/log_ad_revenue.php`. This is
 * the only revenue-per-ad-watch data that exists anywhere — Google's server-to-server SSV callback
 * (the trustworthy one that actually grants credits) carries no monetary value at all, only the
 * client-side SDK does — so this is inherently client-reported/best-effort, not a verified figure.
 * Fire-and-forget: failures are silently dropped, this is analytics, not something anything else
 * depends on.
 */
object AdRevenueClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun logRevenue(installId: String, adUnitId: String, adValue: AdValue) = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("install_id", installId)
                put("ad_unit_id", adUnitId)
                put("value_micros", adValue.valueMicros)
                put("currency_code", adValue.currencyCode)
                put("precision_type", precisionTypeLabel(adValue.precisionType))
            }
            val request = Request.Builder()
                .url("https://lw.wikiponder.com/log_ad_revenue.php")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            // Best-effort analytics — dropping a revenue log entry is never worth surfacing to the
            // user or retrying at the cost of extra complexity.
        }
    }

    private fun precisionTypeLabel(precisionType: Int): String = when (precisionType) {
        AdValue.PrecisionType.UNKNOWN -> "UNKNOWN"
        AdValue.PrecisionType.ESTIMATED -> "ESTIMATED"
        AdValue.PrecisionType.PUBLISHER_PROVIDED -> "PUBLISHER_PROVIDED"
        AdValue.PrecisionType.PRECISE -> "PRECISE"
        else -> "UNKNOWN_$precisionType"
    }
}
