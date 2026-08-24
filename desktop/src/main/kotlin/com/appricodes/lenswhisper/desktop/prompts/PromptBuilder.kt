package com.appricodes.lenswhisper.desktop.prompts

/**
 * Prompt text for local (llama.cpp/GGUF) analysis. This is a verbatim port of the Android app's
 * `OfflinePromptBuilder` (app/src/main/java/com/appricodes/lens_whisper/utils/OfflinePromptBuilder.kt),
 * which itself mirrors the backend's prompt construction (backend/process.php) — keep all three in
 * sync if wording ever changes.
 */
object PromptBuilder {

    private val predefinedPrompts = mapOf(
        "brief_narration" to "Describe this scene very briefly in a single short sentence. If the scene is totally empty, explicitly state that.",
        "detailed_scene" to "Describe this scene in detail, including people, objects, settings, and overall atmosphere. If nothing distinct is visible, explicitly state that.",
        "people_and_outfit" to "Describe the outfit, clothing, body appearance, and approximate age of the main people visible. If no people are visible, you MUST explicitly state 'No people are visible in this scene'.",
        "emotions" to "Analyze and describe the facial expressions and emotions of each main visible person. If no people or faces are visible, state 'No people or faces are visible'.",
        "objects" to "Identify and list the main physical objects visible and describe their layout or characteristics. Only list the distinct physical objects.",
        "read_texts" to "Identify any visible signs, textual labels, documents, or screen characters and read the text precisely. If absolutely no text visible, state 'No text found'.",
        "story_5" to "Tell the continuous story of what is happening across these sequence frames as a single fluid narrative. If no action, state 'No action detected'.",
        "story_10" to "Tell the continuous story of what is happening across these sequence frames as a single fluid narrative. If no action, state 'No action detected'."
    )

    /** The user-facing instruction text for a predefined prompt id (not "custom" or "explore"). */
    fun userPromptFor(promptId: String): String = predefinedPrompts[promptId] ?: "Describe the image."

    /** The system instruction for a predefined prompt id, mirroring the backend's narration framing. */
    fun buildSystemInstruction(language: String, persona: String, isMultiFrame: Boolean): String {
        var instruction = "You MUST write your response ENTIRELY in the language: $language. " +
            "Limit your entire description to a maximum of 100 words. " +
            "Respond using a $persona narration style. You are an expert audio describer for the visually impaired."
        if (isMultiFrame) {
            instruction += " The user has sent multiple consecutive video frames capturing a timeline sequence. " +
                "Describe the scene's movement and actions over time smoothly."
        }
        return instruction
    }

    /** System instruction for a "custom question" (Custom Prompt 1/2 or Voice Query). */
    fun buildCustomQuestionInstruction(language: String, question: String): String =
        "You MUST write your ENTIRE response in the language $language.\n" +
            "You are a precise visual question-answering assistant for a visually impaired user.\n" +
            "THE USER'S EXACT QUESTION IS: \"$question\"\n" +
            "Answer ONLY the user's exact question above clearly and concisely. If the requested object is not in view, explicitly state that."

    /** System instruction for the Explore spatial-grid analysis; expects a flat JSON response. */
    fun buildExploreSystemInstruction(language: String, persona: String, gridSize: Int = 6): String =
        "You are a precise spatial mapping AI for a visually impaired user's screen-reader app. " +
            "CRITICAL COMPLIANCE: All descriptions assigned to grid keys MUST be written ENTIRELY in $language. " +
            "Limit each description to a few words. " +
            "You must divide the image into a mathematically strict ${gridSize}x${gridSize} physical grid. " +
            "Respond using a $persona description style. " +
            "You MUST respond ONLY with a flat JSON object where the keys are labeled exactly from 'row1_col1' through 'row${gridSize}_col${gridSize}'."

    const val EXPLORE_USER_PROMPT = "Generate the JSON map spanning the entire image."

    /** Resolved system instruction + user prompt for one request, covering the three branches
     * backend/process.php has: explore (spatial JSON grid, forces non-streaming), custom
     * (free-form question), and everything else (a predefined prompt id). Shared by the CLI
     * `analyze` command and the HTTP server's `/process.php` route so they can't drift. */
    fun buildPromptSpec(promptId: String, language: String, persona: String, customQuestion: String, isMultiFrame: Boolean): PromptSpec =
        when (promptId) {
            "explore" -> PromptSpec(
                systemInstruction = buildExploreSystemInstruction(language, persona),
                userPrompt = EXPLORE_USER_PROMPT,
                expectJson = true,
                forceNonStreaming = true,
            )
            "custom" -> PromptSpec(
                systemInstruction = buildCustomQuestionInstruction(language, customQuestion),
                userPrompt = customQuestion,
                expectJson = false,
                forceNonStreaming = false,
            )
            else -> PromptSpec(
                systemInstruction = buildSystemInstruction(language, persona, isMultiFrame),
                userPrompt = userPromptFor(promptId),
                expectJson = false,
                forceNonStreaming = false,
            )
        }
}

data class PromptSpec(
    val systemInstruction: String,
    val userPrompt: String,
    val expectJson: Boolean,
    val forceNonStreaming: Boolean,
)
