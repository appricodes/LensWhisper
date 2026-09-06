package com.appricodes.lens_whisper.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * What the server says about the downloadable database right now. [sizeBytes] is 0 when the server
 * would not say (no `Content-Length`), which is unusual but not fatal — the download then simply
 * runs without a progress percentage. [sha256] is null when the sidecar is absent; see
 * [ProductDbSource.probe].
 */
data class ProductDbInfo(
    val sizeBytes: Long,
    val sha256: String?
)

/**
 * The one place the offline product database comes from.
 *
 * There is no catalog and nothing to choose: one file, one URL, published by this project from the
 * Open Food Facts / Open Products Facts dumps (see `tools/barcode_db/build_barcode_db.py`, which
 * builds it, and `publish_barcode_db.py`, which checks and uploads it). The app asks the server how
 * big it is, shows the user that number, and downloads it only if they say yes.
 *
 * **Changing where it is hosted is a one-line change**, and the app adapts to two things on its own:
 * whether the file is gzipped (decided by the URL's own extension — see [isGzipped]) and how big it
 * is (asked at the moment the user opens the screen, so a rebuilt database never needs an app
 * update to report its new size).
 */
object ProductDbSource {

    private const val TAG = "ProductDbSource"

    /**
     * The database itself. Point this at a `.gz` and the app will decompress on the fly — but then
     * the server must serve it as an opaque `application/octet-stream` download and **not** send
     * `Content-Encoding: gzip`, or OkHttp will transparently decompress it and the app will be
     * left trying to gunzip a stream that is already plain. See `publish_barcode_db.py`, which says
     * the same thing from the server side.
     */
    const val DATABASE_URL = "https://wikiponder.com/lense_whisper/products_barcodes.db"

    /** The name the installed copy is stored under, and the name the database table lives in. */
    const val LOCAL_FILE_NAME = "products_barcodes.db"

    /** True when [DATABASE_URL] points at a gzipped copy that must be unpacked as it arrives. */
    val isGzipped: Boolean get() = DATABASE_URL.endsWith(".gz", ignoreCase = true)

    /** Shown to the user in the confirmation dialog, so they are told where the data actually comes
     *  from rather than just "the internet". */
    val hostName: String
        get() = runCatching { java.net.URI(DATABASE_URL).host }.getOrNull() ?: "our server"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Asks the server what is on offer, without downloading it: a `HEAD` for the size, then the
     * small `.sha256` sidecar `publish_barcode_db.py` writes next to the database.
     *
     * Returns null only when the database itself cannot be reached at all — the screen then says so
     * instead of offering a download that would fail. A **missing sidecar is not a failure**: it
     * comes back as `sha256 = null` and the download falls back to checking the received byte count
     * against `Content-Length` and that the result opens as a usable database. That is a weaker
     * guarantee than a checksum but a perfectly real one, and it means forgetting to upload one
     * small file does not take the feature down.
     *
     * Nothing is sent but the requests themselves — no identifier, nothing about the user, and
     * nothing that has been scanned.
     */
    suspend fun probe(): ProductDbInfo? = withContext(Dispatchers.IO) {
        val size = try {
            client.newCall(Request.Builder().url(DATABASE_URL).head().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(TAG, "Database HEAD returned HTTP ${response.code}")
                    return@withContext null
                }
                response.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.i(TAG, "Could not reach the database: ${e.javaClass.simpleName}")
            return@withContext null
        }
        ProductDbInfo(sizeBytes = size, sha256 = fetchSha256())
    }

    private fun fetchSha256(): String? = try {
        client.newCall(Request.Builder().url("$DATABASE_URL.sha256").build()).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.string()
                ?.trim()
                // The sidecar is one line of hex; some checksum tools write "<hash>  <filename>".
                ?.substringBefore(' ')
                ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
        }
    } catch (e: Exception) {
        Log.i(TAG, "No checksum sidecar available: ${e.javaClass.simpleName}")
        null
    }

    /** "251 MB", "1.4 GB" — the size in the confirmation dialog is the whole point of it, so it is
     *  formatted for a person rather than printed as raw bytes. */
    fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "an unknown size"
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
        else -> "${bytes / 1_000} KB"
    }
}
