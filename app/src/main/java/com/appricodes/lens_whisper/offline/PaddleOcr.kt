package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One script the offline OCR can read. [label] is the exact string stored in
 * [com.appricodes.lens_whisper.data.PreferencesManager.ocrLanguage] (and shown in Settings) — the
 * first five are the labels the ML Kit-based recognizer used, kept verbatim so a choice saved
 * before the switch still maps to a model. [model] names the bundled
 * `assets/models/ocr/rec_<model>.tflite` + `dict_<model>.txt` pair.
 */
enum class OcrScript(val label: String, val model: String, val rightToLeft: Boolean = false) {
    LATIN("Latin (English/EU)", "latin"),
    CHINESE("Chinese", "ch"),
    JAPANESE("Japanese", "ch"),
    KOREAN("Korean", "korean"),
    DEVANAGARI("Devanagari", "devanagari"),
    PERSIAN("Persian", "arabic", rightToLeft = true),
    ARABIC("Arabic", "arabic", rightToLeft = true),
    CYRILLIC("Cyrillic (Russian etc.)", "cyrillic");

    companion object {
        fun fromLabel(label: String): OcrScript = entries.firstOrNull { it.label == label } ?: LATIN
    }
}

/** One recognized text line. [quad] is its outline in source-image pixels; [upright] is the same
 *  outline turned with the page so its text reads upright (see [PaddleOcr]'s page orientation),
 *  which is the frame [left]/[top]/... and all layout work use. Both are four (x, y) corners:
 *  top-left, top-right, bottom-right, bottom-left. */
class OcrLine(val quad: FloatArray, val upright: FloatArray, val text: String, val score: Float) {
    val left get() = min(min(upright[0], upright[2]), min(upright[4], upright[6]))
    val right get() = max(max(upright[0], upright[2]), max(upright[4], upright[6]))
    val top get() = min(min(upright[1], upright[3]), min(upright[5], upright[7]))
    val bottom get() = max(max(upright[1], upright[3]), max(upright[5], upright[7]))
    /** The text's own height, measured across the (possibly tilted) line rather than its
     *  upright bounding box, which a long tilted line inflates. */
    val textHeight get() = max(hypot(upright[6] - upright[0], upright[7] - upright[1]), hypot(upright[4] - upright[2], upright[5] - upright[3]))
    val imageLeft get() = min(min(quad[0], quad[2]), min(quad[4], quad[6]))
    val imageRight get() = max(max(quad[0], quad[2]), max(quad[4], quad[6]))
    val imageTop get() = min(min(quad[1], quad[3]), min(quad[5], quad[7]))
    val imageBottom get() = max(max(quad[1], quad[3]), max(quad[5], quad[7]))
}

/**
 * Fully offline OCR: PaddleOCR PP-OCRv5 mobile (Apache-2.0) text detection + per-script
 * recognition, run on LiteRT from bundled models under `assets/models/ocr/`.
 *
 * The models were converted from PaddlePaddle's official ONNX exports (ONNX -> PyTorch via
 * onnx2torch -> LiteRT via litert-torch, weights int8 weight-only quantized), and checked
 * against ONNX Runtime on the same inputs. Neither model takes a free input size, so each is
 * exported with several fixed-size signatures sharing one set of weights: the detector at a few
 * page shapes ([DET_SIZES]), the recognizer at a few line widths ([REC_WIDTHS]). An input is
 * scaled to fit, then zero-padded up to the smallest signature that holds it.
 *
 * The pre/post-processing follows PaddleOCR's own (DBPostProcess + CTCLabelDecode) with one
 * substitution: boxes come from 8-connected components of the thresholded probability map rather
 * than OpenCV contours, which gives the same minimum-area rectangles for text-line blobs.
 *
 * Not thread-safe by itself; every entry point is @Synchronized on this object.
 */
object PaddleOcr {

    private const val ASSET_DIR = "models/ocr"

