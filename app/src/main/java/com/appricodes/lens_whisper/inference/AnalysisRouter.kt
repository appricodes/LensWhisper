package com.appricodes.lens_whisper.inference

import android.content.Context
import android.graphics.Bitmap
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.utils.OfflineImageSize
import com.appricodes.lens_whisper.utils.OfflinePromptBuilder

/**
 * Routes one analysis request to either the Online Describer or the currently selected
 * on-device model, based on [PreferencesManager.selectedModelId]. [onlineCall] performs the
 * existing [com.appricodes.lens_whisper.network.XaiVisionEngine] request (it already has the
 * right Uri(s) for that path); [offlineImages] are the same frame(s) already decoded as
 * bitmaps, reused directly for on-device inference so nothing is captured/decoded twice.
 */
suspend fun runSelectedModelAnalysis(
    context: Context,
    prefs: PreferencesManager,
    config: PromptConfig,
    offlineImages: List<Bitmap>,
    onlineCall: suspend () -> String
): String {
    if (prefs.selectedModelId == "online") return onlineCall()

    val repository = OfflineModelsRepository(context)
    val model = repository.loadModels().find { it.id == prefs.selectedModelId }
        ?: return "ERROR: Selected on-device model isn't available. Open Manage Advanced Models to pick one."
    if (!repository.isDownloaded(model)) {
        return "ERROR: ${model.displayName} isn't downloaded yet. Open Manage Advanced Models to download it."
    }
    if (offlineImages.isEmpty()) {
        return "ERROR: No image available to analyze."
    }

    val isCustomQuestion = config.id == "custom"
    val isExplore = config.id == "explore"
    val systemInstruction = when {
        isCustomQuestion -> OfflinePromptBuilder.buildCustomQuestionInstruction(prefs.selectedLanguage, config.prompt)
        isExplore -> OfflinePromptBuilder.buildExploreSystemInstruction(prefs.selectedLanguage, prefs.selectedMode)
        else -> OfflinePromptBuilder.buildSystemInstruction(prefs.selectedLanguage, prefs.selectedMode, offlineImages.size > 1)
    }
    val userPrompt = when {
        isCustomQuestion -> config.prompt
        isExplore -> OfflinePromptBuilder.EXPLORE_USER_PROMPT
        else -> OfflinePromptBuilder.userPromptFor(config.id)
    }

    return OfflineInferenceEngine.analyze(
        context, model, offlineImages, userPrompt, systemInstruction,
        maxImageDimension = OfflineImageSize.maxDimensionFor(prefs.offlineImageSize)
    )
}
