package com.appricodes.lens_whisper.offline

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A local relief map: [size] x [size] values in -1..1, row-major, spanning the whole photo.
 * +1 = this point sticks out from its surroundings (a nose tip), -1 = it sits in a hollow (an eye
 * socket), 0 = level with what's around it. Deliberately *not* global depth: a wall far behind a
 * person is not "lower" than the person, only its own dents and bumps count.
 */
class ReliefMap(val values: FloatArray, val size: Int) {

    /** Bilinear sample at normalized ([nx], [ny]) in 0..1. */
    fun sample(nx: Float, ny: Float): Float {
        val fx = (nx * size - 0.5f).coerceIn(0f, (size - 1).toFloat())
        val fy = (ny * size - 0.5f).coerceIn(0f, (size - 1).toFloat())
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = min(x0 + 1, size - 1)
        val y1 = min(y0 + 1, size - 1)
        val tx = fx - x0
        val ty = fy - y0
        val a = values[y0 * size + x0] * (1 - tx) + values[y0 * size + x1] * tx
        val b = values[y1 * size + x0] * (1 - tx) + values[y1 * size + x1] * tx
        return a * (1 - ty) + b * ty
    }

    /** The same map turned a quarter turn clockwise — matching `Bitmap` rotated by +90°. */
    fun rotatedClockwise(): ReliefMap {
        val n = size
        val out = FloatArray(n * n)
        // Rotating CW moves source (x, y) to (n-1-y, x), so dest (x', y') reads source (y', n-1-x').
        for (y in 0 until n) for (x in 0 until n) out[y * n + x] = values[(n - 1 - x) * n + y]
        return ReliefMap(out, n)
    }

