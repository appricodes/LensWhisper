package com.appricodes.lens_whisper.offline

import android.content.Context
import android.graphics.Bitmap
import com.appricodes.lens_whisper.data.PreferencesManager

/**
 * Wraps [OfflineSegmentationDetector.detect] with a timer, so every caller (`MlkitButtonProvider`,
 * `SegmentationExploreActivity`, the Read Document screen) gets the same one-time "this seems slow
 * — switch to the smaller model?" trigger logic for free, rather than three copies of it.
 *
 * "m" is the default and "Recommended" size (see [PreferencesManager.segmentationModelSize]'s
 * doc), but it can genuinely be too slow on an older/weaker device — this surfaces that as a
 * one-time, user-decidable prompt (see [com.appricodes.lens_whisper.ui.components.SlowModelSwitchDialog])
 * instead of just quietly staying slow.
 */
object SegmentationSpeedGate {

    /** Above this, a single detection run on the default "m" size is considered "slow enough to
     *  ask about". */
    private const val SLOW_THRESHOLD_MS = 4000L

    /**
     * Runs [OfflineSegmentationDetector.detect] on [bitmap], timed. [onSlowFirstDetected] is
     * called (on whatever thread this itself runs on — callers already run detection off the main
     * thread, so this does no thread-switching of its own) exactly when a run exceeds
     * [SLOW_THRESHOLD_MS] *and* [PreferencesManager.segmentationModelSize] is still "m" *and* the
     * prompt hasn't already been shown once — never on "s" (nothing smaller to offer) and never a
     * second time, regardless of how many more slow runs happen after the first.
     */
    fun detect(
        context: Context,
        bitmap: Bitmap,
        prefs: PreferencesManager,
        onSlowFirstDetected: () -> Unit
    ): List<DetectedInstance> {
        val startMs = System.currentTimeMillis()
        val result = OfflineSegmentationDetector.detect(context, bitmap, prefs)
        val elapsedMs = System.currentTimeMillis() - startMs
        if (elapsedMs > SLOW_THRESHOLD_MS &&
            prefs.segmentationModelSize == OfflineSegmentationDetector.SIZE_MEDIUM &&
            !prefs.hasShownSlowModelSwitchPrompt
        ) {
            onSlowFirstDetected()
        }
        return result
    }
}
