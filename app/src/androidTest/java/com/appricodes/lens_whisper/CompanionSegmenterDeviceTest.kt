package com.appricodes.lens_whisper

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appricodes.lens_whisper.offline.CompanionSegmenter
import com.appricodes.lens_whisper.segmenter.client.SegmenterClient
import com.appricodes.lens_whisper.segmenter.client.SegmenterContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The companion app bridge. Needs a debug build of LensWhisper Object Explorer installed (signed
 * with the same debug keystore); the tests are skipped when it isn't.
 */
@RunWith(AndroidJUnit4::class)
class CompanionSegmenterDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext

    private fun photo() = instrumentation.context.assets.open("ocr_test/coco_umbrella_person.jpg").use { BitmapFactory.decodeStream(it) }

    private fun assumeCompanionInstalled() = assumeTrue(
        "companion not installed",
        runCatching { appContext.packageManager.getPackageInfo(SegmenterContract.PACKAGE, 0) }.isSuccess
    )

    @Test
    fun segmentsThroughTheCompanion() {
        assumeCompanionInstalled()
        assertTrue(CompanionSegmenter.isAvailable(appContext))
        val found = CompanionSegmenter.detect(appContext, photo())
        assertNotNull(found)
        Log.i(TAG, "Companion: " + found!!.joinToString { "%s %.2f".format(it.label, it.confidence) })
        val umbrella = found.first { it.label == "umbrella" }
        assertTrue(umbrella.confidence > 0.6f)
        assertTrue(found.any { it.label == "person" })
        val inside = (0 until 400).count { i ->
            umbrella.containsNormalizedPoint(
                umbrella.boxLeft + (umbrella.boxRight - umbrella.boxLeft) * ((i % 20) + 0.5f) / 20,
                umbrella.boxTop + (umbrella.boxBottom - umbrella.boxTop) * ((i / 20) + 0.5f) / 20
            )
        }
        Log.i(TAG, "Companion umbrella mask covers $inside/400 of its box")
        assertTrue("$inside", inside in 80..360)
    }

    /** A companion signed with any other key never gets the photo. */
    @Test
    fun refusesAnUntrustedCompanion() {
        assumeCompanionInstalled()
        val wrongKey = SegmenterClient(appContext, setOf("00".repeat(32)))
        assertFalse(wrongKey.isAvailable())
        val file = File(appContext.cacheDir, "untrusted_test.jpg")
        file.outputStream().use { photo().compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        assertNull(wrongKey.segment(file))
        assertFalse(SegmenterClient(appContext, emptySet()).isAvailable()) // an empty list fails closed
        file.delete()
    }

    /** The service refuses non-images cleanly instead of crashing. */
    @Test
    fun rejectsGarbage() {
        assumeCompanionInstalled()
        val file = File(appContext.cacheDir, "garbage.jpg").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val trusted = SegmenterClient(appContext, SegmenterContract.RELEASE_CERT_SHA256 + "8AA3AD16A80E33DA89A27AD6BD4A31826A680EDA913CE1CC5DAF04B035266273")
        val result = trusted.segment(file)
        assertNotNull(result)
        assertTrue("${result!!.first}", result.first == SegmenterContract.STATUS_BAD_IMAGE)
        file.delete()
    }

    private companion object {
        const val TAG = "CompanionTest"
    }
}