    /** Detector signatures as (height, width), named `d<H>x<W>`. */
    private val DET_SIZES = listOf(960 to 960, 960 to 736, 736 to 960, 960 to 544, 544 to 960)
    private const val DET_LIMIT = 960
    private const val DET_THRESH = 0.3f
    private const val BOX_THRESH = 0.6f
    private const val UNCLIP_RATIO = 1.5f
    private const val MIN_SIZE = 3f
    private const val MAX_CANDIDATES = 1000
    private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f) // applied to B, G, R in that order
    private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Recognizer signatures, named `w<W>`; every one is 48 pixels high. */
    private val REC_WIDTHS = intArrayOf(320, 640, 960, 1280, 1920)
    private const val REC_HEIGHT = 48

    /** Lines whose mean character confidence falls below this are dropped — at that level the
     *  text is more likely noise (a texture, a logo) than something worth reading aloud. */
    private const val DROP_SCORE = 0.5f

    private var detector: Interpreter? = null
    private var recognizer: Interpreter? = null
    private var recognizerModel: String? = null
    private var dictionary: List<String> = emptyList()

    /** Input/output buffers, one per signature and direction, kept between calls: the detector's
     *  are several MB and the Chinese recognizer's widest output ~18 MB, and continuous Read Text
     *  would otherwise allocate them afresh every few seconds. */
    private val buffers = HashMap<String, FloatBuffer>()

    @Synchronized
    fun recognize(context: Context, image: Bitmap, script: OcrScript): List<OcrLine> {
        // getPixels and software-canvas drawing both need a CPU-readable bitmap.
        val bitmap = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
        val det = detector ?: open(context, "det.tflite").also { detector = it }
        val rec = recognizerFor(context, script.model)
        val boxes = detect(det, bitmap)
        val (turns, sampled) = pageTurns(rec, bitmap, boxes)
        val lines = ArrayList<OcrLine>()
        for (quad in boxes) {
            val (raw, score) = sampled[quad] ?: recognizeLine(rec, bitmap, quad, shiftFor(quad, turns))
            if (score < DROP_SCORE || raw.isBlank()) continue
            var text = if (script.rightToLeft) visualToLogicalRtl(raw) else raw
            if (script == OcrScript.PERSIAN) text = toPersianLetters(text)
            text = text.trim()
            if (text.isNotEmpty()) lines += OcrLine(quad, toUpright(quad, turns, bitmap.width, bitmap.height), text, score)
        }
        return lines
    }

    // ------------------------------------------------------------------ page orientation

    /** Width and height of a box along its own top/left edges. */
    private fun dims(q: FloatArray) = max(dist(q, 0, 1), dist(q, 3, 2)) to max(dist(q, 0, 3), dist(q, 1, 2))

    /**
     * How many quarter turns (counter-clockwise, as a shift of the corner order) make the page
     * upright. A blind user can't see which way the page or the phone is turned, and the
     * recognizer only reads upright text, so this is decided per photo: the boxes' majority shape
     * narrows it to 0/180 or 90/270 degrees, then the three longest lines are read both ways and
     * the more confident reading wins. Also returns those sample lines' readings in the winning
     * orientation, so they aren't recognized twice.
     */
    private fun pageTurns(rec: Interpreter, bitmap: Bitmap, boxes: List<FloatArray>): Pair<Int, Map<FloatArray, Pair<String, Float>>> {
        if (boxes.isEmpty()) return 0 to emptyMap()
        var vertical = 0
        var horizontal = 0
        for (q in boxes) {
            val (w, h) = dims(q)
            if (h >= 1.5f * w) vertical++ else if (w >= 1.5f * h) horizontal++
        }
        val base = if (vertical > horizontal) 1 else 0
        val sample = boxes.sortedByDescending { q -> dims(q).let { max(it.first, it.second) } }.take(3)
        fun read(turns: Int) = sample.associateWith { q -> recognizeLine(rec, bitmap, q, shiftFor(q, turns)) }
        val a = read(base)
        val b = read(base + 2)
        return if (a.values.map { it.second }.average() >= b.values.map { it.second }.average()) base to a else (base + 2) to b
    }

    /** The corner shift to read one box with: the page's [turns], plus one more for a box that is
     *  vertical relative to the turned page — PaddleOCR's own rule for vertical text. */
    private fun shiftFor(quad: FloatArray, turns: Int): Int {
        val (w, h) = dims(quad)
        val extra = if (turns % 2 == 0) h >= 1.5f * w else w >= 1.5f * h
        return (turns + if (extra) 1 else 0) % 4
    }

    /** [quad] in the page's upright frame: its corners shifted by [turns] and the image turned the
     *  same way (counter-clockwise quarter turns). */
    private fun toUpright(quad: FloatArray, turns: Int, w: Int, h: Int): FloatArray {
        val q = shifted(quad, turns)
        for (i in 0 until 4) {
            val x = q[2 * i]
            val y = q[2 * i + 1]
            when (turns % 4) {
                1 -> { q[2 * i] = y; q[2 * i + 1] = w - x }
                2 -> { q[2 * i] = w - x; q[2 * i + 1] = h - y }
                3 -> { q[2 * i] = h - y; q[2 * i + 1] = x }
            }
        }
        return q
    }

    /** The same four corners, starting [shift] corners later. */
    private fun shifted(quad: FloatArray, shift: Int) = FloatArray(8) { quad[(it + 2 * shift) % 8] }

    /** Frees the native interpreters (tens of MB once their weights are expanded). */
    @Synchronized
    fun close() {
        detector?.close()
        detector = null
        recognizer?.close()
        recognizer = null
        recognizerModel = null
        dictionary = emptyList()
        buffers.clear()
    }

    // ------------------------------------------------------------------ model loading

    private fun open(context: Context, name: String): Interpreter {
        val buffer = context.assets.openFd("$ASSET_DIR/$name").use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        return Interpreter(buffer, Interpreter.Options().setNumThreads(4))
    }

    private fun recognizerFor(context: Context, model: String): Interpreter {
        recognizer?.let { if (recognizerModel == model) return it }
        // Only one recognizer stays loaded: switching scripts is rare, and each one is
        // several MB of expanded weights.
        recognizer?.close()
        recognizer = null
        dictionary = context.assets.open("$ASSET_DIR/dict_$model.txt").bufferedReader(Charsets.UTF_8).use { reader ->
            // Index 0 is CTC's blank and the last class is a space, around the model's dictionary.
            // Split on '\n' only: some dictionary entries are themselves whitespace (U+3000).
            // removeSuffix("\r"): a CRLF checkout must not leak '\r' into the characters.
            listOf("") + reader.readText().removeSuffix("\n").split('\n').map { it.removeSuffix("\r") } + listOf(" ")
        }
        return open(context, "rec_$model.tflite").also {
            recognizer = it
            recognizerModel = model
        }
    }

    // ------------------------------------------------------------------ detection

    private fun detect(det: Interpreter, bitmap: Bitmap): List<FloatArray> {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val ratio = DET_LIMIT.toFloat() / max(srcW, srcH)
        val rh = max(32, ((srcH * ratio).toInt() / 32f).roundToInt() * 32)
        val rw = max(32, ((srcW * ratio).toInt() / 32f).roundToInt() * 32)
        val (bh, bw) = DET_SIZES.filter { it.first >= rh && it.second >= rw }.minByOrNull { it.first * it.second }
            ?: DET_SIZES.first()
        val h = min(rh, bh)
        val w = min(rw, bw)

        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        // NCHW, BGR channel order, zero (= the mean colour after normalization) outside the image.
        val plane = bh * bw
        val key = "d${bh}x${bw}"
        val input = buffer("in:$key", 3 * plane)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val o = y * bw + x
                input.put(o, (((p and 0xFF) / 255f) - DET_MEAN[0]) / DET_STD[0])
                input.put(plane + o, ((((p shr 8) and 0xFF) / 255f) - DET_MEAN[1]) / DET_STD[1])
                input.put(2 * plane + o, ((((p shr 16) and 0xFF) / 255f) - DET_MEAN[2]) / DET_STD[2])
            }
        }
        val output = buffer("out:$key", plane, zero = false)
        det.runSignature(
            mapOf(det.getSignatureInputs(key)[0] to input),
            mapOf(det.getSignatureOutputs(key)[0] to output),
            key
        )
        // Crop the probability map back to the real (unpadded) image area.
        val prob = FloatArray(w * h)
        for (y in 0 until h) {
            output.position(y * bw)
            output.get(prob, y * w, w)
        }
        output.rewind()

        val scaleX = srcW.toFloat() / w
        val scaleY = srcH.toFloat() / h
        val boxes = ArrayList<FloatArray>()
        for (boundary in components(prob, w, h)) {
            val hull = convexHull(boundary)
            var rect = minAreaRect(hull)
            if (min(rect.w, rect.h) < MIN_SIZE) continue
            if (boxScore(prob, w, h, rect.corners()) < BOX_THRESH) continue
            // PaddleOCR "unclips" (grows) the shrunken text kernel the detector predicts by
            // area * ratio / perimeter on every side; for a rectangle that is exactly this.
            val d = rect.w * rect.h * UNCLIP_RATIO / (2 * (rect.w + rect.h))
            rect = rect.copy(w = rect.w + 2 * d, h = rect.h + 2 * d)
            if (min(rect.w, rect.h) < MIN_SIZE + 2) continue
            val c = rect.corners()
            for (i in 0 until 4) {
                c[2 * i] = (c[2 * i] * scaleX).coerceIn(0f, srcW.toFloat())
                c[2 * i + 1] = (c[2 * i + 1] * scaleY).coerceIn(0f, srcH.toFloat())
            }
            boxes += c
        }
        return boxes
    }

    /** 8-connected components of `prob > DET_THRESH`, each returned as its boundary pixels
     *  (pixels with a 4-neighbour outside the component), flattened as x, y pairs. */
    private fun components(prob: FloatArray, w: Int, h: Int): List<IntArray> {
        val label = IntArray(w * h)
        val result = ArrayList<IntArray>()
        val stack = IntArray(w * h)
        var next = 0
        for (start in 0 until w * h) {
            if (prob[start] <= DET_THRESH || label[start] != 0) continue
            if (result.size >= MAX_CANDIDATES) break
            next++
            var sp = 0
            stack[sp++] = start
            label[start] = next
            val boundary = IntList()
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                var edge = false
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val xx = x + dx
                        val yy = y + dy
                        val inside = xx in 0 until w && yy in 0 until h && prob[yy * w + xx] > DET_THRESH
                        if (inside) {
                            val j = yy * w + xx
                            if (label[j] == 0) {
                                label[j] = next
                                stack[sp++] = j
                            }
                        } else if (dx == 0 || dy == 0) {
                            edge = true
                        }
                    }
                }
                if (edge) boundary.add(x, y)
            }
            result += boundary.toArray()
        }
        return result
    }

    private data class RotatedRect(val cx: Float, val cy: Float, val w: Float, val h: Float, val ux: Float, val uy: Float) {
        /** Corners as top-left, top-right, bottom-right, bottom-left — PaddleOCR's get_mini_boxes
         *  ordering: sort by x, then split each side's pair by y. */
        fun corners(): FloatArray {
            val vx = -uy
            val vy = ux
            val pts = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (sx, sy) ->
                (cx + sx * w / 2 * ux + sy * h / 2 * vx) to (cy + sx * w / 2 * uy + sy * h / 2 * vy)
            }.sortedBy { it.first }
            val left = pts.subList(0, 2).sortedBy { it.second }
            val right = pts.subList(2, 4).sortedBy { it.second }
            return floatArrayOf(
                left[0].first, left[0].second, right[0].first, right[0].second,
                right[1].first, right[1].second, left[1].first, left[1].second
            )
        }
    }

    /** Andrew's monotone chain over x, y pairs; returns the hull as x, y pairs. */
    private fun convexHull(points: IntArray): FloatArray {
        val n = points.size / 2
        val idx = (0 until n).sortedWith(compareBy({ points[2 * it] }, { points[2 * it + 1] }))
            .distinctBy { points[2 * it].toLong() shl 32 or (points[2 * it + 1].toLong() and 0xFFFFFFFFL) }
        if (idx.size <= 2) return FloatArray(idx.size * 2) { k -> points[2 * idx[k / 2] + k % 2].toFloat() }
        fun cross(o: Int, a: Int, b: Int): Long {
            val ox = points[2 * o].toLong(); val oy = points[2 * o + 1].toLong()
            return (points[2 * a] - ox) * (points[2 * b + 1] - oy) - (points[2 * a + 1] - oy) * (points[2 * b] - ox)
        }
        val hull = IntArray(2 * idx.size)
        var k = 0
        for (p in idx) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0) k--
            hull[k++] = p
        }
        val lowerSize = k + 1
        for (p in idx.asReversed().drop(1)) {
            while (k >= lowerSize && cross(hull[k - 2], hull[k - 1], p) <= 0) k--
            hull[k++] = p
        }
        k-- // the last point repeats the first
        return FloatArray(2 * k) { i -> points[2 * hull[i / 2] + i % 2].toFloat() }
    }

    /** Rotating calipers: the minimum-area rectangle has a side collinear with a hull edge. */
    private fun minAreaRect(hull: FloatArray): RotatedRect {
        val n = hull.size / 2
        if (n == 1) return RotatedRect(hull[0], hull[1], 0f, 0f, 1f, 0f)
        var best: RotatedRect? = null
        var bestArea = Float.MAX_VALUE
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = hull[2 * j] - hull[2 * i]
            val ey = hull[2 * j + 1] - hull[2 * i + 1]
            val len = hypot(ex, ey)
            if (len == 0f) continue
            val ux = ex / len
            val uy = ey / len
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (p in 0 until n) {
                val u = hull[2 * p] * ux + hull[2 * p + 1] * uy
                val v = -hull[2 * p] * uy + hull[2 * p + 1] * ux
                minU = min(minU, u); maxU = max(maxU, u)
                minV = min(minV, v); maxV = max(maxV, v)
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                val cu = (maxU + minU) / 2
                val cv = (maxV + minV) / 2
                best = RotatedRect(cu * ux - cv * uy, cu * uy + cv * ux, maxU - minU, maxV - minV, ux, uy)
            }
        }
        return best ?: RotatedRect(hull[0], hull[1], 0f, 0f, 1f, 0f)
    }

    /** Mean probability inside the (convex, clockwise-in-image-coordinates) quad. */
    private fun boxScore(prob: FloatArray, w: Int, h: Int, quad: FloatArray): Float {
        val x0 = max(0, floor(min(min(quad[0], quad[2]), min(quad[4], quad[6]))).toInt())
        val x1 = min(w - 1, ceil(max(max(quad[0], quad[2]), max(quad[4], quad[6]))).toInt())
        val y0 = max(0, floor(min(min(quad[1], quad[3]), min(quad[5], quad[7]))).toInt())
        val y1 = min(h - 1, ceil(max(max(quad[1], quad[3]), max(quad[5], quad[7]))).toInt())
        var sum = 0.0
        var count = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                var inside = true
                for (i in 0 until 4) {
                    val ax = quad[2 * i]; val ay = quad[2 * i + 1]
                    val bx = quad[(2 * i + 2) % 8]; val by = quad[(2 * i + 3) % 8]
                    if ((bx - ax) * (y - ay) - (by - ay) * (x - ax) < 0) {
                        inside = false
                        break
                    }
                }
                if (inside) {
                    sum += prob[y * w + x]
                    count++
                }
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    // ------------------------------------------------------------------ recognition

    /** Reads one box, its corners first shifted by [shift] (each step turns the strip a quarter
     *  turn counter-clockwise — see [shiftFor]). */
    private fun recognizeLine(rec: Interpreter, bitmap: Bitmap, quad: FloatArray, shift: Int): Pair<String, Float> {
        val src = shifted(quad, shift)
        val (cw, ch) = dims(src)
        val wanted = max(1, ceil(REC_HEIGHT * cw / max(ch, 1e-6f)).toInt())
        val bucket = REC_WIDTHS.firstOrNull { it >= wanted } ?: REC_WIDTHS.last()
        val tw = min(wanted, bucket) // over-long lines are squeezed into the widest signature

        // Perspective-warp the quad straight to a tw x 48 strip.
        val strip = Bitmap.createBitmap(tw, REC_HEIGHT, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setPolyToPoly(src, 0, floatArrayOf(0f, 0f, tw.toFloat(), 0f, tw.toFloat(), REC_HEIGHT.toFloat(), 0f, REC_HEIGHT.toFloat()), 0, 4)
        Canvas(strip).drawBitmap(bitmap, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(tw * REC_HEIGHT)
        strip.getPixels(pixels, 0, tw, 0, 0, tw, REC_HEIGHT)
        strip.recycle()

        val plane = REC_HEIGHT * bucket
        val key = "w$bucket"
        val input = buffer("in:$key", 3 * plane)
        for (y in 0 until REC_HEIGHT) {
            for (x in 0 until tw) {
                val p = pixels[y * tw + x]
                val o = y * bucket + x
                input.put(o, (p and 0xFF) / 127.5f - 1f)
                input.put(plane + o, ((p shr 8) and 0xFF) / 127.5f - 1f)
                input.put(2 * plane + o, ((p shr 16) and 0xFF) / 127.5f - 1f)
            }
        }
        val steps = bucket / 8
        val classes = dictionary.size
        val logits = buffer("out:$key", steps * classes, zero = false)
        rec.runSignature(
            mapOf(rec.getSignatureInputs(key)[0] to input),
            mapOf(rec.getSignatureOutputs(key)[0] to logits),
            key
        )

        // Greedy CTC: best class per step, collapse repeats, drop blanks.
        val text = StringBuilder()
        var scoreSum = 0f
        var emitted = 0
        var prev = -1
        for (t in 0 until steps) {
            var best = 0
            var bestP = logits.get(t * classes)
            for (c in 1 until classes) {
                val v = logits.get(t * classes + c)
                if (v > bestP) { bestP = v; best = c }
            }
            if (best != prev && best != 0) {
                text.append(dictionary[best])
                scoreSum += bestP
                emitted++
            }
            prev = best
        }
        return text.toString() to (if (emitted == 0) 0f else scoreSum / emitted)
    }

    private fun dist(q: FloatArray, a: Int, b: Int) = hypot(q[2 * a] - q[2 * b], q[2 * a + 1] - q[2 * b + 1])

    // ------------------------------------------------------------------ text fix-ups

    /** Characters that keep their left-to-right order inside a right-to-left line: Latin letters,
     *  Western/Arabic-Indic/Persian digits and the punctuation that joins them in numbers/dates. */
    private fun isLtrRunChar(c: Char) =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in '٠'..'٩' || c in '۰'..'۹' ||
            c == ' ' || c == ':' || c == '*' || c == '.' || c == '/' || c == '%' || c == '+' || c == '-'

    /** CTC reads a line left to right, i.e. in visual order; for Arabic script that is the reverse
     *  of reading order. PaddleOCR's own fix (pred_reverse): reverse the line, keeping runs of
     *  [isLtrRunChar] intact. Extended here to Persian/Arabic-Indic digits, which PaddleOCR's
     *  ASCII-only pattern would otherwise reverse digit by digit. */
    internal fun visualToLogicalRtl(text: String): String {
        val parts = ArrayList<String>()
        val run = StringBuilder()
        for (c in text) {
            if (isLtrRunChar(c)) {
                run.append(c)
            } else {
                if (run.isNotEmpty()) { parts += run.toString(); run.clear() }
                parts += c.toString()
            }
        }
        if (run.isNotEmpty()) parts += run.toString()
        return parts.asReversed().joinToString("")
    }

    /** The Arabic-script model outputs Arabic letter forms; Persian text uses its own yeh/kaf and
     *  digits, which TalkBack's Persian voice reads correctly. */
    internal fun toPersianLetters(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    'ي', 'ى' -> 'ی' // Arabic yeh / alef maksura -> Persian yeh
                    'ك' -> 'ک' // Arabic kaf -> Persian keheh
                    in '٠'..'٩' -> '۰' + (c - '٠') // Arabic-Indic -> Persian digits
                    else -> c
                }
            )
        }
    }

    /** The cached buffer [name] of exactly [size] floats, zeroed for inputs — padding outside the image or
     *  line must read as zero, and the previous call may have written there. */
    private fun buffer(name: String, size: Int, zero: Boolean = true): FloatBuffer {
        val cached = buffers[name]?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { buffers[name] = it }
        cached.rewind()
        if (zero) for (i in 0 until size) cached.put(i, 0f)
        return cached
    }

    /** Growable int list for x, y pairs without boxing. */
    private class IntList {
        private var data = IntArray(64)
        private var size = 0
        fun add(x: Int, y: Int) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }
        fun toArray(): IntArray = data.copyOf(size)
    }
}
