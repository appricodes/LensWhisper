package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One detected object: its class name, confidence, bounding box (normalized 0..1 in the original
 * photo's coordinate space), and its pixel mask — queryable via [containsNormalizedPoint] ("is this
 * point part of it") and drawable via [maskBoundaryEdgesNormalized], both working directly off the
 * compact [maskWidth] x [maskHeight] grid [mask], which spans the *whole* original photo (row-major;
 * cell (x, y) covers normalized x/maskWidth..(x+1)/maskWidth, and the same for y).
 *
 * The grid is source-independent on purpose: the bundled YOLACT model produces it directly (its
 * input is a plain squash-resize, so its proto grid already spans the full photo), and any other
 * segmentation source only has to resample its own masks into the same shape.
 */
class DetectedInstance(
    val label: String,
    val confidence: Float,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    private val mask: BooleanArray,
    private val maskWidth: Int,
    private val maskHeight: Int
) {
    init {
        require(mask.size == maskWidth * maskHeight) { "mask size ${mask.size} != $maskWidth x $maskHeight" }
    }

    private fun isInside(x: Int, y: Int): Boolean {
        if (x < 0 || x >= maskWidth || y < 0 || y >= maskHeight) return false
        return mask[y * maskWidth + x]
    }

    /** True if the point at normalized original-image coordinates ([nx], [ny], each 0..1) falls
     *  inside this instance's actual mask — not just its bounding box. */
    fun containsNormalizedPoint(nx: Float, ny: Float): Boolean =
        isInside(floor(nx * maskWidth).toInt(), floor(ny * maskHeight).toInt())

    /**
     * The mask's exact outline, in original-image normalized coordinates — every grid-aligned edge
     * where an in-mask cell borders an out-of-mask (or off-grid) neighbor. Not a smoothed contour
     * and not necessarily one connected loop per blob (a mask with a hole, or two separate regions
     * sharing one label, produces more than one) — just the complete set of boundary segments, which
     * rendered together trace the real shape at the mask grid's own resolution. Computed on demand
     * rather than at detection time — `SegmentationExploreActivity` memoizes it itself.
     */
    fun maskBoundaryEdgesNormalized(): List<Pair<Pair<Float, Float>, Pair<Float, Float>>> {
        fun corner(x: Int, y: Int) = x.toFloat() / maskWidth to y.toFloat() / maskHeight
        val edges = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                if (!isInside(x, y)) continue
                if (!isInside(x, y - 1)) edges.add(corner(x, y) to corner(x + 1, y)) // top
                if (!isInside(x, y + 1)) edges.add(corner(x, y + 1) to corner(x + 1, y + 1)) // bottom
                if (!isInside(x - 1, y)) edges.add(corner(x, y) to corner(x, y + 1)) // left
                if (!isInside(x + 1, y)) edges.add(corner(x + 1, y) to corner(x + 1, y + 1)) // right
            }
        }
        return edges
    }

    /**
     * Crops [sourceBitmap] (the same, full photo the detector ran on) to this instance's mask —
     * pixels outside the mask are made fully transparent — and names its top [k] dominant colors
     * via [OfflineColorDetector.namedDominantColors], which already expects exactly that
     * "transparent outside the region of interest" convention.
     */
    fun namedDominantColors(sourceBitmap: Bitmap, k: Int = 2): List<String> {
        val left = (boxLeft * sourceBitmap.width).toInt().coerceIn(0, sourceBitmap.width - 1)
        val top = (boxTop * sourceBitmap.height).toInt().coerceIn(0, sourceBitmap.height - 1)
        val right = (boxRight * sourceBitmap.width).toInt().coerceIn(left + 1, sourceBitmap.width)
        val bottom = (boxBottom * sourceBitmap.height).toInt().coerceIn(top + 1, sourceBitmap.height)
        val cropWidth = right - left
        val cropHeight = bottom - top

        val masked = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        val sourcePixels = IntArray(cropWidth * cropHeight)
        sourceBitmap.getPixels(sourcePixels, 0, cropWidth, left, top, cropWidth, cropHeight)
        for (y in 0 until cropHeight) {
            val ny = (top + y).toFloat() / sourceBitmap.height
            for (x in 0 until cropWidth) {
                val nx = (left + x).toFloat() / sourceBitmap.width
                val idx = y * cropWidth + x
                sourcePixels[idx] = if (containsNormalizedPoint(nx, ny)) sourcePixels[idx] else 0
            }
        }
        masked.setPixels(sourcePixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
        return OfflineColorDetector.namedDominantColors(masked, k)
    }
}

