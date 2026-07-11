package com.appricodes.lens_whisper.offline

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OfflineTextRecognizer {

    fun recognize(context: Context, uri: Uri, targetLanguage: String, onResult: (String) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, uri)

            // Map the selected string setting to the correct ML Kit Options object
            val recognizerOptions = when (targetLanguage) {
                "Chinese" -> ChineseTextRecognizerOptions.Builder().build()
                "Japanese" -> JapaneseTextRecognizerOptions.Builder().build()
                "Korean" -> KoreanTextRecognizerOptions.Builder().build()
                "Devanagari" -> DevanagariTextRecognizerOptions.Builder().build()
                else -> TextRecognizerOptions.DEFAULT_OPTIONS // Default Latin/English
            }

            val recognizer = TextRecognition.getClient(recognizerOptions)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    if (text.isNotBlank()) {
                        onResult(text)
                    } else {
                        onResult("No text found.")
                    }
                }
                .addOnFailureListener {
                    onResult("Text recognition failed.")
                }
        } catch (e: Exception) {
            onResult("Error loading image for text recognition.")
        }
    }
}