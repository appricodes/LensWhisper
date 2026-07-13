package com.appricodes.lens_whisper.utils

/**
 * Max-dimension tiers offline model images are scaled to before inference. Only models whose
 * vision encoder supports multiple resolution tiers (e.g. Gemma 4, which the LiteRT-LM runtime
 * auto-selects a faster/smaller or slower/higher-detail signature for based on how many patches
 * the input image produces) actually change behavior across tiers; models with a single fixed
 * encoder input size (FastVLM, Qwen-VL) resize internally regardless of what's sent, so this is
 * a no-op for them beyond avoiding pointless oversized preprocessing.
 */
object OfflineImageSize {
    const val SMALL = "Small"
    const val MEDIUM = "Medium"
    const val LARGE = "Large"
    val ALL = listOf(SMALL, MEDIUM, LARGE)

    fun maxDimensionFor(tier: String): Int = when (tier) {
        SMALL -> 448
        MEDIUM -> 896
        else -> 1600
    }
}
