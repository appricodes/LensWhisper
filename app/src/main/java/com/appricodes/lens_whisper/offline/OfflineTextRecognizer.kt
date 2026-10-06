package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.appricodes.lens_whisper.utils.AccessibilityUtils
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/** One paragraph-equivalent unit of recognized text — ML Kit's `TextBlock`, or for PaddleOCR a
 *  group of nearby lines (see [OfflineTextRecognizer.groupIntoBlocks]) — and what Read Document
 *  treats as its "objects" to hover over (see [com.appricodes.lens_whisper.ReadDocumentActivity]).
 *  [boxLeft]/[boxTop]/[boxRight]/[boxBottom] are normalized 0..1 against the image's own
 *  dimensions, matching [com.appricodes.lens_whisper.offline.DetectedInstance]'s convention so the
 *  same kind of hit-testing/hover code works for both. */
class TextBlockResult(
    val text: String,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float
) {
    fun containsNormalizedPoint(nx: Float, ny: Float): Boolean =
        nx in boxLeft..boxRight && ny in boxTop..boxBottom
}

/**
 * Read Text / Read Document's recognizer. Two engines, picked per [OcrScript]:
 * - **ML Kit** (Latin, Chinese, Japanese, Korean, Devanagari): the unbundled Play Services
 *   recognizers — no weights in the APK; each script is its own on-demand module (see [isReady]).
 * - **[PaddleOcr]** (Persian, Arabic, Cyrillic — scripts ML Kit has no recognizer for): bundled
 *   models, plus the layout step here that turns its individual lines into reading-order text and
 *   paragraph blocks.
 * [language] everywhere is the stored [com.appricodes.lens_whisper.data.PreferencesManager.ocrLanguage]
 * label — see [OcrScript].
 */
object OfflineTextRecognizer {

    private const val TAG = "OfflineTextRecognizer"

    // Guards [recognizeText] against overlapping continuous-mode captures.
    private val isProcessing = AtomicBoolean(false)

    private fun usesMlKit(script: OcrScript) = script.paddleModel == null

    private fun mlKitClient(script: OcrScript): TextRecognizer = TextRecognition.getClient(
        when (script) {
            OcrScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            OcrScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            OcrScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            OcrScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        }
    )

    /** Scripts [isReady] has already announced "not ready" for once this process — a
     *  continuous-mode Read Text poll retries every few seconds and must not repeat the warning. */
    private val warnedNotReady = mutableSetOf<OcrScript>()

