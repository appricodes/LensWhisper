package com.appricodes.lens_whisper.offline

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette
import kotlin.math.pow

object OfflineColorDetector {
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
	 * The same Palette-based ranking used internally by the (now-removed) ML Kit subject-detection
	 * path this object used to expose, kept for a caller that has already isolated the region to
	 * name colors for — e.g. [com.appricodes.lens_whisper.offline.OfflineSegmentationDetector],
	 * which gets both an object's identity and its exact pixel mask from a single detector pass.
	 * [bitmap] should have a transparent background outside the region of interest, matching what
	 * [findProminentColorsPalette] already expects (see its doc) — a masked instance crop with
	 * non-member pixels set to alpha=0 satisfies this exactly.
	 */
	fun namedDominantColors(bitmap: Bitmap, k: Int = 2): List<String> {
		val prominentColors = findProminentColorsPalette(bitmap, k)
		return prominentColors.map { getColorNameFromRgb(it[0], it[1], it[2]) }.distinct()
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
