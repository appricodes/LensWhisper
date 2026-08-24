package com.appricodes.lens_whisper.ui.models

import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.ui.components.GridCell

/**
 * The 3x4 prompt grid for [OnlineButtonProvider] — the on-device catalog now has its own,
 * substantially different layout (see [OfflineVlmButtonProvider]: no Explore/Story, 4
 * voice-recordable custom slots instead of 2 static ones), so this is Online-only despite the
 * generic name. Kept as `promptGridRows` since only one caller remains and renaming buys nothing.
 *
 * The two custom-prompt cells here are still populated only via the general (non-slot-targeted)
 * voice query — [ModelButtonContext.customPromptTexts]/`customPromptTitles` indices 0/1 — tapping
 * runs the stored prompt; there's no long-press-to-record for Online (that's an offline-only
 * addition, see [ModelButtonContext.onRecordCustomPrompt]).
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
            promptCell("Emotions", "emotions")
        ),
        listOf(
            promptCell("Objects", "objects"),
            promptCell("Read Texts", "read_texts"),
            promptCell("Story (5 seconds)", "story_5"),
            promptCell("Story (10 seconds)", "story_10")
        ),
        listOf(
            customPromptCell(0),
            customPromptCell(1),
            GridCell(
                label = "Explore",
                contentDescription = "Explore. Analyze the layout of the current view in a grid.",
                onClick = ctx.onExplore
            ),
            GridCell(label = "Repeat", contentDescription = "Repeat the last action.", onClick = ctx.onRepeat)
        )
    )
}
