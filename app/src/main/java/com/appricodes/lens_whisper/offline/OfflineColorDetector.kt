package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.appricodes.lens_whisper.data.PreferencesManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.math.sqrt

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

	fun detect(context: Context, bitmap: Bitmap, prefs: PreferencesManager) {
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
					val subjects = result.subjects.take(2)

					// Requirement 3: If no subjects found, apply K-Means on 10% center crop
					if (subjects.isEmpty()) {
						val cropWidth = (resizedBitmap.width * 0.1f).toInt().coerceAtLeast(1)
						val cropHeight = (resizedBitmap.height * 0.1f).toInt().coerceAtLeast(1)
						val startX = (resizedBitmap.width - cropWidth) / 2
						val startY = (resizedBitmap.height - cropHeight) / 2

						val centerCrop = Bitmap.createBitmap(resizedBitmap, startX, startY, cropWidth, cropHeight)

						// Get top 2 prominent colors
						val prominentColors = findProminentColorsKMeans(centerCrop, k = 2)
						val colorNames = prominentColors.map { getColorNameFromRgb(it[0], it[1], it[2]) }.distinct()

						val finalResult = if (colorNames.isNotEmpty()) colorNames.joinToString(" and ") else "unknown colors"
						finishProcessing(context, finalResult, prefs)
						return@addOnSuccessListener
					}

					val subjectDescriptions = mutableListOf<String>()
					var subjectsProcessedCount = 0

					for (subject in subjects) {
						val subjectBitmap = subject.bitmap
						if (subjectBitmap == null) {
							subjectsProcessedCount++
							if (subjectsProcessedCount == subjects.size) finalizeOutput(context, subjectDescriptions, prefs)
							continue
						}

						// Object labeler to determine type
						val subImage = InputImage.fromBitmap(subjectBitmap, 0)
						labeler.process(subImage)
							.addOnSuccessListener { labels ->
								// Requirement 1: "Unknown" if no label found
								val labelName = labels.firstOrNull()?.text?.replaceFirstChar { it.uppercase() } ?: "Unknown"

								// Requirement 2: K-Means already sorts by cluster size descending, ensuring dominant color is first.
								val prominentColors = findProminentColorsKMeans(subjectBitmap, k = 2)
								val colorNames = prominentColors.map { getColorNameFromRgb(it[0], it[1], it[2]) }.distinct()

								// Format text
								val colorString = if (colorNames.isNotEmpty()) colorNames.joinToString(" and ") else "unknown colors"
								subjectDescriptions.add("$labelName: $colorString")

								subjectsProcessedCount++
								if (subjectsProcessedCount == subjects.size) finalizeOutput(context, subjectDescriptions, prefs)
							}
							.addOnFailureListener {
								subjectsProcessedCount++
								if (subjectsProcessedCount == subjects.size) finalizeOutput(context, subjectDescriptions, prefs)
							}
					}
				}
				.addOnFailureListener {
					finishProcessing(context, "Failed to segment subjects.", prefs)
				}

		} catch (e: Exception) {
			Log.e("OfflineColorDetector", "Processing Error", e)
			finishProcessing(context, "Error analyzing colors.", prefs)
		}
	}

	private fun finalizeOutput(context: Context, descriptions: List<String>, prefs: PreferencesManager) {
		val finalResult = if (descriptions.isNotEmpty()) descriptions.joinToString(", ") else "Could not identify subjects."
		finishProcessing(context, finalResult, prefs)
	}

	private fun finishProcessing(context: Context, finalResult: String, prefs: PreferencesManager) {
		OfflineTranslator.translateAndSpeak(context, finalResult, prefs)
		isProcessing.set(false)
	}

	private fun resizeToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
		val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
		if (scale >= 1f) return bitmap
		return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
	}

	private fun findProminentColorsKMeans(bitmap: Bitmap, k: Int): List<IntArray> {
		val pixels = mutableListOf<IntArray>()

		// Adjust step dynamically. If parsing the tiny 10% center crop, a step of 4 might skip too many pixels.
		val step = if (bitmap.width < 20 || bitmap.height < 20) 1 else 4

		for (x in 0 until bitmap.width step step) {
			for (y in 0 until bitmap.height step step) {
				val color = bitmap.getPixel(x, y)
				// Ignore background points (Alpha > 0 for both Subject segmented maps and normal center crops)
				if (Color.alpha(color) > 0) {
					pixels.add(intArrayOf(Color.red(color), Color.green(color), Color.blue(color)))
				}
			}
		}

		if (pixels.isEmpty()) return listOf(intArrayOf(0, 0, 0))
		if (pixels.size <= k) return pixels

		var centroids = pixels.shuffled().take(k)
		var hasChanged = true
		var iterations = 0
		val maxIterations = 10
		val clusters = Array(k) { mutableListOf<IntArray>() }

		while (hasChanged && iterations < maxIterations) {
			hasChanged = false
			clusters.forEach { it.clear() }

			for (pixel in pixels) {
				var minDistance = Float.MAX_VALUE
				var bestIndex = 0
				for (i in 0 until k) {
					val dist = colorDistance(pixel, centroids[i])
					if (dist < minDistance) {
						minDistance = dist
						bestIndex = i
					}
				}
				clusters[bestIndex].add(pixel)
			}

			val newCentroids = mutableListOf<IntArray>()
			for (i in 0 until k) {
				if (clusters[i].isNotEmpty()) {
					var r = 0L; var g = 0L; var b = 0L
					for (p in clusters[i]) {
						r += p[0]; g += p[1]; b += p[2]
					}
					val count = clusters[i].size
					val newC = intArrayOf((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
					newCentroids.add(newC)
					if (colorDistance(newC, centroids[i]) > 1.0f) hasChanged = true
				} else {
					newCentroids.add(centroids[i])
				}
			}
			centroids = newCentroids
			iterations++
		}

		// Requirement 2: Ensure the most dominant color is first by explicitly sorting clusters by size descending
		return centroids.indices.sortedByDescending { clusters[it].size }.map { centroids[it] }.take(k)
	}

	private fun colorDistance(c1: IntArray, c2: IntArray): Float {
		return sqrt((c1[0] - c2[0]).toFloat().pow(2) + (c1[1] - c2[1]).toFloat().pow(2) + (c1[2] - c2[2]).toFloat().pow(2))
	}
}