    /**
     * True if [language]'s recognizer can run right now. Always true for the bundled PaddleOCR
     * scripts. For an ML Kit script, checks that its Play Services module is installed — a device
     * ready for Latin may not have Chinese yet. Never blocks on a download: if the module is
     * missing, this starts the download, announces "not ready, please wait" (once per script per
     * process) and returns false, so the caller can skip a capture that would fail anyway.
     */
    suspend fun isReady(context: Context, language: String): Boolean {
        val script = OcrScript.fromLabel(language)
        if (!usesMlKit(script)) return true
        val available = try {
            suspendCancellableCoroutine<Boolean> { cont ->
                ModuleInstall.getClient(context).areModulesAvailable(mlKitClient(script))
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it.areModulesAvailable()) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(false) }
            }
        } catch (e: Exception) {
            false
        }
        if (!available) {
            startModuleDownload(context, language)
            if (warnedNotReady.add(script)) {
                AccessibilityUtils.announceToTalkBack(context, "The text reader isn't ready yet. Please wait for it to finish downloading, then try again.")
            }
        }
        return available
    }

    /** Fire-and-forget: asks Play Services to install [language]'s ML Kit module now rather than on
     *  first use. Called when the OCR language changes in Settings and from [isReady]. A no-op for
     *  the bundled PaddleOCR scripts; safe to call repeatedly. */
    fun startModuleDownload(context: Context, language: String) {
        val script = OcrScript.fromLabel(language)
        if (!usesMlKit(script)) return
        try {
            val request = ModuleInstallRequest.newBuilder().addApi(mlKitClient(script)).build()
            ModuleInstall.getClient(context).installModules(request)
                .addOnFailureListener { e -> Log.e(TAG, "OCR module install failed for $language", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not request OCR module install for $language", e)
        }
    }

    private suspend fun mlKitText(bitmap: Bitmap, script: OcrScript): Text =
        suspendCancellableCoroutine { cont ->
            mlKitClient(script).process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    /** Whole-image text, paragraph by paragraph, one printed line per line — what Read Text speaks.
     *  Returns null, without doing anything, if a previous call is still running (continuous mode
     *  polls faster than a slow phone can always finish). */
    suspend fun recognizeText(context: Context, bitmap: Bitmap, language: String): String? {
        if (!isProcessing.compareAndSet(false, true)) return null
        return try {
            val script = OcrScript.fromLabel(language)
            val text = if (usesMlKit(script)) {
                mlKitText(bitmap, script).textBlocks.joinToString("\n") { it.text.trim() }.trim()
            } else {
                val lines = withContext(Dispatchers.Default) { PaddleOcr.recognize(context, bitmap, script) }
                // Block by block, so a two-column page is read column-wise within each paragraph.
                groupIntoBlocks(lines, script.rightToLeft, bitmap.width, bitmap.height).joinToString("\n") { it.text }
            }
            text.ifBlank { "No text found." }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Text recognition failed", e)
            "Text recognition failed."
        } finally {
            isProcessing.set(false)
        }
    }

    /** Paragraph-level counterpart to [recognizeText] — used by
     *  [com.appricodes.lens_whisper.ReadDocumentActivity], which needs each block's own location,
     *  not just the whole image's text. Returns an empty list on failure rather than throwing —
     *  the caller treats "no blocks" as "no text found". */
    suspend fun recognizeBlocks(context: Context, bitmap: Bitmap, language: String): List<TextBlockResult> = try {
        val script = OcrScript.fromLabel(language)
        if (usesMlKit(script)) {
            mlKitText(bitmap, script).textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val text = block.text.trim()
                if (text.isEmpty()) return@mapNotNull null
                TextBlockResult(
                    text = text,
                    boxLeft = (box.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                    boxTop = (box.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                    boxRight = (box.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                    boxBottom = (box.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
                )
            }
        } else {
            val lines = withContext(Dispatchers.Default) { PaddleOcr.recognize(context, bitmap, script) }
            groupIntoBlocks(lines, script.rightToLeft, bitmap.width, bitmap.height)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.e(TAG, "Text recognition failed", e)
        emptyList()
    }

    /** A row of lines that sit side by side (PaddleOCR often splits one printed line at wide gaps,
     *  e.g. table columns), joined in reading order. */
    private class Row(val lines: List<OcrLine>, rightToLeft: Boolean) {
        val text = (if (rightToLeft) lines.sortedByDescending { it.right } else lines.sortedBy { it.left })
            .joinToString(" ") { it.text }
        val top = lines.minOf { it.top }
        val bottom = lines.maxOf { it.bottom }
        val left = lines.minOf { it.left }
        val right = lines.maxOf { it.right }
        val textHeight = lines.maxOf { it.textHeight }
    }

    /** Groups lines into rows top to bottom: a line joins a row when its vertical centre falls
     *  inside the row's first line, it is of similar height, and it sits close beside the row —
     *  so the same printed line in two page columns stays two rows. */
    private fun rows(lines: List<OcrLine>, rightToLeft: Boolean): List<Row> {
        val sorted = lines.sortedBy { it.top }
        val groups = ArrayList<MutableList<OcrLine>>()
        for (line in sorted) {
            val centre = (line.top + line.bottom) / 2
            val row = groups.firstOrNull { g ->
                val first = g[0]
                val gap = max(line.left - g.maxOf { it.right }, g.minOf { it.left } - line.right)
                centre in first.top..first.bottom && line.textHeight < 2 * first.textHeight && first.textHeight < 2 * line.textHeight &&
                    // Beside it, not on top of it: on a tilted photo, consecutive lines' upright
                    // bounds overlap vertically but also horizontally.
                    gap > -0.5f * max(line.textHeight, first.textHeight) && gap < 1.5f * max(line.textHeight, first.textHeight)
            }
            if (row != null) row += line else groups += mutableListOf(line)
        }
        return groups.map { Row(it, rightToLeft) }.sortedBy { it.top }
    }

    /** Paragraph-ish blocks: consecutive rows that overlap horizontally and sit within about a line
     *  height of each other belong together — the closest stand-in for ML Kit's `TextBlock`. */
    internal fun groupIntoBlocks(lines: List<OcrLine>, rightToLeft: Boolean, imageWidth: Int, imageHeight: Int): List<TextBlockResult> {
        val blocks = ArrayList<MutableList<Row>>()
        for (row in rows(lines, rightToLeft)) {
            val rowHeight = row.textHeight
            val block = blocks.lastOrNull { b ->
                val last = b.last()
                val lastHeight = last.textHeight
                val gap = row.top - last.bottom
                val overlap = min(row.right, b.maxOf { it.right }) - max(row.left, b.minOf { it.left })
                gap < 0.8f * max(rowHeight, lastHeight) && overlap > 0 &&
                    rowHeight < 1.6f * lastHeight && lastHeight < 1.6f * rowHeight
            }
            if (block != null) block += row else blocks += mutableListOf(row)
        }
        return blocks.map { b ->
            TextBlockResult(
                text = b.joinToString("\n") { it.text },
                // In image coordinates (not the upright frame the grouping used), since that is
                // what Read Document's touch hit-testing is measured against.
                boxLeft = (b.minOf { r -> r.lines.minOf { it.imageLeft } } / imageWidth).coerceIn(0f, 1f),
                boxTop = (b.minOf { r -> r.lines.minOf { it.imageTop } } / imageHeight).coerceIn(0f, 1f),
                boxRight = (b.maxOf { r -> r.lines.maxOf { it.imageRight } } / imageWidth).coerceIn(0f, 1f),
                boxBottom = (b.maxOf { r -> r.lines.maxOf { it.imageBottom } } / imageHeight).coerceIn(0f, 1f)
            )
        }
    }
}
