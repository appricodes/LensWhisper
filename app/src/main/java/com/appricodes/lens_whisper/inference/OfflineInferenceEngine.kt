package com.appricodes.lens_whisper.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.utils.ImageScaling
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Runs on-device analysis via LiteRT-LM against whichever downloaded model is currently
 * selected. Only one model is ever loaded at a time (they're 1-3GB+) — switching the selected
 * model closes the previous engine and loads the new one on the next query. Loading is slow
 * (up to ~10s), so it's cached across calls and only reloaded when the model actually changes.
 */
object OfflineInferenceEngine {
    private const val TAG = "OfflineInferenceEngine"

    private var loadedEngine: Engine? = null
    private var loadedModelId: String? = null
    private val mutex = Mutex()

    private suspend fun ensureEngine(context: Context, model: OfflineModelInfo): Engine = mutex.withLock {
        loadedEngine?.let { if (loadedModelId == model.id) return@withLock it }

        loadedEngine?.close()
        loadedEngine = null
        loadedModelId = null

        val modelFile = OfflineModelsRepository(context.applicationContext).localFileFor(model)
        val engineConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
            visionBackend = Backend.GPU(),
            cacheDir = context.applicationContext.cacheDir.path
        )
        val engine = Engine(engineConfig)
        engine.initialize()
        loadedEngine = engine
        loadedModelId = model.id
        engine
    }

    /**
     * Runs one query against [model] with the given image(s) and prompt. Returns the model's
     * text response, or an "ERROR: ..." string on failure — matching [com.appricodes.lens_whisper.network.XaiVisionEngine]'s
     * convention so callers can handle both the same way.
     */
    suspend fun analyze(
        context: Context,
        model: OfflineModelInfo,
        images: List<Bitmap>,
        userPrompt: String,
        systemInstruction: String,
        maxImageDimension: Int
    ): String = withContext(Dispatchers.IO) {
        try {
            val engine = ensureEngine(context, model)
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(Content.Text(systemInstruction))
            )
            engine.createConversation(conversationConfig).use { conversation ->
                val contents = mutableListOf<Content>()
                for (bitmap in images) {
                    val scaled = ImageScaling.scaleToMax(bitmap, maxImageDimension)
                    val stream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    contents.add(Content.ImageBytes(stream.toByteArray()))
                }
                // Text after image(s), matching the model's expected token ordering.
                contents.add(Content.Text(userPrompt))
                conversation.sendMessage(Contents.of(contents)).toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "On-device analysis failed for model ${model.id}", e)
            "ERROR: On-device analysis failed. ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    /** Releases the loaded model's resources, e.g. when the user picks a different one. */
    fun release() {
        loadedEngine?.close()
        loadedEngine = null
        loadedModelId = null
    }
}
