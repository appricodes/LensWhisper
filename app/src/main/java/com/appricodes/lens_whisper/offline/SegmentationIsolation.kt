package com.appricodes.lens_whisper.offline

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import java.io.File
import java.io.FileInputStream

/**
 * Isolates one [DetectedInstance] onto a white background at the *original*, full-sensor-resolution
 * detail — shared by "Describe TV Image" and "Explore and Describe" (see
 * [com.appricodes.lens_whisper.CameraActivity]/[com.appricodes.lens_whisper.SegmentationExploreActivity]),
 * both of which need the same "crop just this object out of the rest of the photo, in full detail"
 * step before handing the result to the on-device VLM.
 *
 * **Why a separate high-res file instead of just cropping the bitmap detection already ran on:**
 * detection itself runs on a downscaled 720p copy (fast, and every other segmentation-driven
 * feature in this app already works that way) — but a small object that only fills a corner of a
 * 720p frame yields a blurry crop. Isolating from the original, much larger capture instead keeps
 * whatever detail the sensor actually captured, up to whatever
 * [com.appricodes.lens_whisper.utils.OfflineImageSize.maxDimensionFor] ultimately downscales the
 * final isolated crop to before it reaches the model.
 *
 * **Why [BitmapRegionDecoder] instead of just decoding the whole high-res file:** a full-sensor
 * capture decoded as ARGB_8888 is tens of megabytes (e.g. ~35MB for a 4032x2268 frame) — trivial to
 * decode once, but not something this app holds onto for a whole Explore session (mask hover +
 * repeated taps) alongside a 1-3GB on-device VLM engine on a 4GB device. Decoding only the
 * instance's own bounding region keeps the peak memory cost to that one crop, not the whole photo.
 */
object SegmentationIsolation {

    /**
     * [instance] was detected against [lowResBitmap] (the same photo, downscaled — see this
     * object's doc: [lowResBitmap] MUST be derived from the exact same capture as [highResFile] via
     * [com.appricodes.lens_whisper.utils.ImageScaling.scaleToMax], never a separately captured
     * frame, or normalized mask coordinates silently stop lining up between the two). The mask is
     * grown by [dilatePixels] (measured in [lowResBitmap]'s own pixels) before cropping, so the
     * proto-mask's coarse (160x160-grid) edge doesn't clip a sliver of the real object — then the
     * smallest rectangle containing the grown mask is decoded from [highResFile] at full detail,
     * with every pixel outside the mask painted opaque white. Returns null if the file can't be
     * decoded (corrupt/missing cache file) rather than throwing, so a caller can fall back to a
     * plain "could not isolate that object" announcement.
     */
    fun isolate(lowResBitmap: Bitmap, instance: DetectedInstance, highResFile: File, dilatePixels: Int = 2): Bitmap? {
        val lw = lowResBitmap.width
        val lh = lowResBitmap.height

        // The instance's own box, padded by the dilation radius and clamped to the frame — nothing
        // outside this region can ever be part of the mask (buildInstance's crop_mask step already
        // confines protoMask to the box), so restricting all the work below to this region is exact,
        // not an approximation.
        val left = ((instance.boxLeft * lw).toInt() - dilatePixels).coerceIn(0, lw - 1)
        val top = ((instance.boxTop * lh).toInt() - dilatePixels).coerceIn(0, lh - 1)
        val right = ((instance.boxRight * lw).toInt() + dilatePixels + 1).coerceIn(left + 1, lw)
        val bottom = ((instance.boxBottom * lh).toInt() + dilatePixels + 1).coerceIn(top + 1, lh)
        val rw = right - left
        val rh = bottom - top

        val rawMask = BooleanArray(rw * rh) { idx ->
            val rx = idx % rw
            val ry = idx / rw
            instance.containsNormalizedPoint((left + rx + 0.5f) / lw, (top + ry + 0.5f) / lh)
        }
        // Naive square-kernel dilation — dilatePixels is tiny (2) and this only ever runs over one
        // instance's own bounding region, not the whole frame, so the O(radius^2) cost per pixel is
        // negligible; not worth a separable two-pass version for numbers this small.
        val mask = BooleanArray(rw * rh) { idx ->
            if (rawMask[idx]) return@BooleanArray true
            val rx = idx % rw
            val ry = idx / rw
            var found = false
            var dy = -dilatePixels
            while (dy <= dilatePixels && !found) {
                val ny = ry + dy
                if (ny in 0 until rh) {
                    var dx = -dilatePixels
                    while (dx <= dilatePixels && !found) {
                        val nx = rx + dx
                        if (nx in 0 until rw && rawMask[ny * rw + nx]) found = true
                        dx++
                    }
                }
                dy++
            }
            found
        }

        val decoder = openRegionDecoder(highResFile) ?: return null
        try {
            val hw = decoder.width
            val hh = decoder.height
            val hLeft = (left.toFloat() / lw * hw).toInt().coerceIn(0, hw - 1)
            val hTop = (top.toFloat() / lh * hh).toInt().coerceIn(0, hh - 1)
            val hRight = (right.toFloat() / lw * hw).toInt().coerceIn(hLeft + 1, hw)
            val hBottom = (bottom.toFloat() / lh * hh).toInt().coerceIn(hTop + 1, hh)

            val region = try {
                decoder.decodeRegion(Rect(hLeft, hTop, hRight, hBottom), null)
            } catch (e: Exception) {
                null
            } ?: return null

            val out = if (region.isMutable && region.config == Bitmap.Config.ARGB_8888) region else {
                region.copy(Bitmap.Config.ARGB_8888, true).also { region.recycle() }
            }
            val outW = out.width
            val outH = out.height
            val pixels = IntArray(outW * outH)
            out.getPixels(pixels, 0, outW, 0, 0, outW, outH)
            for (y in 0 until outH) {
                val ry = (y.toFloat() / outH * rh).toInt().coerceIn(0, rh - 1)
                for (x in 0 until outW) {
                    val rx = (x.toFloat() / outW * rw).toInt().coerceIn(0, rw - 1)
                    if (!mask[ry * rw + rx]) pixels[y * outW + x] = WHITE_OPAQUE
                }
            }
            out.setPixels(pixels, 0, outW, 0, 0, outW, outH)
            return out
        } finally {
            decoder.recycle()
        }
    }

    private const val WHITE_OPAQUE = 0xFFFFFFFF.toInt()

    private fun openRegionDecoder(file: File): BitmapRegionDecoder? = try {
        FileInputStream(file).use { stream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }
        }
    } catch (e: Exception) {
        null
    }
}
