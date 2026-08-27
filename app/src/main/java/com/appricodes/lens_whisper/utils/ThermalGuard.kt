package com.appricodes.lens_whisper.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
     * The threshold this blocks at. Originally [PowerManager.THERMAL_STATUS_SEVERE] — measured on
     * real devices to trigger far too early for a bounded, user-initiated foreground task (not the
     * sustained background work Android's thermal guidance is really written for) — raised to
     * [PowerManager.THERMAL_STATUS_CRITICAL], Android's own documented point at which "the platform
     * has done everything [it can] to reduce power" — i.e. the OS itself has already exhausted its
     * own mitigation, not just started considering it. [PowerManager.THERMAL_STATUS_EMERGENCY] (one
     * level above) means components are already shutting down, too late to be a *pause and retry*
     * warning rather than an emergency the OS is already handling on its own.
     */
    private const val BLOCK_AT_OR_ABOVE = PowerManager.THERMAL_STATUS_CRITICAL

    /**
     * Battery-temperature fallback for API < 29, where [PowerManager.getCurrentThermalStatus]
     * doesn't exist at all — [BatteryManager.EXTRA_TEMPERATURE], tenths of a degree Celsius, from
     * the current sticky `ACTION_BATTERY_CHANGED` broadcast. 450 = 45.0°C: the safe-operating upper
     * bound cited consistently across battery-safety sources for sustained Li-ion use (0–45°C is
     * the typical safe charge/operate range; degradation risk climbs from there, genuine
     * thermal-runaway risk doesn't start until roughly 50–60°C) — chosen for real headroom above an
     * ordinarily-warm phone mid-task (well under 40°C) while still catching real overheating with
     * margin before the danger zone.
     *
     * **Not the same signal as [BLOCK_AT_OR_ABOVE], and not meant to line up with it numerically.**
     * Battery temperature lags and runs cooler than the SoC actually doing the inference — often by
     * 5–10°C — so 45°C at the battery reflects a genuinely hot phone, not an early warning; this is
     * a coarser, later-firing proxy for devices with no thermal-status API at all, not a
     * "battery-equivalent" of the CRITICAL threshold above.
     */
    private const val BATTERY_BLOCK_AT_OR_ABOVE_TENTHS_C = 450

    /** Set once the user chooses "Continue Anyway" on the heat warning (see
     *  [com.appricodes.lens_whisper.ui.analysis.AnalysisSessionController]'s thermal dialog) —
     *  every check below reports "not too hot" from then on, for the rest of this process's
     *  lifetime. Deliberately in-memory only, not persisted to [com.appricodes.lens_whisper.data.PreferencesManager]
     *  — per explicit instruction, a fresh app launch should ask again, not remember the override
     *  forever. */
    private var overrideThisSession = false

    /** See [overrideThisSession]. */
    fun dismissForThisSession() {
        overrideThisSession = true
    }

    /** Shown to the user (and read aloud, via the app's existing "ERROR: ..." handling) when
     *  analysis is refused for being too hot. Explicitly reassures that the lighter MLKit tools
     *  still work, since the phone being hot is otherwise an alarming thing to be told mid-task. */
    const val HOT_MESSAGE =
        "Your phone feels hot right now, so on-device AI analysis is paused until it cools down a " +
            "little. Please wait a moment and try again. Text, object, colour, and light-level " +
            "detection are unaffected."

    /** [PowerManager.getCurrentThermalStatus] requires Android 10 (API 29); below that, falls back
     *  to reading battery temperature directly (see [BATTERY_BLOCK_AT_OR_ABOVE_TENTHS_C]) rather
     *  than reporting "not too hot" unconditionally — a phone old enough to lack the thermal API is
     *  no less capable of actually overheating. */
    fun isTooHotForLlm(context: Context): Boolean {
        if (overrideThisSession) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            powerManager.currentThermalStatus >= BLOCK_AT_OR_ABOVE
        } else {
            val tenthsC = batteryTemperatureTenthsC(context) ?: return false
            tenthsC >= BATTERY_BLOCK_AT_OR_ABOVE_TENTHS_C
        }
    }

    /** `registerReceiver(null, filter)` with a null receiver doesn't actually register anything —
     *  it just returns the current sticky broadcast synchronously, the standard way to read battery
     *  state on demand without holding a listener for the app's lifetime (same reasoning as this
     *  class's own doc for why it's query-on-demand, not a persistent thermal listener). Only
     *  reached on API < 29 (see [isTooHotForLlm]), well below the API 33 exported-receiver
     *  requirement that applies to *actual* receiver registrations, not this null-receiver read. */
    private fun batteryTemperatureTenthsC(context: Context): Int? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenthsC = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return if (tenthsC > 0) tenthsC else null
    }

    /** [HOT_MESSAGE] when blocked, null when it's fine to proceed — the same "message-or-null"
     *  shape as every other preflight check in [com.appricodes.lens_whisper.inference.AnalysisRouter]. */
    fun blockingMessageOrNull(context: Context): String? = if (isTooHotForLlm(context)) HOT_MESSAGE else null
}
