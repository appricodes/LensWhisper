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
 * **2. Two of these prompts used to ask the model to do things the EU AI Act treats as a different
 * category of system, and must not start doing so again:**
 *
 * - There used to be an `emotions` prompt asking the model to "analyze and describe the ... emotions
 *   of each main visible person". Inferring emotions from biometric data is an *emotion recognition
 *   system* (Art. 3(39)), which Annex III(1)(c) classes as **high-risk** — conformity assessment, CE
 *   marking, EU database registration, the lot. Describing a *visibly apparent expression* is not
 *   that (Recital 18 draws exactly this line), so [FACIAL_EXPRESSIONS_ID] asks only for what is
 *   physically visible — a smile, a frown, closed eyes — and explicitly forbids inferring a state
 *   of mind.
 * - `people_and_outfit` used to ask for "approximate age". Inferring protected attributes from
 *   biometric data is biometric categorisation under Annex III(1)(b), and Art. 5(1)(g) prohibits it
 *   outright for some attributes. It now asks for clothing and visible appearance only, and
 *   explicitly rules out identity, age, origin and beliefs.
 *
 * The same guardrail sentence is attached only to the people-facing prompts rather than to the
 * shared system instruction, so the common cases (objects, text, brief narration) stay short and
 * fast.
 */
object OfflinePromptBuilder {

    /** Replaces the former "emotions" id. A stored custom/last-prompt referring to the old id will
     *  simply fall through to [userPromptFor]'s default, which is harmless. */
    const val FACIAL_EXPRESSIONS_ID = "facial_expressions"

    /** Attached to the two prompts that involve people. Kept to one sentence for the reasons in
     *  this object's doc. */
    private const val PEOPLE_GUARDRAIL =
        "Do not name or identify anyone, and do not guess anyone's age, origin, beliefs, health, or feelings."

    /** "brief_narration" is meant to be a single short sentence; every other prompt (detailed
     *  scene, people/outfit, facial expressions, objects, read texts, story, and custom questions)
     *  gets the wider budget. Enforced twice: as a word count here, in the system instruction the
     *  model is asked to follow, and as a hard token cap in
     *  [com.appricodes.lens_whisper.inference.OfflineInferenceEngine] regardless of whether the
     *  model actually complies with the word count. */
    const val BRIEF_MAX_WORDS = 20
    const val DEFAULT_MAX_WORDS = 150

    /** [BRIEF_MAX_WORDS] for the brief-narration prompt, [DEFAULT_MAX_WORDS] for every other
     *  predefined prompt. Not meaningful for "custom", which builds its own instruction directly
     *  (see [buildCustomQuestionInstruction]). */
    fun maxWordsFor(promptId: String): Int = if (promptId == "brief_narration") BRIEF_MAX_WORDS else DEFAULT_MAX_WORDS

    private val predefinedPrompts = mapOf(
        "brief_narration" to
            "Describe this scene in one short sentence. If it is empty, say so.",
        "detailed_scene" to
            "Describe this scene: the people, objects, setting and atmosphere. If nothing distinct is visible, say so.",
        "people_and_outfit" to
            "Describe the clothing and visible appearance of the people in view. $PEOPLE_GUARDRAIL " +
                "If no people are visible, say exactly: No people are visible.",
        FACIAL_EXPRESSIONS_ID to
            "Describe only the facial expressions that are physically visible, such as a smile, a frown, " +
                "raised eyebrows, or closed eyes. Do not say what anyone feels or thinks. $PEOPLE_GUARDRAIL " +
                "If no faces are visible, say exactly: No faces are visible.",
        "objects" to
            "List the main objects visible and how they are arranged.",
        "read_texts" to
            "Read the visible text exactly as written. Say which parts are unclear or unreadable instead of " +
                "guessing them. If there is no text, say exactly: No text found.",
        "story_5" to
            "Describe what happens across these frames as one short narrative. If nothing moves, say exactly: No action detected.",
        "story_10" to
            "Describe what happens across these frames as one short narrative. If nothing moves, say exactly: No action detected."
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
     * the user's own selected language when offline translation is off (nothing downstream will
     * translate the reply, so the model has to produce the target language itself, same as
     * before either translation direction existed), or a fixed "English" when translation is on
     * (so [com.appricodes.lens_whisper.offline.OfflineTranslator.translate]'s assumption that its
     * input is English is a guarantee, not a hope — it used to be asked for the target language
     * directly even with translation on, which made that assumption unreliable on small models).
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
     *  to already be in that same language by the time it gets here: with translation on, it was
     *  already translated to English (see
     *  [com.appricodes.lens_whisper.offline.OfflineTranslator.translateToEnglish] and its caller
     *  in `AnalysisSessionController.startVoiceRecognition`) before [language] below ever became
     *  "English"; with translation off, both stay in the user's own selected language untouched. */
    fun buildCustomQuestionInstruction(language: String, question: String): String =
        "You answer questions about an image for a blind user. Reply in $language, in at most $DEFAULT_MAX_WORDS words.\n" +
            "Question: \"$question\"\n" +
            "Answer only that question. If the answer is not visible, say so instead of guessing. " +
            PEOPLE_GUARDRAIL

}
