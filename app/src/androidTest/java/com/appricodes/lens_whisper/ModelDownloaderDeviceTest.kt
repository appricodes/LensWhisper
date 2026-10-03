package com.appricodes.lens_whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.service.ModelDownloadState
import com.appricodes.lens_whisper.service.ModelDownloader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A real download through [ModelDownloader] / the system DownloadManager. The file is a small
 * Hugging Face LFS object, so it takes the same redirect-to-CDN path the real model downloads do.
 * Needs network.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloaderDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = OfflineModelsRepository(context)

    private val model = OfflineModelInfo(
        id = "download_test",
        displayName = "Download test",
        family = "",
        description = "",
        sizeBytes = 4_826_518,
        downloadUrl = "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx",
        fileName = "download_test.bin",
        backend = "CPU"
    )

    @Test
    fun downloadsIntoModelsDirAndRenamesWhenComplete() = runBlocking {
        repository.deleteDownload(model)
        ModelDownloader.start(context, model)
        val end = withTimeout(180_000) {
            ModelDownloader.state.first { it !is ModelDownloadState.Downloading && it != ModelDownloadState.Idle }
        }
        assertEquals(ModelDownloadState.Completed(model.id), end)
        val file = repository.localFileFor(model)
        assertEquals(OfflineModelsRepository.modelsDir(context), file.parentFile)
        assertEquals(model.sizeBytes, file.length())
        assertFalse(File(file.parentFile, model.fileName + ".part").exists())
        assertTrue(repository.isDownloaded(model))
        assertTrue(repository.deleteDownload(model))
        assertFalse(repository.isDownloaded(model))
    }

    @Test
    fun cancelLeavesNothingBehind() = runBlocking {
        repository.deleteDownload(model)
        ModelDownloader.start(context, model)
        ModelDownloader.cancel(context)
        assertEquals(ModelDownloadState.Cancelled(model.id), ModelDownloader.state.value)
        Thread.sleep(2000)
        assertFalse(repository.isDownloaded(model))
        assertFalse(File(OfflineModelsRepository.modelsDir(context), model.fileName + ".part").exists())
    }
}
