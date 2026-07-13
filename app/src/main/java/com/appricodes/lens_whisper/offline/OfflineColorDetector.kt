package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.palette.graphics.Palette
import com.appricodes.lens_whisper.data.PreferencesManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

object OfflineColorDetector {
	// Set processing flag to prevent concurrent executions
	private val isProcessing = AtomicBoolean(false)

	private class NamedColor(val name: String, val r: Int, val g: Int, val b: Int)
	private val colors = listOf(
		// Base Colors
		NamedColor("Red", 255, 0, 0),
		NamedColor("Green", 0, 255, 0),
		NamedColor("Blue", 0, 0, 255),
		NamedColor("Yellow", 255, 255, 0),
		NamedColor("Cyan", 0, 255, 255),
		NamedColor("Magenta", 255, 0, 255),
		NamedColor("White", 255, 255, 255),
		NamedColor("Black", 0, 0, 0),
		NamedColor("Gray", 128, 128, 128),
		NamedColor("Orange", 255, 165, 0),
		NamedColor("Purple", 128, 0, 128),
		NamedColor("Brown", 165, 42, 42),
		NamedColor("Pink", 255, 192, 203),

		// Dark Variations
		NamedColor("Dark Red", 139, 0, 0),
		NamedColor("Dark Green", 0, 100, 0),
		NamedColor("Dark Blue", 0, 0, 139),
		NamedColor("Dark Yellow", 204, 204, 0),
		NamedColor("Dark Cyan", 0, 139, 139),
		NamedColor("Dark Magenta", 139, 0, 139),
		NamedColor("Dark Gray", 105, 105, 105),
		NamedColor("Dark Orange", 255, 140, 0),
		NamedColor("Dark Brown", 101, 67, 33),

		// Light Variations
		NamedColor("Light Red", 255, 102, 102),
		NamedColor("Light Green", 144, 238, 144),
		NamedColor("Light Blue", 173, 216, 230),
		NamedColor("Light Yellow", 255, 255, 224),
		NamedColor("Light Cyan", 224, 255, 255),
		NamedColor("Light Magenta", 255, 119, 255),
		NamedColor("Light Gray", 211, 211, 211),
		NamedColor("Light Pink", 255, 182, 193),
		NamedColor("Light Brown", 210, 180, 140)
	)

	private fun getColorNameFromRgb(r: Int, g: Int, b: Int): String {
		return colors.minByOrNull {
			((it.r - r).toDouble().pow(2) + (it.g - g).toDouble().pow(2) + (it.b - b).toDouble().pow(2))
		}?.name ?: "Unknown"
	}

