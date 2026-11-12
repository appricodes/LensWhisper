package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * A relative depth map: [size] x [size] values, row-major, spanning the whole photo (stretched,
 * not letterboxed, so cell (x, y) covers normalized x/size..(x+1)/size). Values are MiDaS
 * *inverse* depth (disparity): larger = nearer to the camera. Only relative, never metric.
 * [quantStep] is the smallest step the model can output (0 for a float model) — the relief
 * filter uses it as a noise floor.
 */
class DepthMap(val values: FloatArray, val size: Int, val quantStep: Float)

/**
 * Runs the bundled Depth Anything V2 Small (Apache-2.0; see NOTICE) — Qualcomm AI Hub's float
 * TFLite export with weights quantized to int8 (activations stay float): 518x518 RGB input in 0..1
 * (ImageNet normalization is built into the graph), 518x518 relative disparity output, resampled
 * here to a [GRID]x[GRID] [DepthMap]. Plain CPU [Interpreter], same as
 * [OfflineSegmentationDetector]. Input/output quantization is read from the model's own tensors,
 * so a fully-quantized export would also work unchanged.
 *
 * Chosen over MiDaS v2.1 small after side-by-side tests: MiDaS small renders a face as a flat
 * blob, Depth Anything V2 Small resolves nose, eye sockets and cheeks.
 */
object OfflineDepthEstimator {

    private const val ASSET_PATH = "models/depth_anything_v2_small_wi8.tflite"
    /** Output grid side — fine enough for a fingertip (~4 screen px per cell), cheap to filter. */
    const val GRID = 256

    @Volatile private var interpreter: Interpreter? = null
    private val runLock = Any()

    private fun ensureInterpreter(context: Context): Interpreter {
        interpreter?.let { return it }
        synchronized(this) {
            interpreter?.let { return it }
            val model = context.assets.openFd(ASSET_PATH).use { fd ->
                FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
            }
            return Interpreter(model, Interpreter.Options().setNumThreads(4)).also { interpreter = it }
        }
    }

    /** Frees the interpreter (its float activations are large) — called when the relief screen
     *  closes; the next [estimate] simply reloads the memory-mapped model. */
    fun release() = synchronized(runLock) {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
        }
    }

    fun estimate(context: Context, bitmap: Bitmap): DepthMap = synchronized(runLock) {
        val interpreter = ensureInterpreter(context)
        val inTensor = interpreter.getInputTensor(0)
        // NHWC: [1, H, W, 3]
        val inH = inTensor.shape()[1]
        val inW = inTensor.shape()[2]
        val input = preprocess(bitmap, inW, inH, inTensor)

        val outTensor = interpreter.getOutputTensor(0)
        val outShape = outTensor.shape() // [1, H, W, 1] or [1, H, W]
        val outH = outShape[1]
        val outW = outShape[2]
        val output = ByteBuffer.allocateDirect(outTensor.dataType().byteSize() * outShape.fold(1) { a, d -> a * d })
            .order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()

        val raw = FloatArray(outW * outH)
        val scale = outTensor.quantizationParams().scale
        val zero = outTensor.quantizationParams().zeroPoint
        when (outTensor.dataType()) {
            DataType.FLOAT32 -> output.asFloatBuffer().get(raw)
            DataType.UINT8 -> for (i in raw.indices) raw[i] = scale * ((output.get(i).toInt() and 0xFF) - zero)
            DataType.INT8 -> for (i in raw.indices) raw[i] = scale * (output.get(i).toInt() - zero)
            else -> error("Unsupported depth output type ${outTensor.dataType()}")
        }
        val step = if (outTensor.dataType() == DataType.FLOAT32) 0f else scale
        DepthMap(resample(raw, outW, outH, GRID), GRID, step)
    }

    /** Bilinear resample of a [w]x[h] grid to [n]x[n] (both spanning the whole photo). */
    private fun resample(src: FloatArray, w: Int, h: Int, n: Int): FloatArray {
        if (w == n && h == n) return src
        val out = FloatArray(n * n)
        for (y in 0 until n) {
            val fy = ((y + 0.5f) * h / n - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, h - 1); val ty = fy - y0
            for (x in 0 until n) {
                val fx = ((x + 0.5f) * w / n - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, w - 1); val tx = fx - x0
                val a = src[y0 * w + x0] * (1 - tx) + src[y0 * w + x1] * tx
                val b = src[y1 * w + x0] * (1 - tx) + src[y1 * w + x1] * tx
                out[y * n + x] = a * (1 - ty) + b * ty
            }
        }
        return out
    }

    private fun preprocess(bitmap: Bitmap, w: Int, h: Int, tensor: Tensor): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        val type = tensor.dataType()
        val buf = ByteBuffer.allocateDirect(type.byteSize() * w * h * 3).order(ByteOrder.nativeOrder())
        val qScale = tensor.quantizationParams().scale
        val qZero = tensor.quantizationParams().zeroPoint
        fun put(v01: Float) {
            when (type) {
                DataType.FLOAT32 -> buf.putFloat(v01)
                DataType.UINT8 -> buf.put((v01 / qScale + qZero).roundToInt().coerceIn(0, 255).toByte())
                DataType.INT8 -> buf.put((v01 / qScale + qZero).roundToInt().coerceIn(-128, 127).toByte())
                else -> error("Unsupported depth input type $type")
            }
        }
        for (p in pixels) {
            put(((p shr 16) and 0xFF) / 255f)
            put(((p shr 8) and 0xFF) / 255f)
            put((p and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }
}
