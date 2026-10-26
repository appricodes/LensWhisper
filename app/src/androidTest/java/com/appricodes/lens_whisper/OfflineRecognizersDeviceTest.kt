package com.appricodes.lens_whisper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appricodes.lens_whisper.offline.OfflineBarcodeScanner
import com.appricodes.lens_whisper.offline.OfflineSegmentationDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the barcode reader and segmentation detector on the photos in
 * androidTest/assets/ocr_test/ and logs what they found (`adb logcat -s OfflineRecognizersTest`).
 * The expectations are loose on purpose — key phrases and classes, not whole pages — so the test
 * catches a broken pipeline (garbage, empty output, wrong reading direction), not a model's normal
 * small misreads.
 */
@RunWith(AndroidJUnit4::class)
class OfflineRecognizersDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext

    private fun image(name: String): Bitmap =
        instrumentation.context.assets.open("ocr_test/$name").use { BitmapFactory.decodeStream(it) }

    @Test
    fun barcodes() {
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
        val found = OfflineSegmentationDetector.detect(appContext, bitmap)
        Log.i(TAG, "Segmentation in ${SystemClock.elapsedRealtime() - start} ms: " + found.joinToString { "%s %.2f".format(it.label, it.confidence) })
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

    private companion object {
        const val TAG = "OfflineRecognizersTest"
    }
}
