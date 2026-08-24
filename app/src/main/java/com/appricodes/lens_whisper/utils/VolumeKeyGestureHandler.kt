package com.appricodes.lens_whisper.utils

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.appricodes.lens_whisper.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LONG_PRESS_THRESHOLD_MS = 2000L

/**
 * Volume-key handling used from each Activity's `onKeyDown`/`onKeyUp` overrides (only active
 * while [PreferencesManager.volumeShortcutsEnabled] is on — a no-op passthrough otherwise, so the
 * system handles volume keys entirely normally).
 *
 * A **short** press (released before [LONG_PRESS_THRESHOLD_MS]) behaves like an ordinary hardware
 * volume key — one volume step — so enabling shortcuts doesn't lock the user out of normal volume
 * control. Critically, that step only happens on key-**up**, never on key-down: since we can't
 * know in advance whether a press will turn out to be short or long, adjusting on key-down would
 * cause an unwanted volume blip on every long-press too, right before the 2s threshold fires.
 *
 * A press held **past** 2s instead fires the app's shortcut — Volume Up = repeat the last prompt,
 * Volume Down = start a voice query — and suppresses the volume step for that press entirely.
 * Volume Down's mic session is also tied to the physical key: it keeps listening only while the
 * key stays down, and [onMicStop] force-stops it the instant the key is released (unlike an
 * on-screen mic button/long-press, which always runs until the recognizer stops itself).
 */
class VolumeKeyGestureHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: PreferencesManager,
    private val onRepeat: () -> Unit,
    private val onMicStart: () -> Unit,
    private val onMicStop: () -> Unit
) {
    private var upJob: Job? = null
    private var downJob: Job? = null
    private var upLongPressFired = false
    private var downLongPressFired = false

    /** Call from `onKeyDown`; returns whether the event was consumed. */
    fun onKeyDown(keyCode: Int): Boolean {
        if (!prefs.volumeShortcutsEnabled) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (upJob == null) {
                    upLongPressFired = false
                    upJob = scope.launch {
                        delay(LONG_PRESS_THRESHOLD_MS)
                        upLongPressFired = true
                        VolumeShortcuts.interruptTalkBackSpeech(context)
                        onRepeat()
                    }
                }
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (downJob == null) {
                    downLongPressFired = false
                    downJob = scope.launch {
                        delay(LONG_PRESS_THRESHOLD_MS)
                        downLongPressFired = true
                        VolumeShortcuts.interruptTalkBackSpeech(context)
                        onMicStart()
                    }
                }
                true
            }
            else -> false
        }
    }

    /** Call from `onKeyUp`; returns whether the event was consumed. */
    fun onKeyUp(keyCode: Int): Boolean {
        if (!prefs.volumeShortcutsEnabled) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                upJob?.cancel()
                upJob = null
                if (!upLongPressFired) adjustVolume(raise = true)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                downJob?.cancel()
                downJob = null
                if (!downLongPressFired) adjustVolume(raise = false) else onMicStop()
                true
            }
            else -> false
        }
    }

    private fun adjustVolume(raise: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustSuggestedStreamVolume(
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            AudioManager.FLAG_SHOW_UI
        )
    }
}
