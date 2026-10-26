package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.Canvas
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One detected object: its class name, confidence, bounding box (normalized 0..1 in the original
 * photo's coordinate space), and its pixel mask — queryable via [containsNormalizedPoint] ("is this
 * point part of it") and drawable via [maskBoundaryEdgesNormalized], both working directly off the
 * compact [maskWidth] x [maskHeight] grid [mask], which spans the *whole* original photo (row-major;
 * cell (x, y) covers normalized x/maskWidth..(x+1)/maskWidth, and the same for y).
 *
 * The grid is source-independent on purpose: the detector resamples the model's letterboxed proto
 * masks onto a grid spanning just the original photo.
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
 * Runs the bundled YOLOv8m-seg model (Ultralytics, AGPL-3.0; see NOTICE) and decodes its output
 * into [DetectedInstance]s: class, confidence, box and mask, at most [MAX_DETECTIONS], strongest first.
 *
 * The file comes from Ultralytics' own exporter (`format="tflite", int8=True,
 * data="coco128-seg.yaml"`): NCHW float input, normalized 0..1 boxes, plain float outputs. The
 * layout and box scale are still read from the model's own tensors rather than assumed. Every
 * formula (letterbox, box decode, the proto-mask matmul and crop) follows Ultralytics'
 * `ultralytics/utils/ops.py` and `nms.py`.
 */
object OfflineSegmentationDetector {

    private const val ASSET_PATH = "models/yolov8m_seg_int8.tflite"

    private const val MODEL_INPUT_SIZE = 640
    private const val NUM_CLASSES = 80
    private const val NUM_MASK_COEFFS = 32
    private const val PROTO_SIZE = 160
    private const val DOWNSAMPLE = MODEL_INPUT_SIZE / PROTO_SIZE
    private const val CANDIDATE_FEATURES = 4 + NUM_CLASSES + NUM_MASK_COEFFS // 116

    /** Below this, a detection is discarded before NMS even runs. */
    private const val CONFIDENCE_THRESHOLD = DetectionPhrasing.MINIMUM_CONFIDENCE
    private const val IOU_THRESHOLD = 0.45f
    const val MAX_DETECTIONS = 25

    @Volatile private var interpreter: Interpreter? = null
    private val runLock = Any()

    private class Letterbox(val width: Int, val height: Int, val gain: Float, val padX: Float, val padY: Float)

    private fun ensureInterpreter(context: Context): Interpreter {
        interpreter?.let { return it }
        synchronized(this) {
            interpreter?.let { return it }
            val model = context.assets.openFd(ASSET_PATH).use { fd ->
                FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
            }
            return Interpreter(model, Interpreter.Options().setNumThreads(4)).also {
                resolveOutputs(it)
                interpreter = it
            }
        }
    }

    /**
     * Runs detection on [bitmap] and returns up to [MAX_DETECTIONS] instances, highest confidence
     * first. Thread-safe: calls are serialized, since one TFLite interpreter can't run two
     * inferences at once (e.g. Basic Tasks Detect Objects still running when the user presses
     * Describe TV Image — cancelling a coroutine doesn't stop a detect already in progress).
     */
    fun detect(context: Context, bitmap: Bitmap): List<DetectedInstance> = synchronized(runLock) {
        val interpreter = ensureInterpreter(context)
        val letterbox = letterboxFor(bitmap.width, bitmap.height)
        val input = preprocess(bitmap, letterbox, interpreter.getInputTensor(0))
        val outputs = HashMap<Int, Any>()
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            outputs[i] = ByteBuffer.allocateDirect(t.dataType().byteSize() * t.shape().fold(1) { a, d -> a * d }).order(ByteOrder.nativeOrder())
        }
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
        val candidates = Reader((outputs[candidateIndex] as ByteBuffer).apply { rewind() }, interpreter.getOutputTensor(candidateIndex))
        val proto = Reader((outputs[protoIndex] as ByteBuffer).apply { rewind() }, interpreter.getOutputTensor(protoIndex))
        decode(candidates, proto, letterbox)
    }

    private class Reader(buffer: ByteBuffer, tensor: Tensor) {
        private val type = tensor.dataType()
        private val scale = tensor.quantizationParams().scale
        private val zero = tensor.quantizationParams().zeroPoint
        private val floats = if (type == DataType.FLOAT32) buffer.asFloatBuffer() else null
        private val bytes = if (type != DataType.FLOAT32) buffer else null
        fun get(i: Int): Float = when (type) {
            DataType.FLOAT32 -> floats!!.get(i)
            DataType.INT8 -> scale * (bytes!!.get(i).toInt() - zero)
            DataType.UINT8 -> scale * ((bytes!!.get(i).toInt() and 0xFF) - zero)
            else -> error("Unsupported output type $type")
        }
    }

    /** Aspect-preserving fit into 640x640 with centred grey padding, rounded the way Ultralytics' own
     *  `scale_boxes` rounds (`round(x - 0.1)`). */
    private fun letterboxFor(width: Int, height: Int): Letterbox {
        val gain = min(MODEL_INPUT_SIZE.toFloat() / width, MODEL_INPUT_SIZE.toFloat() / height)
        val padX = Math.round((MODEL_INPUT_SIZE - Math.round(width * gain)) / 2f - 0.1f).toFloat()
        val padY = Math.round((MODEL_INPUT_SIZE - Math.round(height * gain)) / 2f - 0.1f).toFloat()
        return Letterbox(width, height, gain, padX, padY)
    }

    private fun preprocess(bitmap: Bitmap, lb: Letterbox, input: Tensor): ByteBuffer {
        val boxed = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(boxed).apply {
            drawColor(Color.rgb(114, 114, 114))
            val l = lb.padX.toInt()
            val t = lb.padY.toInt()
            drawBitmap(bitmap, null, Rect(l, t, l + Math.round(bitmap.width * lb.gain), t + Math.round(bitmap.height * lb.gain)), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        boxed.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        boxed.recycle()

        val type = input.dataType()
        val q = input.quantizationParams()
        val buffer = ByteBuffer.allocateDirect(type.byteSize() * pixels.size * 3).order(ByteOrder.nativeOrder())
        fun put(v: Float) = when (type) {
            DataType.FLOAT32 -> buffer.putFloat(v)
            DataType.INT8 -> buffer.put(Math.round(v / q.scale + q.zeroPoint).coerceIn(-128, 127).toByte())
            DataType.UINT8 -> buffer.put(Math.round(v / q.scale + q.zeroPoint).coerceIn(0, 255).toByte())
            else -> error("Unsupported input type $type")
        }
        if (input.shape().getOrNull(1) == 3) {
            // NCHW: every red value, then every green, then every blue.
            for (c in 0..2) for (p in pixels) put(when (c) { 0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p) } / 255f)
        } else {
            for (p in pixels) { put(Color.red(p) / 255f); put(Color.green(p) / 255f); put(Color.blue(p) / 255f) }
        }
        buffer.rewind()
        return buffer
    }

    private var candidateIndex = -1
    private var protoIndex = -1
    private var anchorCount = 0
    private var candidateChannelFirst = true
    private var protoChannelFirst = true

    private fun resolveOutputs(interpreter: Interpreter) {
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            if (shape.any { it == CANDIDATE_FEATURES }) {
                candidateIndex = i
                anchorCount = shape.first { it != 1 && it != CANDIDATE_FEATURES }
                candidateChannelFirst = shape.getOrNull(1) == CANDIDATE_FEATURES
            } else {
                protoIndex = i
                protoChannelFirst = shape.getOrNull(1) == NUM_MASK_COEFFS
            }
        }
        check(candidateIndex != -1 && protoIndex != -1) { "Unexpected segmentation model outputs" }
    }

    private class Candidate(val classId: Int, val confidence: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val coeffs: FloatArray)

    private fun decode(c: Reader, proto: Reader, lb: Letterbox): List<DetectedInstance> {
        val n = anchorCount
        fun f(a: Int, k: Int) = if (candidateChannelFirst) c.get(k * n + a) else c.get(a * CANDIDATE_FEATURES + k)

        // Normalized (0..1) or pixel-space (0..640) boxes depending on the exporter — see the
        // object doc. Real boxes never exceed 2 in the normalized convention.
        var maxBox = 0f
        for (a in 0 until n) for (k in 0..3) maxBox = max(maxBox, kotlin.math.abs(f(a, k)))
        val boxScale = if (maxBox <= 2f) MODEL_INPUT_SIZE.toFloat() else 1f

        val candidates = ArrayList<Candidate>()
        for (a in 0 until n) {
            var best = -1
            var bestScore = -1f
            for (k in 0 until NUM_CLASSES) {
                val s = f(a, 4 + k)
                if (s > bestScore) { bestScore = s; best = k }
            }
            if (bestScore < CONFIDENCE_THRESHOLD) continue
            val cx = f(a, 0) * boxScale
            val cy = f(a, 1) * boxScale
            val w = f(a, 2) * boxScale
            val h = f(a, 3) * boxScale
            candidates += Candidate(best, bestScore, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2,
                FloatArray(NUM_MASK_COEFFS) { f(a, 4 + NUM_CLASSES + it) })
        }

        val kept = ArrayList<Candidate>()
        for ((_, group) in candidates.groupBy { it.classId }) {
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept += best
                sorted.removeAll { iou(best, it) > IOU_THRESHOLD }
            }
        }
        return kept.sortedByDescending { it.confidence }.take(MAX_DETECTIONS).map { build(it, proto, lb) }
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val inter = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1)) * max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Builds the proto-resolution mask (crop to box, `logit > 0`, as `process_mask` does), then
     * resamples it onto a grid spanning just the original image — about one cell per proto cell,
     * so no detail is lost and none invented.
     */
    private fun build(cand: Candidate, proto: Reader, lb: Letterbox): DetectedInstance {
        val scale = PROTO_SIZE.toFloat() / MODEL_INPUT_SIZE
        val bl = cand.x1 * scale; val bt = cand.y1 * scale; val br = cand.x2 * scale; val bb = cand.y2 * scale
        val protoMask = BooleanArray(PROTO_SIZE * PROTO_SIZE)
        for (py in 0 until PROTO_SIZE) {
            if (py < bt || py >= bb) continue
            for (px in 0 until PROTO_SIZE) {
                if (px < bl || px >= br) continue
                var logit = 0f
                for (k in 0 until NUM_MASK_COEFFS) {
                    val v = if (protoChannelFirst) proto.get(k * PROTO_SIZE * PROTO_SIZE + py * PROTO_SIZE + px)
                        else proto.get((py * PROTO_SIZE + px) * NUM_MASK_COEFFS + k)
                    logit += cand.coeffs[k] * v
                }
                protoMask[py * PROTO_SIZE + px] = logit > 0f
            }
        }

        val gridW = max(1, ceil(lb.width * lb.gain / DOWNSAMPLE).toInt())
        val gridH = max(1, ceil(lb.height * lb.gain / DOWNSAMPLE).toInt())
        val grid = BooleanArray(gridW * gridH)
        for (y in 0 until gridH) for (x in 0 until gridW) {
            val lx = ((x + 0.5f) / gridW) * lb.width * lb.gain + lb.padX
            val ly = ((y + 0.5f) / gridH) * lb.height * lb.gain + lb.padY
            val px = floor(lx / DOWNSAMPLE).toInt()
            val py = floor(ly / DOWNSAMPLE).toInt()
            if (px in 0 until PROTO_SIZE && py in 0 until PROTO_SIZE) grid[y * gridW + x] = protoMask[py * PROTO_SIZE + px]
        }

        fun nx(v: Float) = ((v - lb.padX) / lb.gain / lb.width).coerceIn(0f, 1f)
        fun ny(v: Float) = ((v - lb.padY) / lb.gain / lb.height).coerceIn(0f, 1f)
        return DetectedInstance(
            label = CocoLabels.NAMES.getOrElse(cand.classId) { "object" },
            confidence = cand.confidence,
            boxLeft = nx(cand.x1), boxTop = ny(cand.y1), boxRight = nx(cand.x2), boxBottom = ny(cand.y2),
            mask = grid, maskWidth = gridW, maskHeight = gridH
        )
    }
}
