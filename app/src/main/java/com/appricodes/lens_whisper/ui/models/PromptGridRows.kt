package com.appricodes.lens_whisper.ui.models

import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.ui.components.GridCell

/**
 * The 3x4 prompt grid for [LocalServerButtonProvider] - the on-device catalog has its own,
 * substantially different layout (see [OfflineVlmButtonProvider]: no Explore/Story, 4
 * voice-recordable custom slots instead of 2 static ones), so this is Local-Server-only despite
 * the generic name.
 *
 * The two custom-prompt cells here are populated only via the general (non-slot-targeted) voice
 * query - [ModelButtonContext.customPromptTexts]/`customPromptTitles` indices 0/1 - tapping runs
 * the stored prompt; there's no long-press-to-record here (that's an on-device-only addition, see
 * [ModelButtonContext.onRecordCustomPrompt]).
 */
internal fun promptGridRows(ctx: ModelButtonContext): List<List<GridCell?>> {
    fun promptCell(label: String, promptId: String): GridCell {
        val config = ctx.settings.prompts.find { it.id == promptId }
        return GridCell(label = label, onClick = { if (config != null) ctx.onRunPrompt(config, label) })
    }

    fun customPromptCell(index: Int): GridCell {
        val title = ctx.customPromptTitles[index]
        val text = ctx.customPromptTexts[index]
        return GridCell(label = title, onClick = { if (text.isNotBlank()) ctx.onRunPrompt(PromptConfig("custom", title, text, 1, 0.5), title) })
    }

    return listOf(
        listOf(
            promptCell("Brief Narration", "brief_narration"),
            promptCell("Detailed Scene", "detailed_scene"),
            promptCell("People and Outfit", "people_and_outfit"),
            // "Facial Expressions" used to sit here — removed 2026-09-01 as unused: never wired to
            // a button on the on-device catalog either (see OfflinePromptBuilder's own doc), so it
            // was dead data with nothing behind it.
            promptCell("Objects", "objects")
        ),
        listOf(
            customPromptCell(0),
            customPromptCell(1),
            GridCell(label = "Repeat", contentDescription = "Repeat the last action.", onClick = ctx.onRepeat),
            // "Read Texts"/"Story (5s)"/"Story (10s)" used to fill out this row and a third one —
            // removed 2026-09-01 alongside their prompt data for the same reason as "Facial
            // Expressions" above. Explore used to sit here too — a structured spatial-grid JSON
            // response, Local-Server-only (the on-device catalog never offered it). Removed
            // 2026-09-01 along with ExploreActivity/SpatialGridParser/ExploreGridOverlay; trailing
            // null spacer per this file's row-of-4 contract (see ModelButtonProvider.rows's doc)
            // rather than a mid-row gap.
            null
        )
    )
}