/**
 * Runs the bundled YOLACT model (ResNet101-FPN backbone, 550x550 input, COCO's 80 classes, MIT
 * licence — see [ASSET_PATH]) and decodes its raw output into [DetectedInstance]s: name,
 * confidence, box and mask, capped to the top [MAX_DETECTIONS] by confidence.
 *
 * The `com.google.ai.edge.litert:litert` artifact still ships its classes under the classic
 * `org.tensorflow.lite.*` package.
 *
 * ## The model file
 * `yolact_resnet101_550_int8.tflite` is PINTO0309's PINTO_model_zoo export (folder `074_Yolact`) of
 * Ma-Dan/yolact, an ONNX-exportable fork of dbolya/yolact. Fully int8, including the boundary
 * tensors, so every read/write goes through the tensor's own quantization parameters. Tensors:
 * - input `[1, 550, 550, 3]` NHWC, RGB scaled to 0..1. The ImageNet mean/std normalization is baked
 *   into the graph (feeding pre-normalized values clips and loses most detections), and BGR input
 *   measurably loses recall on COCO photos, so it really is RGB.
 * - loc `[1, 19248, 4]`: raw SSD-style offsets against [priors].
 * - conf `[1, 19248, 81]`: already softmaxed; class 0 is background.
 * - mask coefficients `[1, 19248, 32]`: already tanh'd.
 * - proto `[1, 138, 138, 32]`: the prototype masks, NHWC.
 * Outputs are told apart by shape, not index, since the converter doesn't guarantee ordering.
 *
 * ## Decode math
 * Mirrors dbolya/yolact: `layers/box_utils.py` `decode` (variances 0.1/0.2), `data/config.py`
 * `yolact_base_config` priors (see [priors]), and `layers/output_utils.py`'s mask assembly
 * (`sigmoid(proto @ coeffs)`, cropped to the box, thresholded at 0.5). Input is a plain
 * squash-resize to 550x550 (YOLACT's `preserve_aspect_ratio` is off), so a normalized coordinate in
 * the model's input is the same normalized coordinate in the original photo — no letterbox to undo.
 * Checked against a numpy port of this same decode on COCO photos (bus 0.93, umbrella 0.99, ...);
 * on coco128 at the 0.20 floor about 92% of detections name a class that really is in the photo.
 */
object OfflineSegmentationDetector {

    private const val ASSET_PATH = "models/yolact_resnet101_550_int8.tflite"

    private const val MODEL_INPUT_SIZE = 550
    private const val NUM_CLASSES = 80 // excluding YOLACT's background class 0
    private const val NUM_MASK_COEFFS = 32

    /** Below this, a detection is discarded before NMS even runs — reads
     *  [DetectionPhrasing.MINIMUM_CONFIDENCE] so the two stay in sync. */
    private const val CONFIDENCE_THRESHOLD = DetectionPhrasing.MINIMUM_CONFIDENCE

    /** Per-class NMS IoU cutoff. Lower than YOLACT's own 0.5 default: at 0.5 the same object kept
     *  coming back as two overlapping instances, which a screen-reader user hears as two objects. */
    private const val IOU_THRESHOLD = 0.3f

    /** Hard cap on how many objects a single photo can surface, applied after NMS, keeping the
     *  [MAX_DETECTIONS] highest-confidence survivors — the user's own explicit limit. */
    const val MAX_DETECTIONS = 25

    @Volatile private var interpreter: Interpreter? = null
    private val loadLock = Any()

    private fun ensureInterpreter(context: Context): Interpreter {
        interpreter?.let { return it }
        synchronized(loadLock) {
            interpreter?.let { return it }
            val created = Interpreter(loadModelFile(context))
            resolveOutputIndices(created)
            interpreter = created
            return created
        }
    }

