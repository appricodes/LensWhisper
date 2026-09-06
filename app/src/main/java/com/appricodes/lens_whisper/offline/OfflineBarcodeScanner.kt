package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * One barcode ML Kit found in a frame.
 *
 * [isTwoDimensional] is the single most important field here: it decides everything downstream.
 * A 2D symbology (QR, Data Matrix, Aztec, PDF417) *carries its own payload* — the content is the
 * information, so it is shown as-is and **never sent anywhere**. A 1D symbology (EAN/UPC/Code 128/
 * ...) is only an identifier that means nothing on its own, so it is the one that can be looked up
 * against [com.appricodes.lens_whisper.network.UpcLookupClient] — behind the user's explicit,
 * one-time consent. Nothing else in the app may make that "does this leave the device?" decision;
 * it is this flag, everywhere.
 *
 * [areaPx] is the bounding box's pixel area, used to pick a winner when a frame contains several
 * barcodes at once (see [pickBest]).
 */
data class ScannedBarcode(
    val value: String,
    val format: Int,
    val isTwoDimensional: Boolean,
    val areaPx: Int
) {
    /** A short human/TalkBack-readable name for [format] — "QR code", "EAN-13", etc. */
    val formatName: String get() = formatDisplayName(format)
}

/**
 * ML Kit barcode scanning (`play-services-mlkit-barcode-scanning` — unbundled, the model is a Play
 * Services module fetched on demand, same arrangement as [OfflineTextRecognizer]'s OCR; see the
 * `com.google.mlkit.vision.DEPENDENCIES` manifest meta-data, which asks for "barcode" alongside
 * "ocr").
 *
 * Two entry points, deliberately different shapes:
 * - [scan] (`ImageProxy`-backed) is the live path used by
 *   [com.appricodes.lens_whisper.BarcodeScanActivity]'s continuous scan loop. It analyzes the
 *   camera's own frames rather than taking a photo per attempt: no shutter sound and no flash once
 *   a second for as long as the user is hunting for the barcode, far lower latency, and it is the
 *   access pattern ML Kit's barcode model is actually built around. **The caller owns closing the
 *   `ImageProxy`** — ML Kit's `process()` is asynchronous and needs the underlying `Image` to stay
 *   valid until it completes, so this cannot close it in a `finally` the way a synchronous
 *   analyzer would.
 * - [scan] (`Bitmap`-backed) is the one-shot path for a host that has an image but no live feed —
 *   Media Describer's file/frame mode (see [com.appricodes.lens_whisper.ui.models.MlkitButtonProvider]).
 *
 * One scanner client is held for the process and reused. ML Kit clients are expensive to build and
 * cheap to keep, and this one is stateless across calls; [close] exists for completeness but the
 * common case never needs it.
 */
object OfflineBarcodeScanner {

    private const val TAG = "OfflineBarcodeScanner"

    /** Formats whose payload *is* the information, rather than an identifier to look up. Anything
     *  in this set never reaches the network — see [ScannedBarcode.isTwoDimensional]. */
    private val TWO_DIMENSIONAL_FORMATS = setOf(
        Barcode.FORMAT_QR_CODE,
        Barcode.FORMAT_DATA_MATRIX,
        Barcode.FORMAT_AZTEC,
        Barcode.FORMAT_PDF417
    )

    @Volatile
    private var client: BarcodeScanner? = null

    /** Announced-once guard, mirroring [OfflineTextRecognizer.warnedNotReadyLanguages] — the scan
     *  loop retries continuously and must not repeat a "not ready" warning on every frame. */
    private var warnedNotReady = false

    private fun scanner(): BarcodeScanner {
        client?.let { return it }
        return synchronized(this) {
            client ?: BarcodeScanning.getClient(
                // Every format ML Kit supports: the whole point of this screen is that the user
                // does not know (and, being unable to see it, cannot know) what kind of barcode
                // they are pointing at. Restricting the format list is ML Kit's recommended
                // speed optimization, but it would mean silently failing on whatever was left out.
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                    .build()
            ).also { client = it }
        }
    }

