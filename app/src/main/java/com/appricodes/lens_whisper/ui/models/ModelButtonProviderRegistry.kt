package com.appricodes.lens_whisper.ui.models

import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.network.LocalServerFeature

/**
 * Resolves [com.appricodes.lens_whisper.data.PreferencesManager.selectedModelId] to the button
 * provider that should drive the grid, plus (for on-device catalog models) the resolved
 * [OfflineModelInfo] the host needs for download/memory gating and to populate
 * [ModelButtonContext.selectedOfflineModel].
 *
 * [mlkitProvider] is supplied by the host (not constructed here) because it's the one stateful
 * provider — it must keep its identity across recompositions so its active-mode/continuous-mode
 * state survives while MLKit stays selected. [OfflineVlmButtonProvider]/[LocalServerButtonProvider]
 * are stateless singletons.
 */
object ModelButtonProviderRegistry {
    fun forSelectedModel(
        selectedModelId: String,
        mlkitProvider: MlkitButtonProvider,
        offlineModels: List<OfflineModelInfo>
    ): Pair<ModelButtonProvider, OfflineModelInfo?> = when (selectedModelId) {
        "mlkit" -> mlkitProvider to null
        LocalServerFeature.MODEL_ID -> LocalServerButtonProvider to null
        else -> {
            val model = offlineModels.find { it.id == selectedModelId }
            OfflineVlmButtonProvider to model
        }
    }
}
