package com.appricodes.lens_whisper.inference

import android.content.Context
import android.graphics.Bitmap
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.utils.OfflineImageSize
import com.appricodes.lens_whisper.utils.OfflinePromptBuilder
import kotlinx.coroutines.flow.Flow

/**
 * Routes one analysis request to the Online Describer, a Local Server (see
 * [com.appricodes.lens_whisper.network.LocalServerDiscovery]), or the currently selected
 * on-device model, based on [PreferencesManager.selectedModelId]. [onlineCall]/[localServerCall]
 * perform the existing [com.appricodes.lens_whisper.network.XaiVisionEngine] request (each already
 * has the right Uri(s) for that path); [offlineImages] are the same frame(s) already decoded as
 * bitmaps, reused directly for on-device inference so nothing is captured/decoded twice.
 */
suspend fun runSelectedModelAnalysis(
    context: Context,
    prefs: PreferencesManager,
    config: PromptConfig,
    offlineImages: List<Bitmap>,
    onlineCall: suspend () -> String,
    localServerCall: suspend () -> String
): String {
    if (prefs.selectedModelId == "online") return onlineCall()
    if (prefs.selectedModelId == "local_server") return localServerCall()

    val repository = OfflineModelsRepository(context)
    val model = repository.loadModels().find { it.id == prefs.selectedModelId }
        ?: return "ERROR: Selected on-device model isn't available. Open Manage Advanced Models to pick one."
    if (!repository.isDownloaded(model)) {
        return "ERROR: ${model.displayName} isn't downloaded yet. Open Manage Advanced Models to download it."
    }
    if (offlineImages.isEmpty()) {
        return "ERROR: No image available to analyze."
    }

    val (userPrompt, systemInstruction) = buildOfflinePrompt(prefs, config, offlineImages.size)
    return OfflineInferenceEngine.analyze(
        context, model, offlineImages, userPrompt, systemInstruction,
        maxImageDimension = OfflineImageSize.maxDimensionFor(prefs.offlineImageSize)
    )
}

/** Shared by [runSelectedModelAnalysis] and [runSelectedOfflineModelAnalysisStreaming] so the two
 *  can never drift apart on how a prompt gets built for the on-device engine. */
private fun buildOfflinePrompt(prefs: PreferencesManager, config: PromptConfig, frameCount: Int): Pair<String, String> {
    val isCustomQuestion = config.id == "custom"
    val isExplore = config.id == "explore"
    val systemInstruction = when {
        isCustomQuestion -> OfflinePromptBuilder.buildCustomQuestionInstruction(prefs.selectedLanguage, config.prompt)
        isExplore -> OfflinePromptBuilder.buildExploreSystemInstruction(prefs.selectedLanguage, prefs.selectedMode)
        else -> OfflinePromptBuilder.buildSystemInstruction(prefs.selectedLanguage, prefs.selectedMode, frameCount > 1)
    }
    val userPrompt = when {
        isCustomQuestion -> config.prompt
        isExplore -> OfflinePromptBuilder.EXPLORE_USER_PROMPT
        else -> OfflinePromptBuilder.userPromptFor(config.id)
    }
    return userPrompt to systemInstruction
}

/**
 * Streaming counterpart to [runSelectedModelAnalysis] — offline models only (Online already streams
 * server-side through a different path; MLKit never routes through here at all). Callers gate on
 * `prefs.selectedModelId` not being "online"/"mlkit" *before* calling this (see
 * `AnalysisSessionController.isOfflineVlmSelected`) — unlike the non-streaming function above, this
 * one throws on any setup problem (model missing, not downloaded, no images) rather than returning
 * an "ERROR: ..." string, since a `Flow` has no natural place to encode that; the caller catches it
 * the same way it catches a mid-stream failure from [OfflineInferenceEngine.analyzeStreaming] itself.
 */
fun runSelectedOfflineModelAnalysisStreaming(
    context: Context,
    prefs: PreferencesManager,
    config: PromptConfig,
    offlineImages: List<Bitmap>
): Flow<String> {
    val repository = OfflineModelsRepository(context)
    val model: OfflineModelInfo = repository.loadModels().find { it.id == prefs.selectedModelId }
        ?: throw IllegalStateException("Selected on-device model isn't available. Open Manage Advanced Models to pick one.")
    if (!repository.isDownloaded(model)) {
        throw IllegalStateException("${model.displayName} isn't downloaded yet. Open Manage Advanced Models to download it.")
    }
    if (offlineImages.isEmpty()) {
        throw IllegalStateException("No image available to analyze.")
    }

    val (userPrompt, systemInstruction) = buildOfflinePrompt(prefs, config, offlineImages.size)
    return OfflineInferenceEngine.analyzeStreaming(
        context, model, offlineImages, userPrompt, systemInstruction,
        maxImageDimension = OfflineImageSize.maxDimensionFor(prefs.offlineImageSize)
    )
}
