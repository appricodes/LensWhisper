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
import java.util.concurrent.atomic.AtomicBoolean

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