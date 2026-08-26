package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/** One paragraph-equivalent unit of recognized text — ML Kit has no explicit "paragraph" concept,
 *  but a `Text.TextBlock` (a group of lines OCR grouped together) is the closest real one, and is
 *  what Read Document treats as its "objects" to hover over — see
 *  [com.appricodes.lens_whisper.ReadDocumentActivity]. [boxLeft]/[boxTop]/[boxRight]/[boxBottom]
 *  are normalized 0..1 against the image's own dimensions, matching
 *  [com.appricodes.lens_whisper.offline.DetectedInstance]'s convention so the same kind of
 *  hit-testing/hover code works for both. */
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

object OfflineTextRecognizer {

    // Guards the Bitmap overload against overlapping continuous-mode captures.
    private val isProcessing = AtomicBoolean(false)

    private fun recognizerOptionsFor(targetLanguage: String) = when (targetLanguage) {
        "Chinese" -> ChineseTextRecognizerOptions.Builder().build()
        "Japanese" -> JapaneseTextRecognizerOptions.Builder().build()
        "Korean" -> KoreanTextRecognizerOptions.Builder().build()
        "Devanagari" -> DevanagariTextRecognizerOptions.Builder().build()
        else -> TextRecognizerOptions.DEFAULT_OPTIONS // Default Latin/English
    }

    /** Suspend, paragraph-level counterpart to [recognize] — used by
     *  [com.appricodes.lens_whisper.ReadDocumentActivity], which needs each block's own location,
     *  not just the whole image's concatenated text. Not guarded by [isProcessing]: Read Document
     *  only ever calls this once per captured photo, awaited before doing anything else with the
     *  result, so the same overlapping-call concern [recognize]'s Bitmap overload guards against
     *  doesn't apply here. Returns an empty list on failure or if the bitmap can't be read, rather
     *  than throwing — the caller treats "no blocks" as "no text found", not as an error to report
     *  differently. */
    suspend fun recognizeBlocks(bitmap: Bitmap, targetLanguage: String): List<TextBlockResult> =
        suspendCoroutine { continuation ->
            try {
                val recognizer = TextRecognition.getClient(recognizerOptionsFor(targetLanguage))
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { visionText ->
                        val blocks = visionText.textBlocks.mapNotNull { block ->
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
                        continuation.resume(blocks)
                    }
                    .addOnFailureListener { continuation.resume(emptyList()) }
            } catch (e: Exception) {
                continuation.resume(emptyList())
            }
        }

    private fun process(image: InputImage, targetLanguage: String, onResult: (String) -> Unit) {
        val recognizer = TextRecognition.getClient(recognizerOptionsFor(targetLanguage))
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                onResult(if (text.isNotBlank()) text else "No text found.")
            }
            .addOnFailureListener {
                onResult("Text recognition failed.")
            }
    }

    fun recognize(context: Context, uri: Uri, targetLanguage: String, onResult: (String) -> Unit) {
        try {
            process(InputImage.fromFilePath(context, uri), targetLanguage, onResult)
        } catch (e: Exception) {
            onResult("Error loading image for text recognition.")
        }
    }

    /** Used by continuous-mode capture, which already has an in-memory photo. */
    fun recognize(bitmap: Bitmap, targetLanguage: String, onResult: (String) -> Unit) {
        if (!isProcessing.compareAndSet(false, true)) return
        fun finish(text: String) {
            isProcessing.set(false)
            onResult(text)
        }
        try {
            process(InputImage.fromBitmap(bitmap, 0), targetLanguage) { finish(it) }
        } catch (e: Exception) {
            finish("Error processing camera frame.")
        }
    }
}