package com.appricodes.lens_whisper.offline

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import java.security.MessageDigest
import kotlin.math.abs
import zxingcpp.BarcodeReader
import zxingcpp.BarcodeReader.Format

/**
 * One barcode found in a frame.
 *
 * [isTwoDimensional] is the single most important field here: it decides everything downstream.
 * A 2D symbology (QR, Data Matrix, Aztec, PDF417) *carries its own payload* — the content is the
 * information, so it is shown as-is and **never sent anywhere**. A 1D symbology (EAN/UPC/Code 128/
 * ...) is only an identifier that means nothing on its own, so it is the one that can be looked up
 * against [com.appricodes.lens_whisper.network.UpcLookupClient] — behind the user's explicit,
 * one-time consent. Nothing else in the app may make that "does this leave the device?" decision;
 * it is this flag, everywhere.
 *
 * [format] is a zxing-cpp [Format.value]. [areaPx] is the barcode outline's pixel area, used to
 * pick a winner when a frame contains several barcodes at once (see [pickBest]).
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
 * Barcode scanning via zxing-cpp (Apache-2.0, bundled native library — no model, no download, no
 * Play Services), so it is ready the moment the app is installed.
 *
 * Two entry points, deliberately different shapes:
 * - [scan] (`ImageProxy`-backed) is the live path used by
 *   [com.appricodes.lens_whisper.BarcodeScanActivity]'s continuous scan loop. It analyzes the
 *   camera's own frames rather than taking a photo per attempt: no shutter sound and no flash once
 *   a second for as long as the user is hunting for the barcode, and far lower latency. Reads only
 *   the frame's luminance plane, synchronously; **the caller owns closing the `ImageProxy`**
 *   afterwards.
 * - [scan] (`Bitmap`-backed) is the one-shot path for a host that has an image but no live feed —
 *   Media Describer's file/frame mode (see [com.appricodes.lens_whisper.ui.models.MlkitButtonProvider]).
 */
object OfflineBarcodeScanner {

    private const val TAG = "OfflineBarcodeScanner"

    /** Formats whose payload *is* the information, rather than an identifier to look up. Anything
     *  in this set never reaches the network — see [ScannedBarcode.isTwoDimensional]. */
    private val TWO_DIMENSIONAL_FORMATS = setOf(
        Format.QR_CODE, Format.QR_CODE_MODEL_1, Format.QR_CODE_MODEL_2, Format.MICRO_QR_CODE, Format.RMQR_CODE,
        Format.DATA_MATRIX,
        Format.AZTEC, Format.AZTEC_CODE, Format.AZTEC_RUNE,
        Format.PDF_417, Format.COMPACT_PDF_417, Format.MICRO_PDF_417,
        Format.MAXI_CODE
    ).map { it.value }.toSet()

    // Every readable format: the whole point of this screen is that the user does not know (and,
    // being unable to see it, cannot know) what kind of barcode they are pointing at. The try*
    // flags trade a little speed for finding codes that are rotated, inverted or small in frame —
    // the norm for a camera aimed without seeing.
    private val reader by lazy {
        BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(Format.ALL_READABLE),
                tryHarder = true,
                tryRotate = true,
                tryInvert = true,
                tryDownscale = true
            )
        )
    }

    /** Scans one live camera frame (YUV, as CameraX's ImageAnalysis delivers by default). */
    @Synchronized
    fun scan(image: ImageProxy): List<ScannedBarcode> = try {
        reader.read(image).mapNotNull { it.toScanned() }
    } catch (e: Exception) {
        // A malformed frame is just "nothing found this time" for a loop that is about to try the
        // next one anyway.
        Log.w(TAG, "Barcode scan failed", e)
        emptyList()
    }

    /** Scans one already-decoded still image — the one-shot path for a host with no live feed. */
    @Synchronized
    fun scan(bitmap: Bitmap): List<ScannedBarcode> = try {
        // The native reader locks the pixels directly, which a HARDWARE bitmap doesn't allow.
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        reader.read(readable).mapNotNull { it.toScanned() }
    } catch (e: Exception) {
        Log.w(TAG, "Barcode scan failed", e)
        emptyList()
    }

    private fun BarcodeReader.Result.toScanned(): ScannedBarcode? {
        // text is null/blank for a binary-payload symbology with no text form, and error is set for
        // a barcode that was located but could not be decoded. Nothing downstream — neither the
        // lookup nor the note key — can do anything with either, so both count as "not found".
        if (error != null) return null
        val raw = text?.takeIf { it.isNotBlank() } ?: return null
        // zxing-cpp reports no confidence score, so the "size times confidence" ranking the feature
        // was specified with reduces to size alone: the outline quadrilateral's area (shoelace).
        val p = listOf(position.topLeft, position.topRight, position.bottomRight, position.bottomLeft)
        var twiceArea = 0L
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            twiceArea += a.x.toLong() * b.y - b.x.toLong() * a.y
        }
        return ScannedBarcode(
            value = raw.trim(),
            format = format.value,
            isTwoDimensional = format.value in TWO_DIMENSIONAL_FORMATS,
            areaPx = (abs(twiceArea) / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    }

    /** Picks the winner when a frame holds several barcodes: the physically largest one, which in
     *  practice is the one the user is actually pointing at rather than a neighbouring package's
     *  that happened to fall in frame. Ties fall back to the first, keeping this total rather than
     *  nullable for a non-empty list. */
    fun pickBest(barcodes: List<ScannedBarcode>): ScannedBarcode? =
        barcodes.maxByOrNull { it.areaPx } ?: barcodes.firstOrNull()
}

/** A short human/TalkBack-readable name for a zxing-cpp [Format.value]. Lives at file scope rather
 *  than inside [OfflineBarcodeScanner] because [ScannedBarcode.formatName] needs it and a private
 *  object member is not visible from a top-level class, even in the same file. */
private fun formatDisplayName(format: Int): String = when (Format.entries.firstOrNull { it.value == format }) {
    Format.QR_CODE, Format.QR_CODE_MODEL_1, Format.QR_CODE_MODEL_2 -> "QR code"
    Format.MICRO_QR_CODE -> "Micro QR code"
    Format.RMQR_CODE -> "rMQR code"
    Format.DATA_MATRIX -> "Data Matrix code"
    Format.AZTEC, Format.AZTEC_CODE, Format.AZTEC_RUNE -> "Aztec code"
    Format.PDF_417, Format.COMPACT_PDF_417, Format.MICRO_PDF_417 -> "PDF417 code"
    Format.MAXI_CODE -> "MaxiCode"
    Format.EAN_13 -> "EAN-13 barcode"
    Format.EAN_8 -> "EAN-8 barcode"
    Format.ISBN -> "ISBN barcode"
    Format.UPC_A -> "UPC-A barcode"
    Format.UPC_E -> "UPC-E barcode"
    Format.CODE_128 -> "Code 128 barcode"
    Format.CODE_39, Format.CODE_39_STD, Format.CODE_39_EXT -> "Code 39 barcode"
    Format.CODE_93 -> "Code 93 barcode"
    Format.CODABAR -> "Codabar barcode"
    Format.ITF, Format.ITF_14 -> "ITF barcode"
    Format.DATA_BAR, Format.DATA_BAR_OMNI, Format.DATA_BAR_STK, Format.DATA_BAR_STK_OMNI,
    Format.DATA_BAR_LTD, Format.DATA_BAR_EXP, Format.DATA_BAR_EXP_STK -> "GS1 DataBar barcode"
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
