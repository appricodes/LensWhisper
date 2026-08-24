package com.appricodes.lens_whisper.ui.models

import com.appricodes.lens_whisper.ui.components.GridCell

/**
 * The online (Wikiponder-backed) model — same prompt layout as [OfflineVlmButtonProvider] (today's
 * code already routes both through `runSelectedModelAnalysis` transparently), always supports
 * Explore. Every cell's `onClick` (bound into [ModelButtonContext.onRunPrompt]/`onExplore` by the
 * hosting screen) is expected to already be wrapped in
 * [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController.checkOnlineEligibleOrGate] —
 * this provider itself has no eligibility logic, it only declares the buttons.
 */
object OnlineButtonProvider : ModelButtonProvider {
    override fun rows(ctx: ModelButtonContext): List<List<GridCell?>> = promptGridRows(ctx)
}
