package com.appricodes.lens_whisper.utils

import android.graphics.Bitmap

object ImageScaling {
    /**
     * Scales [bitmap] so neither dimension exceeds [maxDim], preserving aspect ratio.
     * Never upscales — if the image is already within bounds it is returned unchanged.
     */
    fun scaleToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
        if (scale >= 1f) return bitmap
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