    /** Grayscale picture of the map (white = raised, black = recessed) — for checking by eye. */
    fun toBitmap(): Bitmap {
        val px = IntArray(size * size) {
            val g = ((values[it] + 1f) * 127.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Turns a [DepthMap] into a [ReliefMap]: each point is compared only with its own neighbourhood
 * *on the same surface*.
 *
 * 1. A light pre-blur removes model noise/quantization steps.
 * 2. Two band-pass layers: depth minus an *edge-aware* local mean of itself, at a fine radius
 *    (~2.5% of the image — facial features) and a coarser one (~7% — a face against its own head,
 *    a cup handle against the cup). The local mean is a bilateral filter (piecewise-linear
 *    approximation over [LEVELS] depth levels, Durand & Dorsey 2002): neighbours at a very
 *    different depth — the wall behind a person — barely count, so object silhouettes don't
 *    turn into broad raised/sunken halos and only real bumps and hollows remain.
 * 3. Each layer is divided by its own local RMS, so a gentle bump on a flat table registers as
 *    clearly as a nose on a face — contrast is per region, not per image. A noise floor stops a
 *    featureless wall's noise from being magnified into buzzing.
 * 4. A tanh squash keeps everything inside -1..1 without hard clipping.
 *
 * Tuned on Depth Anything V2 Small output: on a face, nose tip clearly positive, eye sockets
 * and mouth corners negative, cheeks mildly positive.
 */
object LocalRelief {

    private const val LEVELS = 16
    /** Bilateral range sigma, as a fraction of the image's robust depth range. */
    private const val RANGE_SIGMA = 0.06f
    private const val NOISE_FLOOR = 0.02f

    fun compute(depth: DepthMap): ReliefMap {
        val n = depth.size
        val d = smallGaussian(depth.values, n, 0.7f)

        val sorted = d.copyOf().also { it.sort() }
        val lo = sorted.first()
        val hi = sorted.last()
        val range = (sorted[(sorted.size * 0.98f).toInt().coerceAtMost(sorted.size - 1)] - sorted[(sorted.size * 0.02f).toInt()])
            .coerceAtLeast(1e-6f)
        val floor = max(NOISE_FLOOR * range, 1.5f * depth.quantStep)
        val sr = RANGE_SIGMA * range

        val fine = normalizedBand(d, n, sigma = n * 0.025f, sr = sr, lo = lo, hi = hi, floor = floor)
        val coarse = normalizedBand(d, n, sigma = n * 0.07f, sr = sr, lo = lo, hi = hi, floor = floor)
        val out = FloatArray(n * n) { tanh(0.8f * (0.55f * fine[it] + 0.45f * coarse[it])) }
        return ReliefMap(out, n)
    }

    private fun normalizedBand(d: FloatArray, n: Int, sigma: Float, sr: Float, lo: Float, hi: Float, floor: Float): FloatArray {
        val mean = bilateral(d, n, sigma, sr, lo, hi)
        val hp = FloatArray(d.size) { d[it] - mean[it] }
        val energy = boxGaussian(FloatArray(hp.size) { hp[it] * hp[it] }, n, sigma * 1.6f)
        return FloatArray(hp.size) { hp[it] / (sqrt(energy[it]) + floor) }
    }

    /** Piecewise-linear bilateral filter: a range-weighted spatial Gaussian evaluated at [LEVELS]
     *  fixed depth levels, then each pixel interpolates between the two levels around its own depth. */
    private fun bilateral(d: FloatArray, n: Int, sigma: Float, sr: Float, lo: Float, hi: Float): FloatArray {
        val span = (hi - lo).coerceAtLeast(1e-6f)
        val inv2sr2 = 1f / (2f * sr * sr)
        val results = Array(LEVELS) { k ->
            val level = lo + span * k / (LEVELS - 1)
            val w = FloatArray(d.size) { val t = d[it] - level; exp(-t * t * inv2sr2) }
            val wd = FloatArray(d.size) { w[it] * d[it] }
            val bw = boxGaussian(w, n, sigma)
            val bwd = boxGaussian(wd, n, sigma)
            FloatArray(d.size) { bwd[it] / (bw[it] + 1e-12f) }
        }
        return FloatArray(d.size) { i ->
            val pos = (d[i] - lo) / span * (LEVELS - 1)
            val k0 = floor(pos).toInt().coerceIn(0, LEVELS - 2)
            val f = (pos - k0).coerceIn(0f, 1f)
            results[k0][i] * (1 - f) + results[k0 + 1][i] * f
        }
    }

    /** Gaussian approximated by three box blurs (running sums — cost independent of [sigma]). */
    private fun boxGaussian(src: FloatArray, n: Int, sigma: Float): FloatArray {
        // Box width giving the right variance for 3 passes: w = sqrt(12σ²/3 + 1).
        val r = max(1, ((sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).toInt())
        var a = src
        repeat(3) { a = boxBlur(a, n, r) }
        return a
    }

    private fun boxBlur(src: FloatArray, n: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val norm = 1f / (2 * r + 1)
        for (y in 0 until n) {
            val row = y * n
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, n - 1)]
            for (x in 0 until n) {
                tmp[row + x] = acc * norm
                acc += src[row + min(x + r + 1, n - 1)] - src[row + max(x - r, 0)]
            }
        }
        for (x in 0 until n) {
            var acc = 0f
            for (k in -r..r) acc += tmp[k.coerceIn(0, n - 1) * n + x]
            for (y in 0 until n) {
                out[y * n + x] = acc * norm
                acc += tmp[min(y + r + 1, n - 1) * n + x] - tmp[max(y - r, 0) * n + x]
            }
        }
        return out
    }

    /** Direct separable Gaussian for small sigmas, where three boxes would be too coarse. */
    private fun smallGaussian(src: FloatArray, n: Int, sigma: Float): FloatArray {
        val radius = max(1, ceil(sigma * 3f).toInt())
        val kernel = FloatArray(2 * radius + 1) { i ->
            val x = (i - radius).toFloat()
            exp(-(x * x) / (2f * sigma * sigma))
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        val tmp = FloatArray(src.size)
        for (y in 0 until n) for (x in 0 until n) {
            var acc = 0f
            for (k in -radius..radius) acc += src[y * n + (x + k).coerceIn(0, n - 1)] * kernel[k + radius]
            tmp[y * n + x] = acc
        }
        val out = FloatArray(src.size)
        for (y in 0 until n) for (x in 0 until n) {
            var acc = 0f
            for (k in -radius..radius) acc += tmp[(y + k).coerceIn(0, n - 1) * n + x] * kernel[k + radius]
            out[y * n + x] = acc
        }
        return out
    }
}
