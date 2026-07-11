package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.appricodes.lens_whisper.data.PreferencesManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.atomic.AtomicBoolean

object OfflineObjectDetector {

	// Concurrency lock to prevent stacking tasks if ML Kit is slow on an older device
	private val isProcessing = AtomicBoolean(false)

	fun detect(context: Context, bitmap: Bitmap, prefs: PreferencesManager) {
		// If the previous frame is still being processed, ignore this new frame
		if (!isProcessing.compareAndSet(false, true)) {
			return
		}

		try {
			// Process the Bitmap directly from memory (much faster than Uri/Disk for continuous mode)
			val image = InputImage.fromBitmap(bitmap, 0)
			val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

			labeler.process(image)
				.addOnSuccessListener { labels ->
					val finalSentence = if (labels.isEmpty()) {
						"No objects detected."
					} else {
						val detectedObjects = labels.filter { it.confidence > 0.7f }.map { it.text }
						if (detectedObjects.isNotEmpty()) "${detectedObjects.joinToString(", ")}."
						else "No distinct objects detected."
					}

					OfflineTranslator.translateAndSpeak(context, finalSentence, prefs)
					isProcessing.set(false) // Release lock
				}
				.addOnFailureListener { e ->
					Log.e("OfflineObjectDetector", "ML Kit Labeling failed", e)
					OfflineTranslator.translateAndSpeak(context, "Failed to analyze image.", prefs)
					isProcessing.set(false) // Release lock
				}
		} catch (e: Exception) {
			Log.e("OfflineObjectDetector", "Image processing error", e)
			OfflineTranslator.translateAndSpeak(context, "Error processing camera frame.", prefs)
			isProcessing.set(false) // Release lock
		}
	}
}