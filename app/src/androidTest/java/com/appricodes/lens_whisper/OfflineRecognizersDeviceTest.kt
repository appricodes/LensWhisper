package com.appricodes.lens_whisper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appricodes.lens_whisper.offline.OcrScript
import com.appricodes.lens_whisper.offline.OfflineBarcodeScanner
import com.appricodes.lens_whisper.offline.OfflineImageLabeler
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import com.appricodes.lens_whisper.offline.OfflineTextRecognizer
import com.appricodes.lens_whisper.offline.PaddleOcr
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the OCR, barcode reader, image labeler and segmentation detector on the photos in
 * androidTest/assets/ocr_test/ and logs what they found (`adb logcat -s OfflineRecognizersTest`).
 * The expectations are loose on purpose — key phrases and classes, not whole pages — so the test
 * catches a broken pipeline (garbage, empty output, wrong reading direction), not a model's normal
 * small misreads.
 *
 * The ML Kit tests need a device or emulator image with Google Play services; on first run they
 * wait for Play services to download the modules.
 */
@RunWith(AndroidJUnit4::class)
class OfflineRecognizersDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext

    private fun image(name: String): Bitmap =
        instrumentation.context.assets.open("ocr_test/$name").use { BitmapFactory.decodeStream(it) }

    private fun rotated(source: Bitmap, degrees: Int): Bitmap = if (degrees == 0) source
        else Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    /** Polls [ready] (which itself requests the module download) for up to two minutes. */
    private suspend fun awaitModule(what: String, ready: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!ready()) {
            assertTrue("$what module never became available", SystemClock.elapsedRealtime() < deadline)
            delay(2_000)
        }
    }

    private fun readPaddle(name: String, script: OcrScript, degrees: Int = 0): String {
        val bitmap = rotated(image(name), degrees)
        val start = SystemClock.elapsedRealtime()
        val lines = PaddleOcr.recognize(appContext, bitmap, script)
        val ms = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "=== $name rot$degrees [${script.label}] ${bitmap.width}x${bitmap.height} ${lines.size} lines in $ms ms")
        lines.forEach { Log.i(TAG, "  %.2f  %s".format(it.score, it.text)) }
        return lines.joinToString("\n") { it.text }
    }

    private suspend fun readMlKit(name: String, script: OcrScript): String {
        awaitModule(script.label) { OfflineTextRecognizer.isReady(appContext, script.label) }
        val text = OfflineTextRecognizer.recognizeText(appContext, image(name), script.label).orEmpty()
        Log.i(TAG, "=== $name [${script.label}] ML Kit:\n$text")
        return text
    }

    @Test
    fun paddlePageAtEveryRotation() {
        // The user can't see which way the page or phone is turned: every quarter turn must read.
        for (degrees in listOf(0, 90, 180, 270)) {
            val text = readPaddle("synth_ru.jpg", OcrScript.CYRILLIC, degrees)
            assertTrue("$degrees: $text", "Добро пожаловать" in text)
        }
    }

    @Test
    fun paddleScripts() {
        assertTrue(readPaddle("synth_fa.jpg", OcrScript.PERSIAN).let { "قیمت این کتاب" in it && "پزشک" in it })
        assertTrue(readPaddle("synth_ar.jpg", OcrScript.ARABIC).let { "مرحبا بكم في المكتبة العامة" in it })
    }

    @Test
    fun mlKitScripts() = runBlocking {
        val en = readMlKit("synth_en.jpg", OcrScript.LATIN)
        assertTrue(en, "Patient Information Leaflet" in en && "Keep out of reach of children." in en)
        assertTrue(readMlKit("synth_ko.jpg", OcrScript.KOREAN).let { "냉장" in it })
        assertTrue(readMlKit("synth_hi.jpg", OcrScript.DEVANAGARI).let { "नमस्ते" in it })
        assertTrue(readMlKit("synth_zh.jpg", OcrScript.CHINESE).let { "冷藏" in it })
        assertTrue(readMlKit("synth_ja.jpg", OcrScript.JAPANESE).let { "賞味期限" in it })
    }

    @Test
    fun documentBlocks() = runBlocking {
        awaitModule("Latin OCR") { OfflineTextRecognizer.isReady(appContext, OcrScript.LATIN.label) }
        // Upright and turned a quarter: ML Kit's lines follow the text, so the paragraphs must
        // come out the same either way.
        for (degrees in listOf(0, 90)) {
            val blocks = OfflineTextRecognizer.recognizeBlocks(appContext, rotated(image("synth_en.jpg"), degrees), OcrScript.LATIN.label)
            blocks.forEach { Log.i(TAG, "BLOCK rot$degrees [%.2f,%.2f-%.2f,%.2f] %s".format(it.boxLeft, it.boxTop, it.boxRight, it.boxBottom, it.text.replace('\n', '|'))) }
            // A paragraph is one block, not one block per line, and the two columns stay apart.
            val leftColumn = blocks.single { "Store in a cool, dry place." in it.text }
            assertTrue(leftColumn.text, "Keep out of reach of children." in leftColumn.text && "Take one tablet" !in leftColumn.text)
            val rightColumn = blocks.single { "Take one tablet twice a day" in it.text }
            assertTrue(rightColumn.text, rightColumn.text.indexOf("Take one tablet") < rightColumn.text.indexOf("Maximum 4 tablets"))
        }
    }

    @Test
    fun barcodes() = runBlocking {
        awaitModule("Barcode") { OfflineBarcodeScanner.isReady(appContext) }
        val qr = OfflineBarcodeScanner.pickBest(OfflineBarcodeScanner.scan(image("barcode_qr.jpg")))
        Log.i(TAG, "QR: ${qr?.formatName} ${qr?.value} 2D=${qr?.isTwoDimensional}")
        assertEquals("https://example.com/lenswhisper", qr?.value)
        assertTrue(qr!!.isTwoDimensional)

        val ean = OfflineBarcodeScanner.pickBest(OfflineBarcodeScanner.scan(image("barcode_ean13.jpg")))
        Log.i(TAG, "EAN: ${ean?.formatName} ${ean?.value} 2D=${ean?.isTwoDimensional}")
        assertEquals("4006381333931", ean?.value)
        assertTrue(!ean!!.isTwoDimensional)
    }

    /** COCO val2017 000000000036: a person holding an umbrella. */
    @Test
    fun segmentation() {
        val bitmap = image("coco_umbrella_person.jpg")
        val start = SystemClock.elapsedRealtime()
        val found = OfflineSegmentationDetector.detect(appContext, bitmap, useCompanion = false)
        Log.i(TAG, "YOLACT in ${SystemClock.elapsedRealtime() - start} ms: " + found.joinToString { "%s %.2f".format(it.label, it.confidence) })
        val umbrella = found.first { it.label == "umbrella" }
        assertTrue(umbrella.confidence > 0.8f)
        assertTrue(found.any { it.label == "person" && it.confidence > 0.6f })
        // A real mask: it fills a fair part of its box, but not all of it (an umbrella isn't a
        // rectangle). Sampled on a 20x20 grid inside the box.
        val samples = (0 until 400).count { i ->
            val nx = umbrella.boxLeft + (umbrella.boxRight - umbrella.boxLeft) * ((i % 20) + 0.5f) / 20
            val ny = umbrella.boxTop + (umbrella.boxBottom - umbrella.boxTop) * ((i / 20) + 0.5f) / 20
            umbrella.containsNormalizedPoint(nx, ny)
        }
        Log.i(TAG, "Umbrella mask covers $samples/400 of its box")
        assertTrue("$samples", samples in 80..360)
    }

    @Test
    fun imageLabels() = runBlocking {
        awaitModule("Image labeling") { OfflineImageLabeler.isReady(appContext) }
        val labels = OfflineImageLabeler.label(image("coco_umbrella_person.jpg"))
        Log.i(TAG, "Labels: " + labels.joinToString { "%s %.2f".format(it.text, it.confidence) })
        assertTrue(labels.any { it.text == "Umbrella" })
        // Abstract labels (feelings, occasions, activities) are filtered out.
        assertTrue(labels.none { it.text in setOf("Fun", "Leisure", "Vacation", "Smile", "Event") })
    }

    private companion object {
        const val TAG = "OfflineRecognizersTest"
    }
}
