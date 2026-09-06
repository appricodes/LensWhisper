package com.appricodes.lens_whisper.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What a barcode lookup came back with. Exactly one of these is ever built per lookup, and the UI
 * never has to reason about HTTP: a 404, a rate-limit, a timeout, a parse failure and a genuine
 * "this barcode isn't in the database" all arrive as [NotFound], because the user-facing answer to
 * every one of them is the same sentence ("Unknown, or no information available"). [Found] carries
 * only the descriptive fields; **price, offers and merchant listings are deliberately dropped at
 * the parse step**, per the feature's spec, so nothing downstream can accidentally show them.
 */
sealed interface UpcLookupResult {
    data class Found(val fields: List<Pair<String, String>>) : UpcLookupResult
    data object NotFound : UpcLookupResult
}

/**
 * Looks a 1D product barcode up against UPCitemdb's free trial endpoint
 * (https://api.upcitemdb.com/prod/trial/lookup?upc=...).
 *
 * **This is the only thing in the entire app that sends a barcode off the device**, and it must
 * only ever be reached after [com.appricodes.lens_whisper.data.PreferencesManager.hasAcceptedBarcodeLookup]
 * is true — the one-time consent dialog in [com.appricodes.lens_whisper.BarcodeScanActivity].
 * A 2D code (QR and friends) never reaches here at all: its payload is its own content, so there
 * is nothing to look up and no reason to transmit anything. See
 * [com.appricodes.lens_whisper.offline.ScannedBarcode.isTwoDimensional].
 *
 * The trial endpoint is rate-limited per IP and answers with `code: "EXCEED_LIMIT"` (HTTP 429)
 * once a device has used its daily allowance — a completely ordinary outcome here, not an error
 * state worth its own UI, so it maps to [UpcLookupResult.NotFound] like everything else that
 * doesn't produce a description. Only the barcode digits are sent; no device identifier, no
 * location, nothing about the user.
 */
object UpcLookupClient {

    private const val TAG = "UpcLookupClient"
    private const val ENDPOINT = "https://api.upcitemdb.com/prod/trial/lookup"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Descriptive fields worth reading out, in the order they should be presented. Price/offers/
     * merchant fields from the response (`lowest_recorded_price`, `highest_recorded_price`,
     * `offers`, `currency`, ...) are simply not in this list and so are never parsed or shown.
     * `images` is likewise skipped: this screen is read aloud, not looked at.
     */
    private val DESCRIPTIVE_FIELDS = listOf(
        "title" to "Product",
        "brand" to "Brand",
        "category" to "Category",
        "description" to "Description",
        "size" to "Size",
        "weight" to "Weight",
        "color" to "Color",
        "model" to "Model",
        "dimension" to "Dimensions"
    )

    /**
     * Whether [barcode] is something this endpoint could look up at all: a non-empty run of digits,
     * which is what every retail product code (UPC/EAN/ITF) is. Callers use this to decide whether
     * to ask the user for consent in the first place — a Code 39 label carrying `ASSET/4471-B`
     * would never be sent regardless, so prompting "may we send this?" about it would be asking
     * permission for something that is not going to happen. [lookup] re-applies the same test
     * itself and is the real guard; this one only exists so the *question* is honest.
     */
    fun isLookupCandidate(barcode: String): Boolean =
        barcode.trim().let { it.isNotEmpty() && it.all { c -> c.isDigit() } }

    /**
     * Runs the lookup on [Dispatchers.IO]. Never throws — every failure path returns
     * [UpcLookupResult.NotFound]; see this object's own doc for why they collapse.
     */
    suspend fun lookup(barcode: String): UpcLookupResult = withContext(Dispatchers.IO) {
        val upc = barcode.trim()
        // Guard the URL as well as the caller: only digits ever belong in a product-code lookup,
        // and this is the one outbound request in the app built from scanned (i.e. attacker-
        // supplyable) content — a QR code's payload must not be able to steer it, even if some
        // future caller got the 1D/2D branch wrong.
        if (!isLookupCandidate(upc)) {
            Log.w(TAG, "Refusing to look up a non-numeric barcode value")
            return@withContext UpcLookupResult.NotFound
        }
        val request = Request.Builder()
            .url("$ENDPOINT?upc=$upc")
            .header("Accept", "application/json")
            .build()
        val body = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    // 429 here is the trial endpoint's daily rate limit — expected, not a bug.
                    Log.i(TAG, "Lookup returned HTTP ${response.code}")
                    return@withContext UpcLookupResult.NotFound
                }
                text
            }
        } catch (e: Exception) {
            Log.i(TAG, "Lookup failed: ${e.javaClass.simpleName}")
            return@withContext UpcLookupResult.NotFound
        } ?: return@withContext UpcLookupResult.NotFound

        parse(body)
    }

    /** Split out from [lookup] so the response shape is testable without a network call. */
    internal fun parse(body: String): UpcLookupResult = try {
        val root = JSONObject(body)
        val code = root.optString("code")
        if (!code.equals("OK", ignoreCase = true)) {
            Log.i(TAG, "Lookup response code was '$code'")
            UpcLookupResult.NotFound
        } else {
            val items = root.optJSONArray("items")
            val item = if (items != null && items.length() > 0) items.optJSONObject(0) else null
            if (item == null) {
                UpcLookupResult.NotFound
            } else {
                val fields = DESCRIPTIVE_FIELDS.mapNotNull { (jsonKey, label) ->
                    val value = item.optString(jsonKey).trim()
                    if (value.isEmpty() || value.equals("null", ignoreCase = true)) null else label to value
                }
                if (fields.isEmpty()) UpcLookupResult.NotFound else UpcLookupResult.Found(fields)
            }
        }
    } catch (e: Exception) {
        // An unexpected shape is worth a log line — this is a third-party API that can change
        // under us — but it is still just "no information" to the user.
        Log.w(TAG, "Could not parse lookup response: ${body.take(300)}", e)
        UpcLookupResult.NotFound
    }
}
