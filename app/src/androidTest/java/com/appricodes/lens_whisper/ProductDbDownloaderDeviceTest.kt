package com.appricodes.lens_whisper

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appricodes.lens_whisper.data.ProductDatabase
import com.appricodes.lens_whisper.data.ProductDbSource
import com.appricodes.lens_whisper.service.ProductDbDownloadState
import com.appricodes.lens_whisper.service.ProductDbDownloader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full product-database path against the live server: DownloadManager download, checksum
 * against the published `.sha256`, install, and a query. Several hundred MB — run it on its own.
 */
@RunWith(AndroidJUnit4::class)
class ProductDbDownloaderDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun downloadsVerifiesAndInstalls() = runBlocking {
        val info = ProductDbSource.probe()
        assertNotNull(info)
        ProductDbDownloader.start(context, info!!)
        val start = System.currentTimeMillis()
        val end = withTimeout(30 * 60_000L) {
            ProductDbDownloader.state.first {
                it is ProductDbDownloadState.Completed || it is ProductDbDownloadState.Failed || it is ProductDbDownloadState.Cancelled
            }
        }
        Log.i("ProductDbDownloaderTest", "ended $end after ${(System.currentTimeMillis() - start) / 1000} s, sha=${info.sha256}")
        assertEquals(ProductDbDownloadState.Completed, end)
        assertTrue(ProductDatabase.isInstalled(context))
        val count = ProductDatabase.productCount(context)
        Log.i("ProductDbDownloaderTest", "products: $count")
        assertTrue(count > 0)
        assertTrue(context.getExternalFilesDir("product_db")!!.listFiles().orEmpty().isEmpty())
    }
}
