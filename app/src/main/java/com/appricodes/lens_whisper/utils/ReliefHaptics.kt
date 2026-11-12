package com.appricodes.lens_whisper.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.appricodes.lens_whisper.data.PreferencesManager
import kotlin.math.roundToInt

/**
 * Continuous, relief-driven vibration for [com.appricodes.lens_whisper.ReliefExploreActivity]:
 * while a finger rests on the photo the motor runs non-stop, stronger over raised spots and
 * weaker over recessed ones.
 *
 * Implemented as one *repeating* waveform per strength level, swapped only when the level under
 * the finger actually changes (and at most every [MIN_SWAP_MS]) — re-firing a one-shot on every
 * pointer move would keep restarting the motor and feel like stutter rather than texture.
 * [flush] lets the caller push a level that was held back by that rate limit once the finger
 * slows down, so the last reading is never stale.
 *
 * Phones without amplitude control get the same information as pulse *rate* instead: short fixed
 * pulses, packed tighter the higher the relief.
 *
 * Callers must call [stop] whenever the finger lifts or the screen goes away — the waveform
 * repeats indefinitely otherwise.
 */
class ReliefHaptics(context: Context) {

    private val appContext = context.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    val hasVibrator: Boolean = vibrator?.hasVibrator() == true
    val hasAmplitudeControl: Boolean = hasVibrator && vibrator?.hasAmplitudeControl() == true
    val enabledInSettings: Boolean get() = PreferencesManager(appContext).vibrationEnabled

    private var playingLevel = -1
    private var wantedLevel = -1
    private var lastSwapAt = 0L

    /** [relief] in -1..1 (see [com.appricodes.lens_whisper.offline.ReliefMap]). */
    fun update(relief: Float) {
        wantedLevel = (((relief.coerceIn(-1f, 1f) + 1f) / 2f) * (LEVELS - 1)).roundToInt()
        flush()
    }

    /** Applies the most recent [update] if the rate limit held it back. Cheap to call often. */
    fun flush() {
        val level = wantedLevel
        if (level < 0 || level == playingLevel) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSwapAt < MIN_SWAP_MS) return
        lastSwapAt = now
        playingLevel = level
        play(level)
    }

    fun stop() {
        wantedLevel = -1
        if (playingLevel < 0) return
        playingLevel = -1
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    private fun play(level: Int) {
        val vib = vibrator ?: return
        if (!hasVibrator || !enabledInSettings) return
        val t = level.toFloat() / (LEVELS - 1) // 0 = deepest hollow, 1 = highest bump
        val effect = if (hasAmplitudeControl) {
            // Even the deepest hollow stays perceptible (MIN_AMPLITUDE) — "weaker", never "nothing",
            // so the user can still tell the finger is on the picture. Squared so the top end, where
            // the interesting bumps are, gets more of the motor's range.
            val amplitude = (MIN_AMPLITUDE + (255 - MIN_AMPLITUDE) * t * t).roundToInt().coerceIn(1, 255)
            VibrationEffect.createWaveform(longArrayOf(0L, 10_000L), intArrayOf(0, amplitude), 1)
        } else {
            val gap = (MAX_GAP_MS - (MAX_GAP_MS - MIN_GAP_MS) * t).toLong()
            VibrationEffect.createWaveform(longArrayOf(0L, PULSE_MS, gap), 1)
        }
        try {
            vib.vibrate(effect)
        } catch (_: Exception) {
            // Some OEM HALs reject unusual waveforms — feedback is never worth a crash.
        }
    }

    private companion object {
        const val LEVELS = 16
        const val MIN_SWAP_MS = 30L
        const val MIN_AMPLITUDE = 18
        const val PULSE_MS = 14L
        const val MIN_GAP_MS = 12L
        const val MAX_GAP_MS = 160L
    }
}
