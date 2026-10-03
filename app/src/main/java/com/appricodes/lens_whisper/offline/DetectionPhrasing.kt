package com.appricodes.lens_whisper.offline

/**
 * Turns one detection's confidence into a spoken English sentence — shared by every surface built
 * on [OfflineSegmentationDetector] ("Object Detection", "Object and Color Detection", and both
 * Explore screens), so the four don't drift into phrasing confidence differently from each other.
 *
 * A blind user can't glance at the photo to sanity-check a marginal detection, so instead of a
 * single hard cutoff (announce it plainly, or not at all) this hedges: a detection is always
 * spoken, but *how* it's spoken carries how sure the model actually is —
 * - **High** ([Tier.HIGH]): stated plainly, e.g. "Red coach".
 * - **Medium-high** ([Tier.MEDIUM_HIGH]): "Most likely black TV".
 * - **Medium-low** ([Tier.MEDIUM_LOW]): "Maybe white shirt".
 * - Below [MINIMUM_CONFIDENCE]: discarded before this even runs — see
 *   [OfflineSegmentationDetector]'s `CONFIDENCE_THRESHOLD`, which reads that same constant so the
 *   two stay in lockstep by construction rather than as two numbers that happen to agree today.
 *
 * [describeDetection] builds the final sentence from exactly three independently-substitutable
 * parts — the confidence word, the color phrase, and the object name — precisely so a future
 * localization pass can retranslate each on its own rather than pattern-matching a finished English
 * sentence. Today the final English sentence is what reaches the user.
 */
object DetectionPhrasing {

    /** Below this, a detection is discarded before NMS even runs — see
     *  [OfflineSegmentationDetector]'s `CONFIDENCE_THRESHOLD`. Lower than the single cutoff this
     *  used to be (0.35) because the user explicitly wants weaker detections surfaced too, now that
     *  [Tier.MEDIUM_LOW]'s hedge word makes clear they're weaker rather than presenting them with
     *  the same confidence as a strong hit. 0.20 (below Ultralytics' own 0.25 `predict()` default)
     *  is still a real floor, not "show everything" — below it, false positives dominate for a
     *  YOLO-family detector, hedge word or not. */
    const val MINIMUM_CONFIDENCE = 0.20f

    private const val MEDIUM_LOW_FLOOR = MINIMUM_CONFIDENCE // 0.20 - 0.40: "Maybe ..."
    private const val MEDIUM_HIGH_FLOOR = 0.40f // 0.40 - 0.60: "Most likely ..."
    private const val HIGH_FLOOR = 0.60f // 0.60+: stated plainly

    enum class Tier(internal val confidenceWord: String) {
        HIGH(""),
        MEDIUM_HIGH("Most likely"),
        MEDIUM_LOW("Maybe");

        companion object {
            /** [confidence] is assumed to already be >= [MINIMUM_CONFIDENCE] — everything below
             *  that floor never becomes a [DetectedInstance] in the first place, so there's no
             *  "discard" case to represent here. */
            fun of(confidence: Float): Tier = when {
                confidence >= HIGH_FLOOR -> HIGH
                confidence >= MEDIUM_HIGH_FLOOR -> MEDIUM_HIGH
                else -> MEDIUM_LOW
            }
        }
    }

    /**
     * Builds the full spoken phrase for one detection from its three independent parts:
     * - [objectName]: the COCO class label, e.g. "tv".
     * - [colorPhrase]: e.g. "black", or "black and white" — pass blank/empty to omit color
     *   entirely (used by the colorless "Object Detection" mode), in which case the sentence is
     *   just the confidence word plus the object name.
     * - the confidence word, derived from [confidence] via [Tier.of] — not a parameter, since it's
     *   never meaningful to say out loud independent of an actual detection's own confidence.
     *
     * Format is `[confidence word] object[, color]` — e.g. `describeDetection(0.92f, "hat", "green")`
     * -> "Hat, green"; `describeDetection(0.3f, "hat", "green")` -> "Maybe hat, green".
     * Capitalization here is sentence-initial only ("Most likely black tv"); callers that want a
     * class name capitalized (like "TV") do that themselves, since not every one of the 80 COCO
     * labels should be.
     */
    fun describeDetection(confidence: Float, objectName: String, colorPhrase: String = ""): String {
        val confidenceWord = Tier.of(confidence).confidenceWord
        val subject = if (confidenceWord.isEmpty()) objectName else "$confidenceWord $objectName"
        val sentence = if (colorPhrase.isBlank()) subject else "$subject, $colorPhrase"
        return sentence.replaceFirstChar { it.uppercase() }
    }
}
