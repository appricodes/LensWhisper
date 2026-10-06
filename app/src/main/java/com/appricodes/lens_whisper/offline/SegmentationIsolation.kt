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
/**
 * What a "Describe TV Image"/"Explore and Describe" capture hands back, from either source: a
 * downscaled copy (for segmentation and, in [com.appricodes.lens_whisper.SegmentationExploreActivity]'s
 * describe-mode, for display) plus the *file* the same image was written to at full detail — a
 * File, not a decoded Bitmap, so nothing holds a large full decode in memory a moment longer than
 * it takes to produce [lowResBitmap]; see [SegmentationIsolation]'s doc for why.
 *
 * Shared between [com.appricodes.lens_whisper.CameraActivity] (where the pair comes from one live
 * camera capture at max sensor resolution) and `MediaDescriberScreen` (where it comes from an
 * already-loaded image file or an extracted video frame instead — there is no separate "sensor" to
 * ask for extra detail from, so both roles are derived from the exact same source bitmap there).
 */
data class DualResCapture(val lowResBitmap: Bitmap, val highResFile: File)

object SegmentationIsolation {

    /**
     * [instance] was detected against [lowResBitmap] (the same photo, downscaled — see this
     * object's doc: [lowResBitmap] MUST be derived from the exact same capture as [highResFile] via
     * [com.appricodes.lens_whisper.utils.ImageScaling.scaleToMax], never a separately captured
     * frame, or normalized mask coordinates silently stop lining up between the two). The mask is
     * grown by [dilatePixels] (measured in [lowResBitmap]'s own pixels) before cropping, so the
     * mask grid's coarse (138x138) edge doesn't clip a sliver of the real object — then the
     * smallest rectangle containing the grown mask is decoded from [highResFile] at full detail,
     * with every pixel outside the mask painted opaque white. Returns null if the file can't be
     * decoded (corrupt/missing cache file) rather than throwing, so a caller can fall back to a
     * plain "could not isolate that object" announcement.
     *
     * [rotationDegrees] extends that same "same capture" contract: [highResFile]'s own bytes are
     * never rotated (re-encoding a full-sensor capture just to rotate it would defeat the whole
     * point of decoding only [instance]'s own region below), but [lowResBitmap] — and so
     * [instance]'s coordinates, detected against it — may have been rotated a quarter turn after
     * capture to better fit a display (see
     * [com.appricodes.lens_whisper.SegmentationExploreActivity]'s `rotateForBestFit`). Pass exactly
     * that rotation here (clockwise, one of 0/90/180/270) or this call silently isolates the wrong
     * region of the file whenever it isn't 0.
     */
    fun isolate(lowResBitmap: Bitmap, instance: DetectedInstance, highResFile: File, dilatePixels: Int = 2, rotationDegrees: Int = 0): Bitmap? {
        val lw = lowResBitmap.width
        val lh = lowResBitmap.height

        // The instance's own box, padded by the dilation radius and clamped to the frame — nothing
        // outside this region can ever be part of the mask (buildInstance's crop_mask step already
        // confines the mask to the box), so restricting all the work below to this region is exact,
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
            // left/top/right/bottom above are in lowResBitmap's own (possibly rotated) space — map
            // the box's two diagonal corners back to highResFile's native (never-rotated) normalized
            // space via the inverse rotation before locating the region to crop from it. Both corners
            // go through the same transform and are then re-sorted into left<right/top<bottom, since
            // which corner ends up "top-left" changes with the rotation.
            val (nx1, ny1) = rotateNormalizedPoint(left.toFloat() / lw, top.toFloat() / lh, -rotationDegrees)
            val (nx2, ny2) = rotateNormalizedPoint(right.toFloat() / lw, bottom.toFloat() / lh, -rotationDegrees)
            val hLeft = (minOf(nx1, nx2) * hw).toInt().coerceIn(0, hw - 1)
            val hTop = (minOf(ny1, ny2) * hh).toInt().coerceIn(0, hh - 1)
            val hRight = (maxOf(nx1, nx2) * hw).toInt().coerceIn(hLeft + 1, hw)
            val hBottom = (maxOf(ny1, ny2) * hh).toInt().coerceIn(hTop + 1, hh)

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
                for (x in 0 until outW) {
                    // This output pixel's normalized position in the *whole file* (not just this
                    // cropped region), then forward-rotated into the mask's own (rotated) space —
                    // the inverse of the box mapping above. Reduces to the original plain
                    // proportional scaling when rotationDegrees is 0.
                    val fnx = (hLeft + x + 0.5f) / hw
                    val fny = (hTop + y + 0.5f) / hh
                    val (rnx, rny) = rotateNormalizedPoint(fnx, fny, rotationDegrees)
                    val rx = (rnx * lw).toInt() - left
                    val ry = (rny * lh).toInt() - top
                    val inMask = rx in 0 until rw && ry in 0 until rh && mask[ry * rw + rx]
                    if (!inMask) pixels[y * outW + x] = WHITE_OPAQUE
                }
            }
            out.setPixels(pixels, 0, outW, 0, 0, outW, outH)
            return out
        } finally {
            decoder.recycle()
        }
    }

    private const val WHITE_OPAQUE = 0xFFFFFFFF.toInt()

    /**
     * Rotates a normalized (0..1) point clockwise by [clockwiseDegrees] (any multiple of 90,
     * including negative — normalized mod 360) within a unit box whose own width/height swap at 90°
     * and 270°, matching what actually happens to pixel coordinates when a bitmap is rotated via
     * `Matrix.postRotate` (see [com.appricodes.lens_whisper.SegmentationExploreActivity]'s
     * `rotateForBestFit`, which produces the rotation this corrects for). Passing the negated angle
     * gives the inverse transform — rotating a point by `-d` undoes a rotation by `d`, which is all
     * [isolate] needs both directions of (rotated lowRes space -> native file space for locating the
     * crop, then native space -> rotated space again per output pixel to test it against the mask).
     */
    private fun rotateNormalizedPoint(x: Float, y: Float, clockwiseDegrees: Int): Pair<Float, Float> =
        when (((clockwiseDegrees % 360) + 360) % 360) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }

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