    private fun loadModelFile(context: Context): ByteBuffer {
        context.assets.openFd(ASSET_PATH).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /**
     * Runs detection on [bitmap] and returns up to [MAX_DETECTIONS] instances, highest confidence
     * first — through the companion app when it's installed and genuine (see [CompanionSegmenter]),
     * otherwise with the bundled YOLACT. Call from a background thread.
     */
    fun detect(context: Context, bitmap: Bitmap): List<DetectedInstance> {
        CompanionSegmenter.detect(context, bitmap)?.let { return it }
        // Serialized: a TFLite Interpreter is not thread-safe, and two callers can overlap — e.g. a
        // Basic Tasks Detect Objects still running when the user switches model and presses
        // Describe TV Image. Cancelling a coroutine doesn't stop a detect already in progress.
        return synchronized(runLock) { detectLocked(context, bitmap) }
    }

    private val runLock = Any()

    private fun detectLocked(context: Context, bitmap: Bitmap): List<DetectedInstance> {
        val interpreter = ensureInterpreter(context)
        val input = preprocess(bitmap, interpreter.getInputTensor(0))

        val outputs = HashMap<Int, Any>()
        for (i in 0 until interpreter.outputTensorCount) {
            val tensor = interpreter.getOutputTensor(i)
            val elements = tensor.shape().fold(1) { acc, d -> acc * d }
            outputs[i] = ByteBuffer.allocateDirect(tensor.dataType().byteSize() * elements).order(ByteOrder.nativeOrder())
        }
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        fun reader(index: Int) = TensorReader((outputs[index] as ByteBuffer).apply { rewind() }, interpreter.getOutputTensor(index))
        return decode(reader(locIndex), reader(confIndex), reader(coeffIndex), reader(protoIndex))
    }

    /** Reads a tensor's buffer as plain floats whether it's FLOAT32 or quantized INT8/UINT8
     *  (`real = scale * (raw - zeroPoint)`). */
    private class TensorReader(buffer: ByteBuffer, tensor: Tensor) {
        private val dataType = tensor.dataType()
        private val scale = tensor.quantizationParams().scale
        private val zeroPoint = tensor.quantizationParams().zeroPoint
        private val floatBuffer = if (dataType == DataType.FLOAT32) buffer.asFloatBuffer() else null
        private val byteBuffer = if (dataType != DataType.FLOAT32) buffer else null

        fun get(index: Int): Float = when (dataType) {
            DataType.FLOAT32 -> floatBuffer!!.get(index)
            DataType.INT8 -> scale * (byteBuffer!!.get(index).toInt() - zeroPoint)
            DataType.UINT8 -> scale * ((byteBuffer!!.get(index).toInt() and 0xFF) - zeroPoint)
            else -> error("Unsupported tensor dtype for detection output: $dataType")
        }
    }

    /** Squash-resizes [bitmap] to 550x550 and writes RGB 0..1, quantized per [inputTensor]'s own
     *  dtype/params, in NHWC order. */
    private fun preprocess(bitmap: Bitmap, inputTensor: Tensor): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        resized.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        if (resized !== bitmap) resized.recycle()

        val dataType = inputTensor.dataType()
        val quant = inputTensor.quantizationParams()
        val buffer = ByteBuffer.allocateDirect(dataType.byteSize() * pixels.size * 3).order(ByteOrder.nativeOrder())
        fun put(value: Float) {
            when (dataType) {
                DataType.FLOAT32 -> buffer.putFloat(value)
                DataType.INT8 -> buffer.put(Math.round(value / quant.scale + quant.zeroPoint).coerceIn(-128, 127).toByte())
                DataType.UINT8 -> buffer.put(Math.round(value / quant.scale + quant.zeroPoint).coerceIn(0, 255).toByte())
                else -> error("Unsupported tensor dtype for detection input: $dataType")
            }
        }
        for (pixel in pixels) {
            put(Color.red(pixel) / 255f)
            put(Color.green(pixel) / 255f)
            put(Color.blue(pixel) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    // ─────────────────────────────────────────────────────────────────────
    // Output tensors, told apart by shape.
    // ─────────────────────────────────────────────────────────────────────

    private var locIndex = -1
    private var confIndex = -1
    private var coeffIndex = -1
    private var protoIndex = -1
    private var protoSize = 0

    private fun resolveOutputIndices(interpreter: Interpreter) {
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            when {
                shape.size == 4 -> { protoIndex = i; protoSize = shape[1] }
                shape.last() == 4 -> locIndex = i
                shape.last() == NUM_CLASSES + 1 -> confIndex = i
                shape.last() == NUM_MASK_COEFFS -> coeffIndex = i
            }
        }
        check(locIndex != -1 && confIndex != -1 && coeffIndex != -1 && protoIndex != -1) {
            "Could not identify the segmentation model's output tensors by shape — has the model changed?"
        }
        check(interpreter.getOutputTensor(locIndex).shape()[1] == priors.size / 4) {
            "Segmentation model's anchor count doesn't match the prior table"
        }
    }

    /**
     * yolact_base_config's priors, as `[cx, cy, w, h]` per anchor, normalized 0..1, in the model's
     * own output order: FPN levels P3..P7 (69, 35, 18, 9, 5 cells per side, pixel scales 24, 48, 96,
     * 192, 384), row-major cells, then aspect ratios 1, 1/2, 2 — 3 x (69² + 35² + 18² + 9² + 5²) =
     * 19248. With that config's `use_pixel_scales`, `w = scale * sqrt(ar) / 550`, and
     * `use_square_anchors` (a bug in the original training, kept for weight compatibility) makes
     * `h = w`, so the aspect ratio only ever changes an anchor's size, never its shape.
     */
    private val priors: FloatArray by lazy {
        val convSizes = intArrayOf(69, 35, 18, 9, 5)
        val scales = floatArrayOf(24f, 48f, 96f, 192f, 384f)
        val aspectRatios = floatArrayOf(1f, 0.5f, 2f)
        val out = FloatArray(4 * aspectRatios.size * convSizes.sumOf { it * it })
        var k = 0
        for (level in convSizes.indices) {
            val conv = convSizes[level]
            for (j in 0 until conv) for (i in 0 until conv) {
                val cx = (i + 0.5f) / conv
                val cy = (j + 0.5f) / conv
                for (ar in aspectRatios) {
                    val w = scales[level] * sqrt(ar) / MODEL_INPUT_SIZE
                    out[k++] = cx; out[k++] = cy; out[k++] = w; out[k++] = w
                }
            }
        }
        out
    }

    private class Candidate(
        val anchor: Int, val classId: Int, val confidence: Float,
        val x1: Float, val y1: Float, val x2: Float, val y2: Float
    )

    private fun decode(loc: TensorReader, conf: TensorReader, coeffs: TensorReader, proto: TensorReader): List<DetectedInstance> {
        val anchorCount = priors.size / 4
        val candidates = ArrayList<Candidate>()
        for (a in 0 until anchorCount) {
            var bestClass = -1
            var bestScore = -1f
            val row = a * (NUM_CLASSES + 1)
            for (c in 1..NUM_CLASSES) {
                val score = conf.get(row + c)
                if (score > bestScore) { bestScore = score; bestClass = c - 1 }
            }
            if (bestScore < CONFIDENCE_THRESHOLD) continue

            val p = a * 4
            val cx = priors[p] + loc.get(p) * 0.1f * priors[p + 2]
            val cy = priors[p + 1] + loc.get(p + 1) * 0.1f * priors[p + 3]
            val w = priors[p + 2] * exp(loc.get(p + 2) * 0.2f)
            val h = priors[p + 3] * exp(loc.get(p + 3) * 0.2f)
            candidates.add(Candidate(a, bestClass, bestScore, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f))
        }

        return classAwareNms(candidates)
            .sortedByDescending { it.confidence }
            .take(MAX_DETECTIONS)
            .map { buildInstance(it, coeffs, proto) }
    }

    private fun classAwareNms(candidates: List<Candidate>): List<Candidate> {
        val survivors = ArrayList<Candidate>()
        for ((_, group) in candidates.groupBy { it.classId }) {
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                survivors.add(best)
                sorted.removeAll { iou(best, it) > IOU_THRESHOLD }
            }
        }
        return survivors
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val interArea = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1)) * max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val areaA = max(0f, a.x2 - a.x1) * max(0f, a.y2 - a.y1)
        val areaB = max(0f, b.x2 - b.x1) * max(0f, b.y2 - b.y1)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    private fun buildInstance(candidate: Candidate, coeffs: TensorReader, proto: TensorReader): DetectedInstance {
        val size = protoSize
        val coeff = FloatArray(NUM_MASK_COEFFS) { coeffs.get(candidate.anchor * NUM_MASK_COEFFS + it) }
        val left = candidate.x1 * size
        val top = candidate.y1 * size
        val right = candidate.x2 * size
        val bottom = candidate.y2 * size
        val mask = BooleanArray(size * size)
        for (py in 0 until size) {
            if (py < top || py >= bottom) continue
            for (px in 0 until size) {
                if (px < left || px >= right) continue
                var logit = 0f
                val base = (py * size + px) * NUM_MASK_COEFFS
                for (c in 0 until NUM_MASK_COEFFS) logit += coeff[c] * proto.get(base + c)
                // logit > 0 is sigmoid(logit) > 0.5 without the sigmoid.
                mask[py * size + px] = logit > 0f
            }
        }
        return DetectedInstance(
            label = CocoLabels.NAMES.getOrElse(candidate.classId) { "object" },
            confidence = candidate.confidence,
            boxLeft = candidate.x1.coerceIn(0f, 1f),
            boxTop = candidate.y1.coerceIn(0f, 1f),
            boxRight = candidate.x2.coerceIn(0f, 1f),
            boxBottom = candidate.y2.coerceIn(0f, 1f),
            mask = mask,
            maskWidth = size,
            maskHeight = size
        )
    }
}
