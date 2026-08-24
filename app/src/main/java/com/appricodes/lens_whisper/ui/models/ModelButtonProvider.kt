package com.appricodes.lens_whisper.ui.models

import com.appricodes.lens_whisper.data.PreferencesManager
import com.appricodes.lens_whisper.domain.AppSettings
import com.appricodes.lens_whisper.domain.OfflineModelInfo
import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.ui.components.GridCell

/**
 * Everything a [ModelButtonProvider] needs to build its grid, supplied by the shared
 * [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController] via the hosting screen
 * ([com.appricodes.lens_whisper.CameraActivity] / MediaDescriberScreen) so providers stay thin —
 * they only decide *which* cells exist and *what* they're labeled/wired to, never how analysis,
 * Explore, or Repeat actually work.
 */
data class ModelButtonContext(
    val prefs: PreferencesManager,
    val settings: AppSettings,
    /** Non-null only when the selected model is a downloaded on-device LiteRT-LM catalog entry. */
    val selectedOfflineModel: OfflineModelInfo?,
    /** Always size 4 — "Custom 1".."Custom 4" (or the recognized-speech title once recorded). */
    val customPromptTexts: List<String>,
    val customPromptTitles: List<String>,
    /** Runs [config] and speaks/shows the result — already routed through download/eligibility gating. */
    val onRunPrompt: (config: PromptConfig, label: String) -> Unit,
    val onExplore: () -> Unit,
    val onRepeat: () -> Unit,
    /** Long-press on a custom-prompt cell (offline models only, see [com.appricodes.lens_whisper.ui.models.OfflineVlmButtonProvider]) —
     *  starts voice recognition targeting that slot index (0-3): what the user says becomes the
     *  slot's new prompt (and runs immediately); silence clears it back to "Custom N". */
    val onRecordCustomPrompt: (slotIndex: Int) -> Unit
)

/**
 * Declares the button set for one model (or model family) and wires each cell to an action.
 * See `res/raw/offline_models.json`/`OfflineModelsRepository` for how models are catalogued, and
 * [com.appricodes.lens_whisper.data.PreferencesManager.selectedModelId] for the selection key
 * ("mlkit", "online", or an [OfflineModelInfo.id]).
 */
interface ModelButtonProvider {
    /**
     * Always exactly 3 rows of exactly 4 cells (pad short rows/unused rows with `null` spacers)
     * so the reserved grid area's height and cell positions never shift when the selected model
     * changes — callers rely on this contract rather than measuring the returned list.
     */
    fun rows(ctx: ModelButtonContext): List<List<GridCell?>>
}