	/**
	 * Detects the main subject(s) and their colors in [bitmap]. If [onResult] is null (the
	 * default), the result is spoken immediately via [OfflineTranslator]. If provided, [onResult]
	 * receives the raw English sentence instead — used by continuous mode, which only speaks
	 * when the text changes.
	 */
	fun detect(context: Context, bitmap: Bitmap, prefs: PreferencesManager, onResult: ((String) -> Unit)? = null) {
		if (!isProcessing.compareAndSet(false, true)) return

		try {
			// Resize bitmap to max dimension of 1024
			val resizedBitmap = resizeToMax(bitmap, 1024)
			val image = InputImage.fromBitmap(resizedBitmap, 0)

			val subjectResultOptions = SubjectSegmenterOptions.SubjectResultOptions.Builder()
				.enableSubjectBitmap()
				.build()

			val options = SubjectSegmenterOptions.Builder()
				.enableMultipleSubjects(subjectResultOptions)
				.build()

			val segmenter = SubjectSegmentation.getClient(options)
			val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

			segmenter.process(image)
				.addOnSuccessListener { result ->
					// Guarded end-to-end: this callback (and everything it kicks off) runs on the
					// main thread, and must never crash the app if a frame or ML Kit result is
					// malformed — an uncaught exception here isn't caught by the try/catch below,
					// since that only covers this function's synchronous setup, not callbacks that
					// fire later.
					try {
						val subjects = result.subjects.take(2)

						// Requirement 3: If no subjects found, apply Palette on 10% center crop
						if (subjects.isEmpty()) {
							val cropWidth = (resizedBitmap.width * 0.1f).toInt().coerceAtLeast(1)
							val cropHeight = (resizedBitmap.height * 0.1f).toInt().coerceAtLeast(1)
							val startX = (resizedBitmap.width - cropWidth) / 2
							val startY = (resizedBitmap.height - cropHeight) / 2

							val centerCrop = Bitmap.createBitmap(resizedBitmap, startX, startY, cropWidth, cropHeight)

							// Get top 2 prominent colors
							val prominentColors = findProminentColorsPalette(centerCrop, k = 2)
							val colorNames = prominentColors.map { getColorNameFromRgb(it[0], it[1], it[2]) }.distinct()

							val finalResult = if (colorNames.isNotEmpty()) colorNames.joinToString(" and ") else "unknown colors"
							finishProcessing(context, finalResult, prefs, onResult)
							return@addOnSuccessListener
						}

						val subjectDescriptions = mutableListOf<String>()
						var subjectsProcessedCount = 0

						for (subject in subjects) {
							val subjectBitmap = subject.bitmap
							if (subjectBitmap == null) {
								subjectsProcessedCount++
								if (subjectsProcessedCount == subjects.size) finalizeOutput(context, subjectDescriptions, prefs, onResult)
								continue
							}

							// Object labeler to determine type
							val subImage = InputImage.fromBitmap(subjectBitmap, 0)
							labeler.process(subImage)
								.addOnSuccessListener { labels ->
									// Guarded: this callback runs on the main thread, and Palette/bitmap work
									// here must never crash the whole app if a frame is malformed.
									try {
										// Requirement 1: "Unknown" if no label found
										val labelName = labels.firstOrNull()?.text?.replaceFirstChar { it.uppercase() } ?: "Unknown"

										// Palette swatches are sorted by population descending, so the dominant color is first.
										val prominentColors = findProminentColorsPalette(subjectBitmap, k = 2)
										val colorNames = prominentColors.map { getColorNameFromRgb(it[0], it[1], it[2]) }.distinct()

										// Format text
										val colorString = if (colorNames.isNotEmpty()) colorNames.joinToString(" and ") else "unknown colors"
										subjectDescriptions.add("$labelName: $colorString")
									} catch (e: Exception) {
										Log.e("OfflineColorDetector", "Color analysis error", e)
									}

									subjectsProcessedCount++
									if (subjectsProcessedCount == subjects.size) finalizeOutput(context, subjectDescriptions, prefs, onResult)
								}
								.addOnFailureListener {
									subjectsProcessedCount++
									if (subjectsProcessedCount == subjects.size) finalizeOutput(context, subjectDescriptions, prefs, onResult)
								}
						}
					} catch (e: Exception) {
						Log.e("OfflineColorDetector", "Subject processing error", e)
						finishProcessing(context, "Could not analyze colors.", prefs, onResult)
					}
				}
				.addOnFailureListener {
					finishProcessing(context, "Failed to segment subjects.", prefs, onResult)
				}

		} catch (e: Exception) {
			Log.e("OfflineColorDetector", "Processing Error", e)
			finishProcessing(context, "Error analyzing colors.", prefs, onResult)
		}
	}

	private fun finalizeOutput(context: Context, descriptions: List<String>, prefs: PreferencesManager, onResult: ((String) -> Unit)?) {
		val finalResult = if (descriptions.isNotEmpty()) descriptions.joinToString(", ") else "Could not identify subjects."
		finishProcessing(context, finalResult, prefs, onResult)
	}

	private fun finishProcessing(context: Context, finalResult: String, prefs: PreferencesManager, onResult: ((String) -> Unit)?) {
		if (onResult != null) onResult(finalResult) else OfflineTranslator.translateAndSpeak(context, finalResult, prefs)
		isProcessing.set(false)
	}

	private fun resizeToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
		val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
		if (scale >= 1f) return bitmap
		return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
	}

	/**
	 * Finds the top [k] dominant colors using androidx.palette. Segmented subject bitmaps carry
	 * a transparent background (alpha=0), so we first sample only the opaque pixels and pack
	 * them into a compact bitmap — this keeps the background out of Palette's quantization
	 * regardless of how it treats partial alpha.
	 */
	private fun findProminentColorsPalette(bitmap: Bitmap, k: Int): List<IntArray> {
		val opaquePixels = mutableListOf<Int>()

		// Adjust step dynamically. If parsing the tiny 10% center crop, a step of 4 might skip too many pixels.
		val step = if (bitmap.width < 20 || bitmap.height < 20) 1 else 4

		for (x in 0 until bitmap.width step step) {
			for (y in 0 until bitmap.height step step) {
				val color = bitmap.getPixel(x, y)
				if (Color.alpha(color) > 0) {
					opaquePixels.add(color or (0xFF shl 24))
				}
			}
		}

		if (opaquePixels.isEmpty()) return listOf(intArrayOf(0, 0, 0))

		val side = kotlin.math.ceil(kotlin.math.sqrt(opaquePixels.size.toDouble())).toInt().coerceAtLeast(1)
		val packed = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
		for (i in opaquePixels.indices) {
			packed.setPixel(i % side, i / side, opaquePixels[i])
		}

		val palette = Palette.from(packed).maximumColorCount(24).generate()
		val topSwatches = palette.swatches.sortedByDescending { it.population }.take(k)

		return if (topSwatches.isNotEmpty()) {
			topSwatches.map { intArrayOf(Color.red(it.rgb), Color.green(it.rgb), Color.blue(it.rgb)) }
		} else {
			listOf(intArrayOf(0, 0, 0))
		}
	}
}