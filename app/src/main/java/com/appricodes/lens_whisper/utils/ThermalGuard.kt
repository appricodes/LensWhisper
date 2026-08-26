package com.appricodes.lens_whisper.utils

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Keeps the on-device VLM catalog (Gemma4, SmolVLM2, Qwen-VL, ...) from running while the phone is
 * already thermally stressed — sustained inference is tens of seconds of near-continuous CPU/GPU
 * load, which is exactly the kind of work that makes a hot phone hotter right when the OS is trying
 * to cool it down. [com.appricodes.lens_whisper.inference.AnalysisRouter] checks this before every
 * on-device analysis, the same way it already checks the model is downloaded and an image exists.
 *
 * **Deliberately does not touch the MLKit-based tools** (object/text/colour detection, the light
 * meter — see [com.appricodes.lens_whisper.ui.models.MlkitButtonProvider]). Those never route
 * through [com.appricodes.lens_whisper.inference.OfflineInferenceEngine] or this guard at all —
 * each single-shot MLKit call is milliseconds of work, not remotely comparable to a VLM generating
 * a whole response token by token, and blocking them too would leave a user with no working tool at
 * exactly the moment their phone is uncomfortably hot.
 *
 * Query-on-demand ([isTooHotForLlm] is called right before each analysis), not a persistent
 * listener — there's nothing else in the app that needs to react to thermal state continuously, so
 * there's no reason to hold a registered [PowerManager.OnThermalStatusChangedListener] for the
 * app's whole lifetime.
 */
object ThermalGuard {

    /**
     * The threshold this blocks at. [PowerManager.THERMAL_STATUS_SEVERE] is Android's own
     * documented point at which "the device is beginning to require mitigation and the system may
     * begin throttling components to reduce thermal load" — the level at which Android's thermal
     * API guidance tells apps to reduce performance-intensive work themselves, not a number picked
     * without reference. [PowerManager.THERMAL_STATUS_MODERATE] (one level below) still just means
     * "a bit warm, nothing needs to change yet", so blocking there would refuse VLM analysis far
     * more often than the situation actually calls for.
     */
    private const val BLOCK_AT_OR_ABOVE = PowerManager.THERMAL_STATUS_SEVERE

    /** Shown to the user (and read aloud, via the app's existing "ERROR: ..." handling) when
     *  analysis is refused for being too hot. Explicitly reassures that the lighter MLKit tools
     *  still work, since the phone being hot is otherwise an alarming thing to be told mid-task. */
    const val HOT_MESSAGE =
        "Your phone feels hot right now, so on-device AI analysis is paused until it cools down a " +
            "little. Please wait a moment and try again. Text, object, colour, and light-level " +
            "detection are unaffected."

    /** [PowerManager.getCurrentThermalStatus] requires Android 10 (API 29) — on anything older
     *  there's no thermal-status API to query at all, so this reports "not too hot" rather than
     *  guessing or blocking unconditionally. */
    fun isTooHotForLlm(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.currentThermalStatus >= BLOCK_AT_OR_ABOVE
    }

    /** [HOT_MESSAGE] when blocked, null when it's fine to proceed — the same "message-or-null"
     *  shape as every other preflight check in [com.appricodes.lens_whisper.inference.AnalysisRouter]. */
    fun blockingMessageOrNull(context: Context): String? = if (isTooHotForLlm(context)) HOT_MESSAGE else null
}
