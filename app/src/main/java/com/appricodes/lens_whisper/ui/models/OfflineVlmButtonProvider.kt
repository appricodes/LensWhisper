package com.appricodes.lens_whisper.ui.models

import com.appricodes.lens_whisper.domain.PromptConfig
import com.appricodes.lens_whisper.ui.components.GridCell

/**
 * Drives every on-device LiteRT-LM catalog model (`gemma4_multimodal_2b`, `fastvlm_0_5b`,
 * `qwen_vl_2b`, see `res/raw/offline_models.json`) with one shared button layout — they all
 * expose the exact same prompt set and only differ in data already captured by
 * [com.appricodes.lens_whisper.domain.OfflineModelInfo] (display name, whether Explore is
 * supported — though Explore itself isn't offered here at all, see below). A future model
 * needing genuinely different buttons should get its own provider instead of being force-fit
 * here.
 *
 * No VLM-grid Explore, no Story (5s)/(10s) — dropped for the offline catalog specifically
 * (on-device multi-frame/spatial-grid inference was too slow/heavy to be worth the grid space). In
 * their place: 4 voice-recordable custom-prompt slots instead of 2 static ones, plus "Describe TV
 * Image" and "Explore and Describe" — a *different*, segmentation-based Explore (see
 * [com.appricodes.lens_whisper.SegmentationExploreActivity]'s describe-mode), not the dropped
 * grid-JSON one; both reuse [com.appricodes.lens_whisper.offline.OfflineSegmentationDetector] the
 * same way `MlkitButtonProvider`'s buttons do, feeding the isolated result to the selected on-device
 * model rather than just naming/coloring it. Long-pressing a custom slot starts voice recognition
 * targeting it — what's said becomes the slot's prompt (and runs immediately); silence clears it
 * back to "Custom N". Tapping a slot with a recorded prompt reruns it on a freshly captured image;
 * tapping an empty one does nothing (there's no prompt to run).
 */
object OfflineVlmButtonProvider : ModelButtonProvider {
    override fun rows(ctx: ModelButtonContext): List<List<GridCell?>> {
        fun promptCell(label: String, promptId: String): GridCell {
            val config = ctx.settings.prompts.find { it.id == promptId }
            return GridCell(label = label, onClick = { if (config != null) ctx.onRunPrompt(config, label) })
        }

        fun customCell(slotIndex: Int): GridCell {
            val title = ctx.customPromptTitles[slotIndex]
            val text = ctx.customPromptTexts[slotIndex]
            val hasPrompt = text.isNotBlank()
            return GridCell(
                label = title,
                contentDescription = if (hasPrompt) {
                    "$title. Tap to ask it again. Long press to record a new question by voice."
                } else {
                    "$title. Empty. Long press to record a question by voice."
                },
                onClick = { if (hasPrompt) ctx.onRunPrompt(PromptConfig("custom", title, text, 1, 0.5), title) },
                onLongClick = { ctx.onRecordCustomPrompt(slotIndex) }
            )
        }

        return listOf(
            listOf(
                promptCell("Brief Narration", "brief_narration"),
                promptCell("Detailed Scene", "detailed_scene"),
                promptCell("People and Outfit", "people_and_outfit"),
                GridCell(
                    label = "Describe TV Image",
                    contentDescription = if (ctx.onDescribeTv != null) {
                        "Describe TV Image. Finds the screen or TV in view and describes it in detail."
                    } else {
                        "Describe TV Image. Not available here — needs a live camera."
                    },
                    enabled = ctx.onDescribeTv != null,
                    onClick = { ctx.onDescribeTv?.invoke() }
                )
            ),
            listOf(
                promptCell("Objects", "objects"),
                promptCell("Read Texts", "read_texts"),
                GridCell(label = "Repeat", contentDescription = "Repeat the last action.", onClick = ctx.onRepeat),
                GridCell(
                    label = "Explore and Describe",
                    contentDescription = if (ctx.onExploreAndDescribe != null) {
                        "Explore and Describe. Opens a full-screen photo — move your finger over an " +
                            "object and tap it for a detailed AI description."
                    } else {
                        "Explore and Describe. Not available here — needs a live camera."
                    },
                    enabled = ctx.onExploreAndDescribe != null,
                    onClick = { ctx.onExploreAndDescribe?.invoke() }
                )
            ),
            // The 4 custom slots get a row of their own, deliberately last — CameraActivity places
            // the AI-inaccuracy disclaimer and the utility row right after whatever this returns,
            // so this row ends up third-from-bottom, exactly where the custom prompts (the ones a
            // user is most likely to reach for repeatedly) are meant to land.
            listOf(
                customCell(0),
                customCell(1),
                customCell(2),
                customCell(3)
            )
        )
    }
}
