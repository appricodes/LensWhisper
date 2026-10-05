package com.appricodes.lens_whisper.offline

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Names the main colours of a region — for Simple Color Detection, Object and Color Detection,
 * Explore's object descriptions and its Color Report.
 *
 * Works in HSV rather than RGB: brighter or dimmer light mostly changes a surface's value (V),
 * barely its hue (H), so each pixel is sorted into a colour family by hue first, and brightness
 * only decides black/white/grey, brown, and a "Dark"/"Light" qualifier. (The RGB nearest-match
 * this replaces called a red shirt "Dark Brown" in a dim room and "Light Red" by a window.)
 * The families are then ranked by how many pixels fall in each, and each is named from its own
 * pixels' average saturation and value.
 */
object OfflineColorDetector {

	private enum class Family(val baseName: String) {
		BLACK("Black"), GRAY("Gray"), RED("Red"), ORANGE("Orange"), BROWN("Brown"), YELLOW("Yellow"),
		GREEN("Green"), CYAN("Cyan"), BLUE("Blue"), PURPLE("Purple"), MAGENTA("Magenta"), PINK("Pink")
	}

	private class Tally {
		var count = 0
		var saturation = 0f
		var value = 0f
	}

	/** Below this, a pixel is black whatever its hue: too dark for the hue to mean anything. */
	private const val BLACK_VALUE = 0.12f
	/** Below this saturation, a pixel is white/grey: its hue is noise. */
	private const val GRAY_SATURATION = 0.18f
	/** A second (third…) colour is only named if at least this share of the region has it. */
	private const val MIN_SHARE = 0.10f

	/**
	 * The [k] most common colours in [bitmap], most common first. Fully transparent pixels are
	 * ignored, so a masked crop (an object with its background cleared to alpha 0) names only the
	 * object.
	 */
	fun namedDominantColors(bitmap: Bitmap, k: Int = 2): List<String> {
		val tallies = HashMap<Family, Tally>()
		val hsv = FloatArray(3)
		var total = 0
		// A small crop (Simple Color's centre patch, Explore's finger-sized patch) is read whole.
		val step = if (bitmap.width < 20 || bitmap.height < 20) 1 else 4
		for (x in 0 until bitmap.width step step) {
			for (y in 0 until bitmap.height step step) {
				val pixel = bitmap.getPixel(x, y)
				if (Color.alpha(pixel) == 0) continue
				Color.colorToHSV(pixel, hsv)
				val tally = tallies.getOrPut(familyOf(hsv[0], hsv[1], hsv[2])) { Tally() }
				tally.count++
				tally.saturation += hsv[1]
				tally.value += hsv[2]
				total++
			}
		}
		if (total == 0) return listOf("Black")

		val ranked = tallies.entries.sortedByDescending { it.value.count }
		return ranked
			.filterIndexed { index, entry -> index == 0 || entry.value.count >= total * MIN_SHARE }
			.take(k)
			.map { (family, tally) -> nameOf(family, tally.saturation / tally.count, tally.value / tally.count) }
			.distinct()
	}

	private fun familyOf(hue: Float, saturation: Float, value: Float): Family {
		if (value < BLACK_VALUE) return Family.BLACK
		if (saturation < GRAY_SATURATION) return Family.GRAY
		// Brown is dark (or greyish, "tan") orange-to-yellow; dark red stays red.
		if (hue >= 15f && hue < 50f && (value < 0.55f || (saturation < 0.45f && value < 0.92f))) return Family.BROWN
		return when {
			hue < 15f || hue >= 345f -> Family.RED
			hue < 40f -> Family.ORANGE
			hue < 70f -> Family.YELLOW
			hue < 165f -> Family.GREEN
			hue < 195f -> Family.CYAN
			hue < 255f -> Family.BLUE
			hue < 290f -> Family.PURPLE
			hue < 330f -> Family.MAGENTA
			else -> Family.PINK
		}
	}

	private fun nameOf(family: Family, saturation: Float, value: Float): String = when (family) {
		Family.BLACK -> "Black"
		Family.GRAY -> when {
			value >= 0.80f -> "White"
			value >= 0.60f -> "Light Gray"
			value >= 0.35f -> "Gray"
			else -> "Dark Gray"
		}
		Family.BROWN -> when {
			value < 0.30f -> "Dark Brown"
			saturation < 0.45f && value >= 0.55f -> "Light Brown"
			else -> "Brown"
		}
		else -> when {
			value < 0.30f -> "Dark ${family.baseName}"
			// Pale (low saturation, bright): pastel shades. Pale red is what people call pink.
			saturation < 0.45f && value >= 0.70f -> when (family) {
				Family.RED -> "Pink"
				else -> "Light ${family.baseName}"
			}
			else -> family.baseName
		}
	}
}