    /**
     * True if the barcode module is downloaded and usable right now. Same contract as
     * [OfflineTextRecognizer.isReady]: never blocks on a download, announces "not ready" at most
     * once per process, and returning false only means "don't bother starting a scan that is going
     * to fail". The manifest's `DEPENDENCIES` meta-data means the common case is already installed
     * around app-install time and this is just a cold-start guard.
     */
    suspend fun isReady(context: Context): Boolean {
        val available = try {
            suspendCancellableCoroutine { cont ->
                ModuleInstall.getClient(context).areModulesAvailable(scanner())
                    .addOnSuccessListener { response -> if (cont.isActive) cont.resume(response.areModulesAvailable()) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(false) }
            }
        } catch (e: Exception) {
            false
        }
        if (!available) {
            // Actually ask for the module, don't just report its absence. The manifest's
            // DEPENDENCIES meta-data usually gets it installed around app-install time, but that is
            // best-effort — on a device where it didn't happen, nothing else in the app would ever
            // trigger the download, and this whole feature would sit permanently on a message
            // promising a download that was never requested.
            startModuleDownload(context)
            if (!warnedNotReady) {
                warnedNotReady = true
                AccessibilityUtils.announceToTalkBack(
                    context,
                    "The barcode reader isn't ready yet. Please wait for it to finish downloading, then try again."
                )
            }
        }
        return available
    }

