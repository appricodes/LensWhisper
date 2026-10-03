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
import com.appricodes.lens_whisper.offline.OfflineTextRecognizer
import com.appricodes.lens_whisper.offline.PaddleOcr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the bundled OCR and barcode reader on the photos in androidTest/assets/ocr_test/ and logs
 * what they read (`adb logcat -s OfflineRecognizersTest`). The OCR expectations are loose on
 * purpose — key phrases, not whole pages — so the test catches a broken pipeline (garbage, empty
 * output, wrong reading direction), not a model's normal small misreads.
 */
@RunWith(AndroidJUnit4::class)
class OfflineRecognizersDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext

    private fun image(name: String): Bitmap =
        instrumentation.context.assets.open("ocr_test/$name").use { BitmapFactory.decodeStream(it) }

    private fun read(name: String, script: OcrScript, degrees: Int = 0): String {
        val source = image(name)
        val bitmap = if (degrees == 0) source
            else Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        val start = SystemClock.elapsedRealtime()
        val lines = PaddleOcr.recognize(appContext, bitmap, script)
        val ms = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "=== $name rot$degrees [${script.label}] ${bitmap.width}x${bitmap.height} ${lines.size} lines in $ms ms")
        lines.forEach { Log.i(TAG, "  %.2f  %s".format(it.score, it.text)) }
        return lines.joinToString("\n") { it.text }
    }

    @Test
    fun latinPageAtEveryRotation() {
        // The user can't see which way the page or phone is turned: every quarter turn must read.
        for (degrees in listOf(0, 90, 180, 270)) {
            val text = read("synth_en.jpg", OcrScript.LATIN, degrees)
            assertTrue("$degrees: $text", "Patient Information Leaflet" in text)
            assertTrue("$degrees: $text", "Keep out of reach of children." in text)
            assertTrue("$degrees: $text", "Maximum 4 tablets in 24 hours." in text)
        }
    }

    @Test
    fun otherScripts() {
        assertTrue(read("synth_fa.jpg", OcrScript.PERSIAN).let { "قیمت این کتاب" in it && "پزشک" in it })
        assertTrue(read("synth_ar.jpg", OcrScript.ARABIC).let { "مرحبا بكم في المكتبة العامة" in it })
        assertTrue(read("synth_ko.jpg", OcrScript.KOREAN).let { "냉장 보관" in it })
        assertTrue(read("synth_hi.jpg", OcrScript.DEVANAGARI).let { "नमस्ते" in it })
        assertTrue(read("synth_zh.jpg", OcrScript.CHINESE).let { "本产品请冷藏保存" in it })
        assertTrue(read("synth_ja.jpg", OcrScript.JAPANESE).let { "賞味期限" in it })
        assertTrue(read("synth_ru.jpg", OcrScript.CYRILLIC).let { "Добро пожаловать" in it })
    }

    @Test
    fun documentBlocks() = runBlocking {
        val blocks = OfflineTextRecognizer.recognizeBlocks(appContext, image("synth_en.jpg"), OcrScript.LATIN.label)
        blocks.forEach { Log.i(TAG, "BLOCK [%.2f,%.2f-%.2f,%.2f] %s".format(it.boxLeft, it.boxTop, it.boxRight, it.boxBottom, it.text.replace('\n', '|'))) }
        // The two columns must stay separate paragraphs, each read top to bottom.
        val leftColumn = blocks.single { "Store in a cool, dry place." in it.text }
        assertTrue(leftColumn.text, "Keep out of reach of children." in leftColumn.text && "Take one tablet" !in leftColumn.text)
        val rightColumn = blocks.single { "Take one tablet twice a day" in it.text }
        assertTrue(rightColumn.text, rightColumn.text.indexOf("Take one tablet") < rightColumn.text.indexOf("Maximum 4 tablets"))
        assertTrue(leftColumn.boxRight < rightColumn.boxLeft)
    }

    @Test
    fun barcodes() {
        val qr = OfflineBarcodeScanner.pickBest(OfflineBarcodeScanner.scan(image("barcode_qr.jpg")))
        Log.i(TAG, "QR: ${qr?.formatName} ${qr?.value} 2D=${qr?.isTwoDimensional}")
        assertEquals("https://example.com/lenswhisper", qr?.value)
        assertEquals("QR code", qr?.formatName)
        assertTrue(qr!!.isTwoDimensional)

        val ean = OfflineBarcodeScanner.pickBest(OfflineBarcodeScanner.scan(image("barcode_ean13.jpg")))
        Log.i(TAG, "EAN: ${ean?.formatName} ${ean?.value} 2D=${ean?.isTwoDimensional}")
        assertEquals("4006381333931", ean?.value)
        assertEquals("EAN-13 barcode", ean?.formatName)
        assertTrue(!ean!!.isTwoDimensional)
    }

    private companion object {
        const val TAG = "OfflineRecognizersTest"
    }
}
