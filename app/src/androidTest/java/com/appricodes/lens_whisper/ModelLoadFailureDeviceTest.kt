package com.appricodes.lens_whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine
import com.appricodes.lens_whisper.inference.OfflineInferenceEngine.LoadState
import com.appricodes.lens_whisper.utils.DeviceMemoryDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A model that can't be loaded must leave the app on Basic tasks with nothing of it in memory. */
@RunWith(AndroidJUnit4::class)
class ModelLoadFailureDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = PreferencesManager(context)

    private fun fakeModel(id: String, sizeBytes: Long) = OfflineModelInfo(
        id = id, displayName = "Test $id", family = "", description = "", sizeBytes = sizeBytes,
        downloadUrl = "https://example.invalid/$id", fileName = "$id.litertlm", backend = "CPU"
    )

    private fun failureFor(id: String) = runBlocking {
        withTimeout(120_000) {
            OfflineInferenceEngine.loadState.first { it is LoadState.Failed && it.modelId == id } as LoadState.Failed
        }
    }

    /** Plain ASCII, not random bytes: LiteRT-LM quotes the file's first bytes in its error message,
     *  and in a debug build CheckJNI aborts the process on a message that isn't valid UTF-8. */
    private fun junk(size: Long) = ByteArray(size.toInt()) { "not a model ".let { s -> s[it % s.length].code.toByte() } }

    @After
    fun cleanUp() {
        prefs.selectedModelId = "mlkit"
        OfflineModelsRepository.modelsDir(context)?.listFiles()?.filter { it.name.startsWith("loadtest_") }?.forEach { it.delete() }
    }

    @Test
    fun tooBigForFreeMemoryIsRefusedAndSwitchesToBasic() = runBlocking {
        val huge = fakeModel("loadtest_huge", DeviceMemoryDetector.availableRamBytes(context) * 4)
        prefs.selectedModelId = huge.id
        OfflineInferenceEngine.preload(context, huge)
        val failure = failureFor(huge.id)
        assertTrue(failure.message, "isn't enough free memory" in failure.message)
        assertTrue(failure.switchedToBasic)
        assertEquals("mlkit", prefs.selectedModelId)
        assertNull(prefs.modelLoadInProgress)
    }

    @Test
    fun corruptFileFailsSwitchesToBasicAndFreesTheSlot() = runBlocking {
        val dir = OfflineModelsRepository.modelsDir(context)!!
        val broken = fakeModel("loadtest_broken", 4L * 1024 * 1024)
        File(dir, broken.fileName).writeBytes(junk(broken.sizeBytes))
        prefs.selectedModelId = broken.id
        OfflineInferenceEngine.preload(context, broken)
        val failure = failureFor(broken.id)
        assertTrue(failure.switchedToBasic)
        assertEquals("mlkit", prefs.selectedModelId)
        assertNull(prefs.modelLoadInProgress)

        // Nothing of it stayed behind: a second model's load starts (and fails on its own) instead
        // of waiting behind a slot still held by the first.
        val second = fakeModel("loadtest_second", 4L * 1024 * 1024)
        File(dir, second.fileName).writeBytes(junk(second.sizeBytes))
        prefs.selectedModelId = second.id
        OfflineInferenceEngine.preload(context, second)
        val secondFailure = failureFor(second.id)
        assertFalse(secondFailure.message, "still loading" in secondFailure.message)
        assertEquals("mlkit", prefs.selectedModelId)
    }
}
