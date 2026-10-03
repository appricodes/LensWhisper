package com.appricodes.lens_whisper.utils

/**
 * Prompt text for on-device (LiteRT-LM) analysis.
 *
 * Two constraints shape everything here, and both are easy to undo by accident.
 *
 * **1. Length is latency.** Every token of system instruction and user prompt is prefilled before
 * the first output token appears, on a phone. The wording below is deliberately terse — dropped
 * politeness, no restating of the obvious, no "you are an expert ..." framing that buys nothing
 * from a 500M-parameter model. Do not pad these back out.
 *
 * **2. One of these prompts used to ask the model to do things the EU AI Act treats as a different
 * category of system, and must not start doing so again:**
 *
 * - There used to be an `emotions` prompt asking the model to "analyze and describe the ... emotions
 *   of each main visible person" (later reworded into a `facial_expressions` prompt, removed
 *   2026-09-01 as unused — see the on-device grid's own doc for which prompts it actually offers).
 *   Inferring emotions from biometric data is an *emotion recognition system* (Art. 3(39)), which
 *   Annex III(1)(c) classes as **high-risk** — conformity assessment, CE marking, EU database
 *   registration, the lot. Describing a *visibly apparent expression* is not that (Recital 18 draws
 *   exactly this line) — if a facial-expression prompt is ever reintroduced, it must ask only for
 *   what is physically visible (a smile, a frown, closed eyes) and explicitly forbid inferring a
 *   state of mind, the same as it used to.
 * - `people_and_outfit` used to ask for "approximate age". Inferring protected attributes from
 *   biometric data is biometric categorisation under Annex III(1)(b), and Art. 5(1)(g) prohibits it
 *   outright for some attributes. It now asks for clothing and visible appearance only, and
 *   explicitly rules out identity, age, origin and beliefs.
 *
 * The same guardrail sentence is attached only to the people-facing prompt rather than to the
 * shared system instruction, so the common cases (objects, brief narration, detailed scene) stay
 * short and fast.
 */
object OfflinePromptBuilder {

    /** Attached to the one prompt that involves people. Kept to one sentence for the reasons in
     *  this object's doc. */
    private const val PEOPLE_GUARDRAIL =
        "Do not name or identify anyone, and do not guess anyone's age, origin, beliefs, health, or feelings."

    /** "brief_narration" is meant to be a single short sentence; every other prompt (detailed
     *  scene, people/outfit, objects, and custom questions) gets the wider budget. Enforced twice:
     *  as a word count here, in the system instruction the model is asked to follow, and as a hard
     *  token cap in [com.appricodes.lens_whisper.inference.OfflineInferenceEngine] regardless of
     *  whether the model actually complies with the word count. */
    const val BRIEF_MAX_WORDS = 20
    const val DEFAULT_MAX_WORDS = 150

    /** [BRIEF_MAX_WORDS] for the brief-narration prompt, [DEFAULT_MAX_WORDS] for every other
     *  predefined prompt. Not meaningful for "custom", which builds its own instruction directly
     *  (see [buildCustomQuestionInstruction]). */
    fun maxWordsFor(promptId: String): Int = if (promptId == "brief_narration") BRIEF_MAX_WORDS else DEFAULT_MAX_WORDS

    // The on-device VLM grid (OfflineVlmButtonProvider) is the only reachable caller of
    // userPromptFor below, and offers exactly these four buttons + custom voice-recorded slots —
    // "facial_expressions"/"read_texts"/"story_5"/"story_10" used to exist here too but were never
    // wired to a button on it (only to the Local Server grid, itself unreachable while
    // LocalServerFeature.ENABLED is false); removed 2026-09-01 as dead data rather than left to
    // silently bit-rot.
    private val predefinedPrompts = mapOf(
        "brief_narration" to
            "Describe this scene in one short sentence. If it is empty, say so.",
        "detailed_scene" to
            "Describe this scene: the people, objects, setting and atmosphere. If nothing distinct is visible, say so.",
        "people_and_outfit" to
            "Describe the clothing and visible appearance of the people in view. $PEOPLE_GUARDRAIL " +
                "If no people are visible, say exactly: No people are visible.",
        "objects" to
            "List the main objects visible, their colors, and how they are arranged."
    )

    /** The user-facing instruction text for a predefined prompt id (not "custom"). */
    fun userPromptFor(promptId: String): String = predefinedPrompts[promptId] ?: "Describe the image."

    /**
     * The shared system instruction. The "only what is actually visible ... say so rather than
     * guessing" clause is the one piece of safety wording that earns its tokens on every single
     * request: these models will otherwise invent plausible detail, and a blind user has no way to
     * catch it.
     *
     * [maxWords] should come from [maxWordsFor] — passed in rather than looked up here because the
     * caller ([com.appricodes.lens_whisper.inference.AnalysisRouter]) already has the prompt id and
     * this function otherwise has no way to know it.
     *
     * [language] is resolved by that same caller, not decided here — this object takes no
     * [com.appricodes.lens_whisper.data.PreferencesManager] dependency of its own. The policy:
     * the user's own selected language for a multilingual model, a fixed "English" for an
     * English-only one (see [com.appricodes.lens_whisper.domain.OfflineModelInfo.multilingual]).
     */
    fun buildSystemInstruction(language: String, persona: String, isMultiFrame: Boolean, maxWords: Int): String {
        var instruction = "You describe images for a blind user. Reply in $language, under $maxWords words, in a $persona style. " +
            "Describe only what is actually visible. If something is unclear, say so instead of guessing."
        if (isMultiFrame) {
            instruction += " These are consecutive video frames; describe how the scene changes over time."
        }
        return instruction
    }

    /** System instruction for a "custom question" (a custom prompt slot or a voice query). Uses
     *  [DEFAULT_MAX_WORDS] — a custom question is never the brief-narration prompt. [language] is
     *  resolved the same way [buildSystemInstruction]'s is — see its doc. [question] is expected
     *  to already be in that same language: `AnalysisSessionController.startVoiceRecognition`
     *  recognizes speech as English for an English-only model. */
    fun buildCustomQuestionInstruction(language: String, question: String): String =
        "You answer questions about an image for a blind user. Reply in $language, in at most $DEFAULT_MAX_WORDS words.\n" +
            "Question: \"$question\"\n" +
            "Answer only that question. If the answer is not visible, say so instead of guessing. " +
            PEOPLE_GUARDRAIL

}
