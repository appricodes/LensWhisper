package com.appricodes.lens_whisper.utils

import com.appricodes.lens_whisper.domain.PromptConfig

/**
 * Shared utilities for building system prompts, especially for custom user questions
 * (Voice Query and Custom Prompts 1/2).
 */
object PromptUtils {

    /**
     * Determines whether the given prompt execution should be treated as a "custom question"
     * where the user is asking something specific (via microphone or custom prompt fields)
     * instead of a pre-defined narration prompt.
     */
    fun isCustomQuestion(config: PromptConfig, label: String = ""): Boolean {
        val title = config.title
        return label == "Voice Query" ||
               title == "Voice Query" ||
               title == "Custom Prompt 1" ||
               title == "Custom Prompt 2"
    }

    /**
     * Builds a strict system prompt for custom user questions.
     *
     * The goal is to force the model to directly answer the user's specific question
     * and NEVER fall back to a general image/video description — especially when the
     * asked-about object/element is not visible in the current frame.
     */
    fun buildCustomQuestionSystemPrompt(
        language: String,
        userQuestion: String,
        isVideo: Boolean = false
    ): String {
        val mediaType = if (isVideo) "video footage (sequence of frames)" else "image or camera view"
        val changeNote = if (isVideo)
            "or any visual changes or motion visible across the frames"
        else
            ""

        return """
CRITICAL COMPLIANCE: You MUST write your ENTIRE response in the language $language.
Do not use any English words or sentences under any circumstances.

You are a precise visual question-answering assistant for a visually impaired user.
The user is pointing their camera or looking at a screen and has asked a specific question about what they are currently seeing.

THE USER'S EXACT QUESTION IS: "$userQuestion"

MANDATORY RULES — YOU MUST FOLLOW THESE EXACTLY:
1. Answer ONLY the user's exact question above. Give a direct, clear, and concise answer.
2. DO NOT describe the overall scene, background, lighting, layout, other objects, or give any general narration.
3. Base your answer strictly and only on visual evidence present in the provided image/frames.
4. If the thing the user is asking about is NOT visible in the current view (for example, they ask about a keyboard but there is no keyboard), you MUST explicitly say so. Good examples:
   - "There is no keyboard visible in the image."
   - "I cannot see any keyboard, so I cannot tell its color."
   - "The object you asked about is not in view."
5. Never invent details. Never switch to describing the image just because you cannot answer the exact question.
6. If you genuinely cannot answer the question from what is visible, state that fact briefly and stop. Do not add extra descriptive sentences.
7. Keep the response short and natural for someone listening via text-to-speech.
8. The only information you may mention is what is directly required to answer the exact question the user asked.

Remember: The user does NOT want a description. They want an answer to their specific question.
""".trimIndent()
    }
}
