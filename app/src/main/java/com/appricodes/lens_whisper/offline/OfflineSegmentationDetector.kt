package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.appricodes.lens_whisper.data.PreferencesManager
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One detected object: its class name, confidence, bounding box (normalized 0..1 in the *original*
 * photo's coordinate space, not the model's letterboxed 640x640 input), and its exact pixel mask —
 * queryable via [containsNormalizedPoint] rather than materialized as a full-resolution bitmap mask,
 * since nothing here ever needs to *draw* the mask, only ask "is this point part of it".
 */
class DetectedInstance internal constructor(
    val label: String,
    val confidence: Float,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    private val protoMask: BooleanArray,
    private val letterbox: LetterboxInfo
) {
    /** True if the point at normalized original-image coordinates ([nx], [ny], each 0..1) falls
     *  inside this instance's actual mask — not just its bounding box. Re-derives the same
     *  original-image -> letterboxed-640 -> proto-160 mapping [OfflineSegmentationDetector] used to
     *  build [protoMask] in the first place, run in reverse. */
    fun containsNormalizedPoint(nx: Float, ny: Float): Boolean {
        val origX = nx * letterbox.originalWidth
        val origY = ny * letterbox.originalHeight
        val letterboxedX = origX * letterbox.gain + letterbox.padX
        val letterboxedY = origY * letterbox.gain + letterbox.padY
        val protoX = floor(letterboxedX / OfflineSegmentationDetector.DOWNSAMPLE).toInt()
        val protoY = floor(letterboxedY / OfflineSegmentationDetector.DOWNSAMPLE).toInt()
        val protoSize = OfflineSegmentationDetector.PROTO_SIZE
        if (protoX < 0 || protoX >= protoSize || protoY < 0 || protoY >= protoSize) return false
        return protoMask[protoY * protoSize + protoX]
    }

    /**
     * Crops [sourceBitmap] (the same, full, un-letterboxed photo the detector ran on) to this
     * instance's mask — pixels outside the mask are made fully transparent — and names its top
     * [k] dominant colors via [OfflineColorDetector.namedDominantColors], which already expects
     * exactly that "transparent outside the region of interest" convention.
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

/** Maps between the original photo's pixel space and the model's letterboxed 640x640 input —
 *  see [OfflineSegmentationDetector.preprocess]. [gain] and [padX]/[padY] follow the exact same
 *  rounding convention (`round(x - 0.1)`) Ultralytics' own `scale_boxes`/`scale_masks` use, so a
 *  point mapped through this and back lands on the same pixel their reference implementation would
 *  put it on. */
internal class LetterboxInfo(val originalWidth: Int, val originalHeight: Int, val gain: Float, val padX: Float, val padY: Float)

/**
 * The `com.google.ai.edge.litert:litert` Gradle artifact (see app/build.gradle.kts) still ships its
 * classes under the classic `org.tensorflow.lite.*` package — confirmed by decompiling the actual
 * AAR rather than assumed, since the Maven *coordinate* changing on Google's rebrand did not mean
 * the *package name* did.
 *
 * Runs whichever bundled YOLOv8-seg model the user picked in Settings (see `assets/models/` —
 * **AGPL-3.0 licensed weights, see [LICENSING NOTE] below**) and decodes its raw output into
 * [DetectedInstance]s: name, confidence, box, and an exactly-queryable mask, capped to the top
 * [MAX_DETECTIONS] by confidence.
 *
 * ## Eight bundled files, dtype- and layout-agnostic decoding
 * [ALL_SIZES] (Nano/Small/Medium/Large) × [ALL_PRECISIONS] (int8/float16) — see [assetPathFor] for
 * the naming. The two precisions deliberately come from *two different* converters, because they
 * played different roles in getting here:
 * - **int8**: Ultralytics' own native exporter
 *   (`model.export(format="tflite", quantize="int8", data="coco128-seg.yaml")`), NCHW input,
 *   *normalized* (0..1) box coordinates.
 * - **float16**: `onnx2tf` (PyTorch → ONNX → onnx2tf), NHWC input, *pixel-space* (0..640) box
 *   coordinates. This is what every file here originally shipped as, back when the whole family
 *   still came from one backend. It was temporarily replaced by a w8a16 (int8 weights, int16
 *   activations) build from Ultralytics' native exporter — chosen at the time because that backend
 *   doesn't support float16 at all for this format — but real on-device testing on two phones (a
 *   Galaxy S8 and a Galaxy S21) found w8a16 running **more than 4x slower** than int8, on both, for
 *   every size. That's despite w8a16 being architecturally sound on paper (still integer
 *   arithmetic, normally at least as fast as pure int8); the likely cause is that TFLite's XNNPACK
 *   CPU delegate lacks an efficient kernel path for int16 activations on these chips and falls back
 *   to a slow reference implementation. w8a16 was dropped entirely rather than kept as a third
 *   option, and float16 — genuinely fast on CPU, unlike w8a16 turned out to be, and the whole point
 *   of a second precision to compare against int8 in the first place — took its place instead.
 *
 * Because these two precisions now come from genuinely different converters, both the layout and
 * box-scale axes are resolved per-loaded-model from its own reported tensor shape/values
 * ([preprocess]'s `channelFirst` check, [decodeAndNms]'s `boxScale` check) rather than assumed —
 * cheap insurance against a future re-export drifting from what's true today, and the reason this
 * class already coped fine with the two conventions disagreeing. The dtype side is handled the same
 * way via [TensorReader]: every tensor read/write goes through
 * [Tensor.dataType]/[Tensor.quantizationParams] rather than assuming FLOAT32, even though in
 * practice all eight files expose plain FLOAT32 at the input/output boundary regardless of
 * precision (the "int8" quantization is internal to the graph; boundary tensors are
 * dequantized by the exported model itself).
 *
 * The four int8 files were calibrated on the same 128 real, varied photos — Ultralytics' own
 * `coco128-seg` sample (real COCO train2017 images with segmentation labels, needed because these
 * are segmentation, not detection, exports) — preprocessed implicitly by Ultralytics' own export
 * pipeline, not this class's [preprocess]. An earlier pass through this same export used the
 * *default* calibration set `data="coco8-seg.yaml"` resolves to, which is only 4 images; Ultralytics'
 * own exporter warns "`>300 images recommended for INT8 calibration`" at that size, so every int8
 * file was re-exported against the larger, still-official 128-image set instead of leaving that
 * warning unaddressed. float16 has no calibration step — it's a lossless-shape reinterpretation of
 * the trained weights, not a quantization that needs representative data.
 *
 * ## LICENSING NOTE — read before any public release
 * [Ultralytics' own stated position](https://docs.ultralytics.com/help/licensing/) is that
 * AGPL-3.0 covers their trained model weights, not only their code — so shipping these files
 * publicly requires either releasing this whole app under AGPL-3.0 (source available to anyone who
 * gets the APK) or purchasing an Ultralytics Enterprise license. Neither has been done as of this
 * writing. Do not remove this note until one of those is actually true.
 *
 * ## Decode math
 * Every formula below (letterbox gain/pad, box decode, the proto-mask matrix multiply, and the
 * crop-to-box step) is copied from Ultralytics' own `ultralytics/utils/ops.py`
 * (`scale_boxes`/`process_mask`/`crop_mask`) and `ultralytics/utils/nms.py`
 * (`non_max_suppression`'s box/class/mask-coefficient split), not reconstructed from general
 * knowledge of the architecture — this is exactly the class of code where a plausible-looking but
 * subtly wrong formula produces a mask that silently doesn't line up with what's on screen, which
 * is a bad failure mode for a blind user to hit.
 *
 * The model's own box-decode (grid + stride + DFL) is baked into the exported graph — this class
 * only has to run NMS (never baked in for this export; see [decodeAndNms]) and the mask
 * matmul/crop, entirely in Kotlin, on the model's own decoded boxes (pixel-space or normalized,
 * auto-detected — see above).
 */
object OfflineSegmentationDetector {

    private const val TAG = "OfflineSegmentationDetector"

    /**
     * The four bundled sizes ([PreferencesManager.segmentationModelSize]'s valid values) — Nano
     * through Large, in Ultralytics' own n/s/m/l naming, used directly as the asset filename's
     * size letter — crossed with two precisions ([PreferencesManager.segmentationModelPrecision]:
     * "int8" or "float16"), eight bundled files total. Both axes are chosen in Settings;
     * [ensureInterpreter] reloads the interpreter whenever either selection changes.
     *
     * float16 briefly wasn't an option here: once every size besides Nano started coming from
     * Ultralytics' own native exporter (for int8), that same backend turned out not to support
     * float16 for this format at all — its own validation error lists only int8, w8a16 (int8
     * weights + int16 activations), dynamic int8, and FP32. w8a16 stood in for it for a while, but
     * real on-device testing found it more than 4x slower than int8 on both a Galaxy S8 and a
     * Galaxy S21 — see the class doc above for the likely cause. It was dropped, and float16 is
     * produced via `onnx2tf` (PyTorch → ONNX → onnx2tf) instead — the same route the very first
     * Nano build this app shipped came from — run identically across all four sizes so the
     * comparison is apples-to-apples.
     */
    const val SIZE_NANO = "n"
    const val SIZE_SMALL = "s"
    const val SIZE_MEDIUM = "m"
    const val SIZE_LARGE = "l"
    val ALL_SIZES = listOf(SIZE_NANO, SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE)

    const val PRECISION_INT8 = "int8"
    const val PRECISION_FLOAT16 = "float16"
    val ALL_PRECISIONS = listOf(PRECISION_INT8, PRECISION_FLOAT16)

    /** One line per size for the Settings picker — speed/accuracy is a real trade-off here, not
     *  just a label, so the picker says so rather than leaving the user to guess what N/S/M/L mean. */
    fun sizeDisplayName(size: String): String = when (size) {
        SIZE_NANO -> "Nano — fastest, least accurate"
        SIZE_SMALL -> "Small"
        SIZE_MEDIUM -> "Medium"
        SIZE_LARGE -> "Large — slowest, most accurate"
        else -> size
    }

    fun precisionDisplayName(precision: String): String = when (precision) {
        PRECISION_INT8 -> "int8 — smaller, faster"
        PRECISION_FLOAT16 -> "float16 — more accurate, larger"
        else -> precision
    }

    private fun assetPathFor(size: String, precision: String) = "models/yolov8${size}_seg_${precision}.tflite"

    private const val MODEL_INPUT_SIZE = 640
    private const val NUM_CLASSES = 80
    private const val NUM_MASK_COEFFS = 32
    internal const val PROTO_SIZE = 160
    internal const val DOWNSAMPLE = MODEL_INPUT_SIZE / PROTO_SIZE // 4 — proto grid is 4x downsampled from the 640 input, fixed by the model's own architecture, not configurable.
    private const val CANDIDATE_FEATURES = 4 + NUM_CLASSES + NUM_MASK_COEFFS // 116

    /** Below this, a detection is discarded before NMS even runs — see
     *  [DetectionPhrasing.MINIMUM_CONFIDENCE] for why this reads that constant rather than defining
     *  its own number: everything above it gets spoken, just hedged more the weaker it is, so the
     *  two need to stay exactly in sync. */
    private const val CONFIDENCE_THRESHOLD = DetectionPhrasing.MINIMUM_CONFIDENCE

    /** Standard greedy NMS IoU cutoff, matching Ultralytics' own `predict()` default. */
    private const val IOU_THRESHOLD = 0.45f

    /** Hard cap on how many objects a single photo can surface, applied after NMS, keeping the
     *  [MAX_DETECTIONS] highest-confidence survivors — the user's own explicit limit. */
    const val MAX_DETECTIONS = 25

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var loadedKey: String? = null
    private val loadLock = Any()

    /** Closes and reloads the interpreter whenever the (size, precision) pair differs from
     *  whatever's currently loaded — the same "close old, load new" shape [com.appricodes.lens_whisper.inference.OfflineInferenceEngine.ensureEngine]
     *  already uses for switching between VLM models, kept consistent rather than inventing a
     *  second model-switching pattern in the same app. Also resets the output-tensor shape cache
     *  ([resolveOutputIndices]'s fields) on every reload rather than only the first time — all
     *  eight bundled files share the same architecture and export backend and are expected to
     *  share its exact tensor layout too, but re-deriving it from each newly-loaded model's own
     *  reported shape costs nothing and removes the need to simply trust that assumption. */
    private fun ensureInterpreter(context: Context, size: String, precision: String): Interpreter {
        val key = "$size/$precision"
        interpreter?.let { if (loadedKey == key) return it }
        synchronized(loadLock) {
            interpreter?.let { if (loadedKey == key) return it }
            interpreter?.close()
            candidateOutputIndex = -1
            protoOutputIndex = -1
            val model = loadModelFile(context, size, precision)
            val created = Interpreter(model)
            interpreter = created
            loadedKey = key
            return created
        }
    }

    private fun loadModelFile(context: Context, size: String, precision: String): ByteBuffer {
        context.assets.openFd(assetPathFor(size, precision)).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /**
     * Runs detection on [bitmap] and returns up to [MAX_DETECTIONS] instances, highest confidence
     * first. Safe to call from a background thread (the caller — see the MLKit button handlers —
     * already runs capture+detect off the main thread; this does no thread-switching of its own).
     * Loads whichever (size, precision) [PreferencesManager.segmentationModelSize]/
     * [PreferencesManager.segmentationModelPrecision] currently name, reloading if the user
     * changed either in Settings since the last call.
     */
    fun detect(context: Context, bitmap: Bitmap, prefs: PreferencesManager): List<DetectedInstance> {
        val interpreter = ensureInterpreter(context, prefs.segmentationModelSize, prefs.segmentationModelPrecision)
        val letterbox = computeLetterbox(bitmap.width, bitmap.height)
        val inputBuffer = preprocess(bitmap, letterbox, interpreter.getInputTensor(0))

        val outputMap = allocateOutputs(interpreter)
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        val candidateBuffer = (outputMap[candidateOutputIndex] as ByteBuffer).apply { rewind() }
        val protoBuffer = (outputMap[protoOutputIndex] as ByteBuffer).apply { rewind() }
        val candidateReader = TensorReader(candidateBuffer, interpreter.getOutputTensor(candidateOutputIndex))
        val protoReader = TensorReader(protoBuffer, interpreter.getOutputTensor(protoOutputIndex))

        return decodeAndNms(candidateReader, protoReader, letterbox)
    }

    /**
     * Reads a tensor's buffer as plain floats regardless of whether the underlying tensor is
     * FLOAT32 (read directly) or a quantized INT8/UINT8 tensor (dequantized via its own
     * scale/zero-point — `real = scale * (raw - zeroPoint)`, the standard TFLite formula). Every
     * decode formula below reads through this rather than `ByteBuffer.asFloatBuffer()` directly,
     * so the same decode code works for both bundled model variants — see this object's doc.
     */
    private class TensorReader(buffer: ByteBuffer, tensor: Tensor) {
        private val dataType = tensor.dataType()
        private val quant = tensor.quantizationParams()
        private val floatBuffer = if (dataType == DataType.FLOAT32) buffer.asFloatBuffer() else null
        private val byteBuffer = if (dataType != DataType.FLOAT32) buffer else null

        fun get(index: Int): Float = when (dataType) {
            DataType.FLOAT32 -> floatBuffer!!.get(index)
            DataType.INT8 -> quant.scale * (byteBuffer!!.get(index).toInt() - quant.zeroPoint)
            // A raw INT8 byte read as Kotlin's signed Byte is already the correct -128..127 value;
            // UINT8's byte-pattern needs "and 0xFF" first so e.g. a stored 200 reads as 200, not -56.
            DataType.UINT8 -> quant.scale * ((byteBuffer!!.get(index).toInt() and 0xFF) - quant.zeroPoint)
            else -> error("Unsupported tensor dtype for detection output: $dataType")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Preprocessing: letterbox resize to 640x640, matching Ultralytics' own LetterBox transform
    // (aspect-preserving resize + centered mid-gray padding), since the model was trained and
    // exported expecting exactly that, not a plain stretch-to-square resize.
    // ─────────────────────────────────────────────────────────────────────

    private fun computeLetterbox(width: Int, height: Int): LetterboxInfo {
        val gain = min(MODEL_INPUT_SIZE.toFloat() / width, MODEL_INPUT_SIZE.toFloat() / height)
        val scaledWidth = Math.round(width * gain)
        val scaledHeight = Math.round(height * gain)
        // The "-0.1" bias matches Ultralytics' own rounding exactly (scale_boxes/scale_masks) —
        // without it, a photo whose padding lands exactly on a .5 pixel boundary can round the
        // opposite way here than it does inside the reference implementation this was copied from,
        // drifting every mask/box by one pixel at the edge.
        val padX = Math.round((MODEL_INPUT_SIZE - scaledWidth) / 2f - 0.1f).toFloat()
        val padY = Math.round((MODEL_INPUT_SIZE - scaledHeight) / 2f - 0.1f).toFloat()
        return LetterboxInfo(width, height, gain, padX, padY)
    }

    /**
     * Builds the model's input tensor. Two independent per-model choices are resolved from the
     * input tensor's own reported shape/dtype rather than assumed, since the two bundled models
     * were produced by different converters that disagree on both:
     * - **Pixel layout**: the onnx2tf-produced float16 build is NHWC (`[1,640,640,3]`, TF's native
     *   layout); the Ultralytics-native int8 build is NCHW (`[1,3,640,640]`, PyTorch's native
     *   layout, preserved as-is by that converter instead of being transposed). Writing NHWC pixel
     *   data into an NCHW input (or vice versa) doesn't fail loudly — it just silently feeds every
     *   channel the wrong plane, which is a much worse failure than a crash.
     * - **Value encoding**: FLOAT32 (both bundled models, as it happens — the int8 build's
     *   "int8 weights + int8 activations" quantization is internal only; its own input/output
     *   tensors are plain dequantized float32) vs INT8/UINT8 for a hypothetical future export that
     *   keeps the boundary quantized, handled for completeness even though neither shipped file
     *   currently exercises it.
     */
    private fun preprocess(bitmap: Bitmap, letterbox: LetterboxInfo, inputTensor: Tensor): ByteBuffer {
        val scaledWidth = Math.round(bitmap.width * letterbox.gain)
        val scaledHeight = Math.round(bitmap.height * letterbox.gain)

        val letterboxed = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.rgb(114, 114, 114)) // Ultralytics' own fixed letterbox pad color.
        val destRect = Rect(
            letterbox.padX.toInt(), letterbox.padY.toInt(),
            letterbox.padX.toInt() + scaledWidth, letterbox.padY.toInt() + scaledHeight
        )
        canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))

        val dataType = inputTensor.dataType()
        val quant = inputTensor.quantizationParams()
        // NCHW iff the channel dimension (3) is right after batch, matching this class's other
        // shape-based layout checks (resolveOutputIndices) rather than a hardcoded assumption.
        val channelFirst = inputTensor.shape().getOrNull(1) == 3

        val buffer = ByteBuffer.allocateDirect(dataType.byteSize() * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        letterboxed.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        fun putChannel(normalized: Float) {
            when (dataType) {
                DataType.FLOAT32 -> buffer.putFloat(normalized)
                DataType.INT8 -> buffer.put(Math.round(normalized / quant.scale + quant.zeroPoint).coerceIn(-128, 127).toByte())
                DataType.UINT8 -> buffer.put(Math.round(normalized / quant.scale + quant.zeroPoint).coerceIn(0, 255).toByte())
                else -> error("Unsupported tensor dtype for detection input: $dataType")
            }
        }

        if (channelFirst) {
            // NCHW: every red value for the whole image, then every green value, then every blue.
            for (channel in 0..2) {
                for (pixel in pixels) {
                    val v = when (channel) { 0 -> Color.red(pixel); 1 -> Color.green(pixel); else -> Color.blue(pixel) }
                    putChannel(v / 255f)
                }
            }
        } else {
            // NHWC: red, green, blue interleaved per pixel.
            for (pixel in pixels) {
                putChannel(Color.red(pixel) / 255f)
                putChannel(Color.green(pixel) / 255f)
                putChannel(Color.blue(pixel) / 255f)
            }
        }
        buffer.rewind()
        return buffer
    }

    // ─────────────────────────────────────────────────────────────────────
    // Output tensor bookkeeping — resolved by shape, not hardcoded index, since the converter
    // that produced this .tflite doesn't guarantee output ordering matches the PyTorch export's.
    // ─────────────────────────────────────────────────────────────────────

    private var candidateOutputIndex = -1
    private var protoOutputIndex = -1
    private var candidateAnchorCount = 0
    private var candidateChannelFirstResolved = true
    private var protoChannelFirstResolved = true

    private fun allocateOutputs(interpreter: Interpreter): MutableMap<Int, Any> {
        if (candidateOutputIndex == -1) resolveOutputIndices(interpreter)
        val map = mutableMapOf<Int, Any>()
        for (i in 0 until interpreter.outputTensorCount) {
            val tensor = interpreter.getOutputTensor(i)
            val elementCount = tensor.shape().fold(1) { acc, d -> acc * d }
            // Byte width comes from the tensor's own dtype (4 for FLOAT32, 1 for INT8/UINT8) —
            // hardcoding 4 here silently under-allocates for the int8 build's outputs.
            map[i] = ByteBuffer.allocateDirect(tensor.dataType().byteSize() * elementCount).order(ByteOrder.nativeOrder())
        }
        return map
    }

    /**
     * Identifies which output tensor is candidate-detections ([CANDIDATE_FEATURES]=116 somewhere
     * in its shape) vs. prototype-masks ([NUM_MASK_COEFFS]=32 and [PROTO_SIZE]=160 twice) by shape,
     * not by a hardcoded output index, since a converter isn't guaranteed to preserve the PyTorch
     * export's ordering. The channel-first/-last question for each (does the "channels" dimension
     * come right after batch — [1, 116, 8400]/[1, 32, 160, 160] — or last —
     * [1, 8400, 116]/[1, 160, 160, 32]) is resolved the same way, from the shape's own dimension
     * positions, which is exact for both output tensors here — 116, 8400, 32 and 160 are all
     * distinct values, so there's no realistic shape collision to disambiguate further.
     */
    private fun resolveOutputIndices(interpreter: Interpreter) {
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            if (shape.any { it == CANDIDATE_FEATURES }) {
                candidateOutputIndex = i
                candidateAnchorCount = shape.first { it != 1 && it != CANDIDATE_FEATURES }
                candidateChannelFirstResolved = shape.getOrNull(1) == CANDIDATE_FEATURES
            } else {
                protoOutputIndex = i
                protoChannelFirstResolved = shape.getOrNull(1) == NUM_MASK_COEFFS
            }
        }
        check(candidateOutputIndex != -1 && protoOutputIndex != -1) {
            "Could not identify the loaded segmentation model's output tensors by shape — has the exported model changed?"
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Decode: candidate extraction, confidence threshold, per-class NMS, mask crop — every formula
    // here is traced to Ultralytics' own ops.py/nms.py, see this object's doc.
    // ─────────────────────────────────────────────────────────────────────

    private class Candidate(
        val classId: Int, val confidence: Float,
        var x1: Float, var y1: Float, var x2: Float, var y2: Float,
        val maskCoeffs: FloatArray
    )

    private fun decodeAndNms(candidateReader: TensorReader, protoReader: TensorReader, letterbox: LetterboxInfo): List<DetectedInstance> {
        val n = candidateAnchorCount
        val candidates = ArrayList<Candidate>()

        // Resolved from the tensor's own reported shape — see resolveOutputIndices' doc.
        val channelFirst = candidateChannelFirstResolved

        fun feature(a: Int, f: Int): Float = if (channelFirst) candidateReader.get(f * n + a) else candidateReader.get(a * CANDIDATE_FEATURES + f)

        // Box scale: the onnx2tf-produced float16 build's box-decode head outputs pixel-space
        // coordinates (0..640, matching the model's own input size) with the grid/stride/DFL decode
        // baked in; the Ultralytics-native int8 build's head instead outputs *normalized* 0..1
        // coordinates for the same architecture — confirmed empirically (global box-channel max was
        // 0.99, never remotely close to 640) rather than assumed, since two different converters
        // disagreeing on this is exactly the kind of thing that silently produces wildly-wrong
        // (large negative, off-canvas) boxes if assumed rather than checked. A generous 2.0 cutoff
        // — real boxes are never that large in either convention — separates the two cases without
        // hardcoding per-file logic that would break the moment either model gets re-exported.
        var maxBoxValue = 0f
        for (a in 0 until n) {
            for (f in 0..3) maxBoxValue = max(maxBoxValue, kotlin.math.abs(feature(a, f)))
        }
        val boxScale = if (maxBoxValue <= 2f) MODEL_INPUT_SIZE.toFloat() else 1f

        for (a in 0 until n) {
            var bestClass = -1
            var bestScore = -1f
            for (c in 0 until NUM_CLASSES) {
                val score = feature(a, 4 + c)
                if (score > bestScore) { bestScore = score; bestClass = c }
            }
            if (bestScore < CONFIDENCE_THRESHOLD) continue

            val cx = feature(a, 0) * boxScale
            val cy = feature(a, 1) * boxScale
            val w = feature(a, 2) * boxScale
            val h = feature(a, 3) * boxScale
            val maskCoeffs = FloatArray(NUM_MASK_COEFFS) { feature(a, 4 + NUM_CLASSES + it) }
            candidates.add(
                Candidate(
                    classId = bestClass, confidence = bestScore,
                    x1 = cx - w / 2f, y1 = cy - h / 2f, x2 = cx + w / 2f, y2 = cy + h / 2f,
                    maskCoeffs = maskCoeffs
                )
            )
        }

        val kept = classAwareNms(candidates)
            .sortedByDescending { it.confidence }
            .take(MAX_DETECTIONS)

        return kept.map { candidate -> buildInstance(candidate, protoReader, protoChannelFirstResolved, letterbox) }
    }

    private fun classAwareNms(candidates: List<Candidate>): List<Candidate> {
        val byClass = candidates.groupBy { it.classId }
        val survivors = ArrayList<Candidate>()
        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()
            val picked = ArrayList<Candidate>()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                picked.add(best)
                sorted.removeAll { iou(best, it) > IOU_THRESHOLD }
            }
            survivors.addAll(picked)
        }
        return survivors
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val interLeft = max(a.x1, b.x1); val interTop = max(a.y1, b.y1)
        val interRight = min(a.x2, b.x2); val interBottom = min(a.y2, b.y2)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val areaA = max(0f, a.x2 - a.x1) * max(0f, a.y2 - a.y1)
        val areaB = max(0f, b.x2 - b.x1) * max(0f, b.y2 - b.y1)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    private fun buildInstance(
        candidate: Candidate,
        protoReader: TensorReader,
        protoChannelFirst: Boolean,
        letterbox: LetterboxInfo
    ): DetectedInstance {
        // Box: scale to proto (160) resolution to crop in the same space the mask matmul produces,
        // exactly matching process_mask(upsample=false)'s ratio step (width_ratio = mw/shape[1]).
        val protoScale = PROTO_SIZE.toFloat() / MODEL_INPUT_SIZE.toFloat()
        val boxProtoLeft = candidate.x1 * protoScale
        val boxProtoTop = candidate.y1 * protoScale
        val boxProtoRight = candidate.x2 * protoScale
        val boxProtoBottom = candidate.y2 * protoScale

        val mask = BooleanArray(PROTO_SIZE * PROTO_SIZE)
        for (py in 0 until PROTO_SIZE) {
            // crop_mask's half-open interval: row/col included iff >= start AND < end.
            if (py < boxProtoTop || py >= boxProtoBottom) continue
            for (px in 0 until PROTO_SIZE) {
                if (px < boxProtoLeft || px >= boxProtoRight) continue
                var logit = 0f
                for (c in 0 until NUM_MASK_COEFFS) {
                    val protoValue = if (protoChannelFirst) {
                        protoReader.get(c * PROTO_SIZE * PROTO_SIZE + py * PROTO_SIZE + px)
                    } else {
                        protoReader.get((py * PROTO_SIZE + px) * NUM_MASK_COEFFS + c)
                    }
                    logit += candidate.maskCoeffs[c] * protoValue
                }
                // gt 0.0 on the raw logit is exactly process_mask's masks.gt_(0.0) — equivalent to
                // sigmoid(logit) > 0.5 without spending a sigmoid call per pixel.
                mask[py * PROTO_SIZE + px] = logit > 0f
            }
        }

        // Box: undo the letterbox to land back in the *original* photo's normalized coordinates —
        // the exact inverse of scale_boxes' letterboxed->original mapping (subtract pad, divide by
        // gain), since here the box already IS in letterboxed-640 space and needs no further step
        // beyond normalizing.
        fun toOriginalNormalizedX(letterboxedX: Float) = ((letterboxedX - letterbox.padX) / letterbox.gain / letterbox.originalWidth).coerceIn(0f, 1f)
        fun toOriginalNormalizedY(letterboxedY: Float) = ((letterboxedY - letterbox.padY) / letterbox.gain / letterbox.originalHeight).coerceIn(0f, 1f)

        return DetectedInstance(
            label = CocoLabels.NAMES.getOrElse(candidate.classId) { "object" },
            confidence = candidate.confidence,
            boxLeft = toOriginalNormalizedX(candidate.x1),
            boxTop = toOriginalNormalizedY(candidate.y1),
            boxRight = toOriginalNormalizedX(candidate.x2),
            boxBottom = toOriginalNormalizedY(candidate.y2),
            protoMask = mask,
            letterbox = letterbox
        )
    }
}