    /** Fire-and-forget request that Play Services install the barcode module now. Mirrors
     *  [OfflineTextRecognizer.startModuleDownload]; safe to call repeatedly, since `installModules`
     *  is a fast no-op once the module is present. */
    fun startModuleDownload(context: Context) {
        try {
            val request = ModuleInstallRequest.newBuilder().addApi(scanner()).build()
            ModuleInstall.getClient(context).installModules(request)
                .addOnFailureListener { e -> Log.e(TAG, "Barcode module install failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not request barcode module install", e)
        }
    }

    /**
     * Scans one live camera frame. [image] is the `ImageProxy`'s own `Image`; [rotationDegrees] is
     * its `imageInfo.rotationDegrees`. The caller must keep the `ImageProxy` open until this
     * returns and close it afterwards — see this object's own doc.
     */
    suspend fun scan(image: Image, rotationDegrees: Int): List<ScannedBarcode> =
        process(InputImage.fromMediaImage(image, rotationDegrees))

    /** Scans one already-decoded still image — the one-shot path for a host with no live feed. */
    suspend fun scan(bitmap: Bitmap): List<ScannedBarcode> =
        process(InputImage.fromBitmap(bitmap, 0))

    private suspend fun process(input: InputImage): List<ScannedBarcode> = try {
        suspendCancellableCoroutine { cont ->
            scanner().process(input)
                .addOnSuccessListener { barcodes ->
                    if (cont.isActive) cont.resume(barcodes.mapNotNull { it.toScanned() })
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Barcode scan failed", e)
                    if (cont.isActive) cont.resume(emptyList())
                }
        }
    } catch (e: Exception) {
        // fromMediaImage/fromBitmap can throw on a malformed frame; a bad frame is just "nothing
        // found this time" for a loop that is about to try the next one anyway.
        Log.w(TAG, "Could not build an input image for barcode scanning", e)
        emptyList()
    }

    private fun Barcode.toScanned(): ScannedBarcode? {
        // rawValue is null for a barcode ML Kit located but could not decode (damaged/blurred), and
        // for binary-payload symbologies with no text form. Nothing downstream — neither the
        // lookup nor the note key — can do anything with that, so it counts as "not found".
        val raw = rawValue?.takeIf { it.isNotBlank() } ?: return null
        val box = boundingBox
        // ML Kit's Barcode exposes no confidence score of any kind (unlike the segmentation
        // detector's per-instance confidence), so the "size times confidence" ranking the feature
        // was specified with reduces to size alone. Documented here rather than faked with a
        // constant, so nothing downstream reads a 1.0 as a real measurement.
        val area = if (box != null) box.width().coerceAtLeast(0) * box.height().coerceAtLeast(0) else 0
        return ScannedBarcode(
            value = raw.trim(),
            format = format,
            isTwoDimensional = format in TWO_DIMENSIONAL_FORMATS,
            areaPx = area
        )
    }

    /** Picks the winner when a frame holds several barcodes: the physically largest one, which in
     *  practice is the one the user is actually pointing at rather than a neighbouring package's
     *  that happened to fall in frame. Ties (including "every box was null") fall back to the
     *  first, keeping this total rather than nullable for a non-empty list. */
    fun pickBest(barcodes: List<ScannedBarcode>): ScannedBarcode? =
        barcodes.maxByOrNull { it.areaPx } ?: barcodes.firstOrNull()

    fun close() {
        synchronized(this) {
            runCatching { client?.close() }
            client = null
        }
    }
}

/** A short human/TalkBack-readable name for one of ML Kit's `Barcode.FORMAT_*` constants. Lives at
 *  file scope rather than inside [OfflineBarcodeScanner] because [ScannedBarcode.formatName] needs
 *  it and a private object member is not visible from a top-level class, even in the same file. */
private fun formatDisplayName(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR code"
    Barcode.FORMAT_DATA_MATRIX -> "Data Matrix code"
    Barcode.FORMAT_AZTEC -> "Aztec code"
    Barcode.FORMAT_PDF417 -> "PDF417 code"
    Barcode.FORMAT_EAN_13 -> "EAN-13 barcode"
    Barcode.FORMAT_EAN_8 -> "EAN-8 barcode"
    Barcode.FORMAT_UPC_A -> "UPC-A barcode"
    Barcode.FORMAT_UPC_E -> "UPC-E barcode"
    Barcode.FORMAT_CODE_128 -> "Code 128 barcode"
    Barcode.FORMAT_CODE_39 -> "Code 39 barcode"
    Barcode.FORMAT_CODE_93 -> "Code 93 barcode"
    Barcode.FORMAT_CODABAR -> "Codabar barcode"
    Barcode.FORMAT_ITF -> "ITF barcode"
    else -> "barcode"
}

/**
 * The one function that turns a scanned barcode into the identity the app stores it under —
 * **both** the notes database's primary key and the voice-note file's name, deliberately the same
 * string in both places. If those two ever diverged (say the filename sanitized a character the
 * database key kept), a note saved one day would silently fail to come back the next, which is the
 * entire point of the feature.
 *
 * Shape, per spec: `1D_<value>` for a product barcode, `QR_<hash>` for a 2D code's content. 1D
 * values are effectively always digits, but Code 39/Code 128/Codabar can legitimately carry `/`,
 * spaces and other characters that are not safe in a filename — so any value that is not purely
 * `[A-Za-z0-9._-]` falls back to a hash of itself, exactly like a 2D code does. A 2D payload is
 * hashed unconditionally: it can be a whole URL, a vCard, or kilobytes of text.
 */
object BarcodeKey {

    private val SAFE_VALUE = Regex("^[A-Za-z0-9._-]{1,64}$")

    fun of(barcode: ScannedBarcode): String = of(barcode.value, barcode.isTwoDimensional)

    fun of(value: String, isTwoDimensional: Boolean): String {
        val trimmed = value.trim()
        return if (isTwoDimensional) {
            "QR_${sha256Hex(trimmed)}"
        } else if (SAFE_VALUE.matches(trimmed)) {
            "1D_$trimmed"
        } else {
            // A 1D value that cannot be a filename verbatim. Kept in the 1D namespace (it is still
            // a product code) but identified by its hash, so key and filename stay identical.
            "1D_${sha256Hex(trimmed)}"
        }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
