package com.appricodes.lens_whisper.inference

import android.content.Context
import android.graphics.Bitmap
import com.appricodes.lens_whisper.data.OfflineModelsRepository
import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.network.LocalServerFeature
import com.appricodes.lens_whisper.utils.OfflineImageSize
import com.appricodes.lens_whisper.utils.OfflinePromptBuilder
import com.appricodes.lens_whisper.utils.ThermalGuard
import kotlinx.coroutines.flow.Flow

/**
 * Routes one analysis request to the currently selected on-device model, or - while
 * [LocalServerFeature] is switched on, which it is not - to the user's own desktop server via
 * [localServerCall]. [offlineImages] are the frame(s) already decoded as bitmaps, reused directly
 * for on-device inference so nothing is captured/decoded twice.
 *
 * The hosted-backend branch this used to have is gone: no image leaves the device any more.
 */
suspend fun runSelectedModelAnalysis(
    context: Context,
    prefs: PreferencesManager,
    config: PromptConfig,
    offlineImages: List<Bitmap>,
    localServerCall: suspend () -> String
): String {
    if (prefs.selectedModelId == LocalServerFeature.MODEL_ID) return localServerCall()

    // Checked before anything else: if the phone is too hot, no on-device model should run
    // regardless of which one is selected or whether it's even downloaded — see ThermalGuard.
    // Never blocks the Local Server branch above (nothing runs on this phone for that path) or
    // MLKit (which never reaches this function at all).
    ThermalGuard.blockingMessageOrNull(context)?.let { return "ERROR: $it" }

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
        else -> OfflinePromptBuilder.buildSystemInstruction(
            prefs.selectedLanguage, prefs.selectedMode, frameCount > 1,
            maxWords = OfflinePromptBuilder.maxWordsFor(config.id)
        )
    }
    val userPrompt = when {
        isCustomQuestion -> config.prompt
        isExplore -> OfflinePromptBuilder.EXPLORE_USER_PROMPT
        else -> OfflinePromptBuilder.userPromptFor(config.id)
    }
    return userPrompt to systemInstruction
}

/**
 * Streaming counterpart to [runSelectedModelAnalysis] - on-device models only (the Local Server
 * streams through its own path; MLKit never routes through here at all). Callers gate on
 * `prefs.selectedModelId` not being "mlkit"/"local_server" *before* calling this (see
 * `AnalysisSessionController.isOfflineVlmSelected`) — unlike the non-streaming function above, this
 * one throws on any setup problem (model missing, not downloaded, no images, device too hot)
 * rather than returning an "ERROR: ..." string, since a `Flow` has no natural place to encode that.
 * The throw happens eagerly, before this function returns a `Flow` at all — not lazily from inside
 * one — so the caller must wrap the *call* to this function in a try/catch, not just its `collect`;
 * see `AnalysisSessionController.runAnalysis`'s own try/catch around this call for why that matters.
 */
fun runSelectedOfflineModelAnalysisStreaming(
    context: Context,
    prefs: PreferencesManager,
    config: PromptConfig,
    offlineImages: List<Bitmap>
): Flow<String> {
    // See the matching check in runSelectedModelAnalysis above for why this comes first.
    ThermalGuard.blockingMessageOrNull(context)?.let { throw IllegalStateException(it) }

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
